package com.repl.bubbledrawer.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.xposed.RemotePrefs
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-user helper for Android & MIUI / HyperOS (XSpace / Dual Apps / Work Profile).
 * References KernelSU UserManager.getAliveUsers and MIUI SecurityCenter cross-user mechanisms.
 */
object MultiUserHelper {
    private const val TAG = "MeiWindow_MultiUser"

    /** Returns the user ID (identifier) for a [UserHandle], default 0. */
    fun getUserId(user: UserHandle): Int {
        return runCatching {
            UserHandle::class.java.getMethod("getIdentifier").invoke(user) as Int
        }.getOrElse {
            runCatching {
                val f = UserHandle::class.java.getDeclaredField("mHandle")
                f.isAccessible = true
                f.getInt(user)
            }.getOrElse { user.hashCode() }
        }
    }

    /** Instantiates a [UserHandle] for a given [userId]. */
    fun getUserHandle(userId: Int): UserHandle? {
        if (userId == 0) return Process.myUserHandle()

        // 1. UserHandle.of(userId) — standard static factory method since Android 7.0 (API 24)
        runCatching {
            val m = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
            val h = m.invoke(null, userId) as? UserHandle
            if (h != null) return h
        }

        // 2. UserHandle(userId) constructor via getDeclaredConstructor + setAccessible
        runCatching {
            val ctor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            val h = ctor.newInstance(userId) as? UserHandle
            if (h != null) return h
        }

        // 3. UserHandle.getUserHandleForUid(userId * 100000)
        runCatching {
            val m = UserHandle::class.java.getMethod("getUserHandleForUid", Int::class.javaPrimitiveType)
            val h = m.invoke(null, userId * 100000) as? UserHandle
            if (h != null) return h
        }

        // 4. Xiaomi XSpace specific helper
        if (userId == 999) {
            runCatching {
                val cls = Class.forName("miui.securityspace.XSpaceUserHandle")
                val f = cls.getDeclaredField("XSPACE_USER_HANDLE")
                f.isAccessible = true
                val h = f.get(null) as? UserHandle
                if (h != null) return h
            }
        }

        return null
    }

    /**
     * Enumerates all active user IDs on the device.
     * Incorporates KernelSU's `getAliveUsers()` technique, `userProfiles`, and explicit HyperOS XSpace 999 check.
     */
    fun getAllUserIds(context: Context): List<Int> {
        val userIds = linkedSetOf<Int>()
        userIds.add(0)
        // Users the UserManager itself reported, so "does user 999 exist" can be answered
        // without `getUserInfo(int)` (hidden API; `UserManager.getUserInfo(int)` is not in the
        // public SDK — the compile against android-37 rejected it).
        val knownUsers = mutableSetOf<Int>()

        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (um != null) {
            // KernelSU approach: getAliveUsers()
            runCatching {
                val method = um.javaClass.getMethod("getAliveUsers")
                val users = method.invoke(um) as? List<*>
                users?.forEach { user ->
                    user?.let {
                        val idField = it.javaClass.getField("id")
                        val id = idField.getInt(it)
                        userIds.add(id)
                        knownUsers.add(id)
                    }
                }
            }.onFailure { Log.d(TAG, "getAliveUsers reflection failed: ${it.message}") }

            // Standard approach: userProfiles
            runCatching {
                um.userProfiles.forEach { profile ->
                    val id = getUserId(profile)
                    userIds.add(id)
                    knownUsers.add(id)
                }
            }.onFailure { Log.d(TAG, "userProfiles lookup failed: ${it.message}") }
        }

        // HyperOS / MIUI XSpace User 999 check.
        //
        // `getUserHandle(999)` used to stand in for "does user 999 exist" — but it only calls
        // `UserHandle.of(999)`, which never returns null, so 999 was added on EVERY device and
        // every load then ran the whole "is pkg installed for 999" fallback below (one binder
        // round-trip per app, measured 216 on this phone) against a user that is not there.
        // Verified on device 2026-10-09 with 双开 switched off: `settings get secure
        // xspace_enabled` = 0, `pm list users` = user 0 only, while the module still logged
        // `Discovered userIds: [0, 999]`. The ROM flag stays the primary signal (XSpace users
        // are hidden from `pm list users` on some builds); UserManager's own list is the second.
        if (!userIds.contains(999)) {
            val xspaceEnabled = runCatching {
                android.provider.Settings.Secure.getInt(context.contentResolver, "xspace_enabled", 0) == 1
            }.getOrDefault(false)

            if (xspaceEnabled || knownUsers.contains(999)) {
                userIds.add(999)
            }
        }

        Log.i(TAG, "getAllUserIds result: $userIds")
        return userIds.toList()
    }

    /**
     * Helper to unwrap List<T> from a ParceledListSlice or List.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> extractList(sliceOrList: Any?): List<T>? {
        if (sliceOrList == null) return null
        if (sliceOrList is List<*>) return sliceOrList as? List<T>
        return runCatching {
            val m = sliceOrList.javaClass.getMethod("getList")
            m.invoke(sliceOrList) as? List<T>
        }.getOrNull()
    }

    /**
     * Queries launcher activities for a given [userId] via IPackageManager Binder or LauncherApps.
     */
    fun queryLauncherActivitiesForUser(context: Context, userId: Int): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // 1. Primary: ActivityThread.getPackageManager().queryIntentActivities(Intent, String, long/int, int)
        runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val getPm = atCls.getMethod("getPackageManager")
            val ipm = getPm.invoke(null) ?: return@runCatching
            for (m in ipm.javaClass.methods) {
                if (m.name == "queryIntentActivities") {
                    val ptypes = m.parameterTypes
                    // (Intent, String, long/int, int)
                    if (ptypes.size == 4 && ptypes[0] == Intent::class.java && ptypes[3] == Int::class.javaPrimitiveType) {
                        val flagArg: Any = if (ptypes[2] == Long::class.javaPrimitiveType) 0L else 0
                        val res = m.invoke(ipm, intent, null, flagArg, userId)
                        val list = extractList<ResolveInfo>(res)
                        if (!list.isNullOrEmpty()) {
                            Log.i(TAG, "IPackageManager resolved ${list.size} activities for user $userId")
                            return list
                        }
                    }
                }
            }
        }.onFailure { Log.d(TAG, "IPackageManager queryIntentActivities failed for user $userId: ${it.message}") }

        // 2. Secondary: LauncherApps.getActivityList
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val userHandle = getUserHandle(userId)
        if (launcherApps != null && userHandle != null) {
            val list = runCatching { launcherApps.getActivityList(null, userHandle) }.getOrNull()
            if (!list.isNullOrEmpty()) {
                Log.i(TAG, "LauncherApps resolved ${list.size} activities for user $userId")
                return list.map { lai ->
                    ResolveInfo().apply {
                        activityInfo = android.content.pm.ActivityInfo().apply {
                            packageName = lai.applicationInfo.packageName
                            name = lai.name
                            applicationInfo = lai.applicationInfo
                        }
                    }
                }
            }
        }

        // 3. Fallback: PackageManager.queryIntentActivitiesAsUser
        val pm = context.packageManager
        runCatching {
            for (m in pm.javaClass.methods) {
                if (m.name == "queryIntentActivitiesAsUser") {
                    val ptypes = m.parameterTypes
                    if (ptypes.size == 3 && ptypes[0] == Intent::class.java && ptypes[2] == Int::class.javaPrimitiveType) {
                        val flagArg: Any = if (ptypes[1] == Long::class.javaPrimitiveType) 0L else 0
                        val res = m.invoke(pm, intent, flagArg, userId) as? List<*>
                        val list = res?.filterIsInstance<ResolveInfo>()
                        if (!list.isNullOrEmpty()) return list
                    }
                }
            }
        }

        return emptyList()
    }

    /**
     * Checks if a package is installed for a specific [userId] (SecurityCenter method).
     */
    fun isPackageInstalledForUser(context: Context, packageName: String, userId: Int): Boolean {
        // 1. IPackageManager.getPackageInfo
        val okFromIpm = runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val getPm = atCls.getMethod("getPackageManager")
            val ipm = getPm.invoke(null) ?: return@runCatching false
            for (m in ipm.javaClass.methods) {
                if (m.name == "getPackageInfo") {
                    val ptypes = m.parameterTypes
                    if (ptypes.size == 3 && ptypes[0] == String::class.java && ptypes[2] == Int::class.javaPrimitiveType) {
                        val flagArg: Any = if (ptypes[1] == Long::class.javaPrimitiveType) 0L else 0
                        val pi = m.invoke(ipm, packageName, flagArg, userId)
                        if (pi != null) return@runCatching true
                    }
                }
            }
            false
        }.getOrDefault(false)
        if (okFromIpm) return true

        // 2. PackageManager.getPackageInfoAsUser
        val pm = context.packageManager
        val okFromPm = runCatching {
            for (m in pm.javaClass.methods) {
                if (m.name == "getPackageInfoAsUser") {
                    val ptypes = m.parameterTypes
                    if (ptypes.size == 3 && ptypes[0] == String::class.java && ptypes[2] == Int::class.javaPrimitiveType) {
                        val flagArg: Any = if (ptypes[1] == Long::class.javaPrimitiveType) 0L else 0
                        val pi = m.invoke(pm, packageName, flagArg, userId)
                        if (pi != null) return@runCatching true
                    }
                }
            }
            false
        }.getOrDefault(false)

        return okFromPm
    }

    /**
     * Locates the launcher ComponentName for [packageName] on [userId].
     */
    fun findComponentForPackage(context: Context, packageName: String, userId: Int): ComponentName? {
        val activities = queryLauncherActivitiesForUser(context, userId)
        val match = activities.firstOrNull { it.activityInfo?.packageName == packageName }
        if (match != null && match.activityInfo != null) {
            return ComponentName(match.activityInfo.packageName, match.activityInfo.name)
        }
        return null
    }
}

/**
 * Enumerates launchable apps across all user profiles (including HyperOS XSpace 999)
 * and caches icons with official badges.
 *
 * ## The cache is only as good as the moment it was filled
 *
 * The fan and its 更多 panel live inside SystemUI, and SystemUI's own module install runs
 * at BOOT — i.e. while the user is still locked. In that state the platform's package
 * queries answer with a tiny, direct-boot-shaped subset (measured on device 2026-10-09:
 * `AppRepository.loadAll` → 2 apps, 设置 + 联系人, versus 216 once unlocked; `uid=10231`
 * `com.android.systemui`, probe in `AppEnumProbe`). Because [cache] is per SystemUI
 * process and every reader takes it as-is (`cachedAll()`), that boot-time subset used to
 * survive the whole boot: after two reboots the panel showed those two apps only, while
 * the module's own Activity (whose process starts after unlock) showed all of them.
 *
 * Hence [cacheNeedsRefresh]: a list enumerated while locked is PROVISIONAL, and every
 * reader asks this before trusting it. FanHost additionally reloads on ACTION_USER_UNLOCKED.
 */
class AppRepository(
    private val context: Context,
    /**
     * 分身角标开关（[RemotePrefs.KEY_CLONE_BADGE]）的取值入口。两个宿主各有自己的设置来源
     * （SystemUI 读 LSPosed 远程组，应用进程读本地镜像），所以由宿主传进来；只用于把图标
     * 预热到正确的缓存键上 —— 真正渲染时每个调用点还会再传一次当次的开关值。
     */
    private val cloneBadge: () -> Boolean = { RemotePrefs.DEFAULT_CLONE_BADGE },
) {

    @Volatile
    private var cache: ImmutableList<BubbleApp> = kotlinx.collections.immutable.persistentListOf()

    /** True when [cache] came from a query made while the user was still locked. */
    @Volatile
    private var cacheEnumeratedWhileLocked: Boolean = false

    /** Synchronous view of the last [loadAll] (bubble bar reads this on expand). */
    fun cachedAll(): ImmutableList<BubbleApp> = cache

    /**
     * True when the cache must not be trusted for display: it is empty, or it was filled
     * while the user was locked and the device is unlocked now (the boot case above).
     * Callers reload instead of showing a two-app "all apps" list.
     */
    fun cacheNeedsRefresh(): Boolean =
        cache.isEmpty() || (cacheEnumeratedWhileLocked && userUnlocked(context))

    /**
     * The list every UI reads: the cache when it can be trusted, a fresh [loadAll] otherwise.
     * This is the replacement for the old `cachedAll().ifEmpty { loadAll() }` idiom, which
     * only noticed an EMPTY cache and therefore kept serving the boot's two-app answer.
     */
    suspend fun all(): ImmutableList<BubbleApp> =
        if (cacheNeedsRefresh()) loadAll() else cache

    /** Synchronous lookup for a single app; resolves from LauncherApps/PackageManager if cache is cold. */
    fun findApp(packageName: String, userId: Int = 0): BubbleApp? {
        cache.firstOrNull { it.packageName == packageName && it.userId == userId }?.let { return it }
        return runCatching {
            val userHandle = MultiUserHelper.getUserHandle(userId)
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val label = if (userHandle != null && launcherApps != null) {
                runCatching {
                    launcherApps.getActivityList(packageName, userHandle).firstOrNull()?.label?.toString()
                }.getOrNull()
            } else null

            val finalLabel = label ?: runCatching {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(ai).toString()
            }.getOrNull() ?: packageName

            BubbleApp(
                packageName = packageName,
                label = finalLabel,
                userId = userId,
                usageCount = LaunchCountStore.get(context, packageName, userId),
            )
        }.getOrNull()
    }

    suspend fun loadAll(): ImmutableList<BubbleApp> = withContext(Dispatchers.IO) {
        val allApps = mutableListOf<BubbleApp>()
        val own = context.packageName
        val seenKeys = mutableSetOf<String>()
        val pm = context.packageManager

        // 1. Discover all active user IDs on device (0, 999, etc.)
        val userIds = MultiUserHelper.getAllUserIds(context)
        Log.i(TAG, "Starting loadAll. Discovered userIds: $userIds")

        for (userId in userIds) {
            val activities = MultiUserHelper.queryLauncherActivitiesForUser(context, userId)
            for (ri in activities) {
                val actInfo = ri.activityInfo ?: continue
                val pkg = actInfo.packageName ?: continue
                if (pkg == own) continue
                val key = "$pkg#$userId"
                if (!seenKeys.add(key)) continue

                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()
                    ?: runCatching { actInfo.loadLabel(pm).toString() }.getOrNull()
                    ?: runCatching { pm.getApplicationLabel(actInfo.applicationInfo).toString() }.getOrNull()
                    ?: pkg

                allApps.add(
                    BubbleApp(
                        packageName = pkg,
                        label = label,
                        userId = userId,
                        usageCount = LaunchCountStore.get(context, pkg, userId),
                    )
                )
            }
        }

        // 2. HyperOS / MIUI SecurityCenter style fallback:
        // If User 999 is present but queryLauncherActivitiesForUser returned nothing,
        // iterate User 0 apps and check isPackageInstalledForUser(pkg, 999).
        if (userIds.contains(999) && allApps.none { it.userId == 999 }) {
            Log.i(TAG, "User 999 query activities yielded 0 items, running fallback package probe...")
            val user0Apps = allApps.filter { it.userId == 0 }
            for (app0 in user0Apps) {
                if (MultiUserHelper.isPackageInstalledForUser(context, app0.packageName, 999)) {
                    val key = "${app0.packageName}#999"
                    if (seenKeys.add(key)) {
                        Log.i(TAG, "Discovered dual app via package probe: ${app0.packageName}")
                        allApps.add(
                            BubbleApp(
                                packageName = app0.packageName,
                                label = app0.label,
                                userId = 999,
                                usageCount = LaunchCountStore.get(context, app0.packageName, 999),
                            )
                        )
                    }
                }
            }
        }

        // 3. Fallback for User 0 if completely empty (e.g. strict binder restriction)
        if (allApps.isEmpty()) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = runCatching { pm.queryIntentActivities(main, 0) }.getOrNull().orEmpty()
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == own) continue
                val key = "$pkg#0"
                if (!seenKeys.add(key)) continue
                val label = ri.loadLabel(pm).toString()
                allApps.add(
                    BubbleApp(
                        packageName = pkg,
                        label = label,
                        userId = 0,
                        usageCount = LaunchCountStore.get(context, pkg, 0),
                    )
                )
            }
        }

        val sorted = allApps.sortedWith(AppSortKey.DEFAULT).toImmutableList()
        cache = sorted
        // Remember HOW this list was obtained: a query made while the user is still locked
        // answers with a tiny subset (see the class doc), and that answer must not outlive
        // the unlock. [cacheNeedsRefresh] is what every reader checks.
        cacheEnumeratedWhileLocked = !userUnlocked(context)
        val dualCount = sorted.count { it.userId == 999 }
        Log.i(
            TAG,
            "loadAll finished. Total apps=${sorted.size}, User 999 dual apps=$dualCount " +
                "(${sorted.filter { it.userId == 999 }.map { it.packageName }}) " +
                "userUnlocked=${!cacheEnumeratedWhileLocked}",
        )

        // Pre-warm top 50 icons in background to eliminate scroll hitching
        CoroutineScope(Dispatchers.IO).launch {
            val badge = cloneBadge()
            val previewPx = (45 * context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
            for (app in sorted.take(50)) {
                AppIconCache.loadOrGet(context, app, previewPx, badge)
            }
        }

        sorted
    }

    fun icon(
        app: BubbleApp,
        cloneBadge: Boolean = RemotePrefs.DEFAULT_CLONE_BADGE,
    ): Drawable? = getIcon(context, app, cloneBadge)

    fun launchIntent(pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)

    companion object {
        private const val TAG = "MeiWindow_AppRepo"
        private val iconCache = LruCache<String, Drawable>(128)

        /**
         * Is the current user's credential-encrypted storage available?
         *
         * `UserManager.isUserUnlocked()` is the same signal the platform uses to decide how
         * much of the package list an app-uid caller may see, so it is exactly the question
         * [AppRepository.cacheNeedsRefresh] has to ask. Unknown answer → assume unlocked, so
         * a missing service can never wedge the refresh path into reloading forever.
         */
        fun userUnlocked(context: Context): Boolean = runCatching {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.isUserUnlocked != false
        }.getOrDefault(true)

        /**
         * The icon of [app] as the launcher draws it, with the 分身 badge only when
         * [cloneBadge] is on.
         *
         * 分身 (MIUI 应用双开 = user 999, plus any other secondary profile) must not look like a
         * different app: the ARTWORK always comes from the main space ([mainSpaceIcon]) and only
         * the corner mark is added on top. Until 1.1.9 the clone's icon came from the clone
         * user's own resource chain (`LauncherActivityInfo.getBadgedIcon` first, MIUI's
         * `XSpaceUserHandle.getXSpaceIcon` never reached), and what MIUI pre-bakes there is its
         * own idea of a dual-app icon — different artwork at a different size, i.e. the same app
         * looking like two apps inside one fan.
         */
        fun getIcon(
            context: Context,
            app: BubbleApp,
            cloneBadge: Boolean = RemotePrefs.DEFAULT_CLONE_BADGE,
        ): Drawable? {
            // The switch is part of the key: the same app#user renders two different images
            // depending on it, and both hosts (app process, SystemUI) cache per process.
            val key = "${app.packageName}#${app.userId}#badge=$cloneBadge"
            iconCache.get(key)?.let { return it }

            val d = loadIcon(context, app, cloneBadge) ?: return null
            iconCache.put(key, d)
            return d
        }

        private fun loadIcon(context: Context, app: BubbleApp, cloneBadge: Boolean): Drawable? {
            val base = mainSpaceIcon(context, app.packageName) ?: return null
            if (app.userId == 0 || !cloneBadge) {
                // Only the clone path is worth a line (it is the one that used to come from the
                // clone user's own resources); a plain user-0 icon is loaded once per process.
                if (app.userId != 0) {
                    Log.i(TAG, "CLONE_ICON pkg=${app.packageName} user=${app.userId} badge=false src=main-space")
                }
                return base
            }
            val badged = badgedIcon(context, app, base)
            Log.i(
                TAG,
                "CLONE_ICON pkg=${app.packageName} user=${app.userId} badge=true " +
                    "src=${if (badged === base) "main-space" else "main-space+badge"}",
            )
            return badged
        }

        /**
         * The artwork the MAIN space (user 0) shows for [pkg] — the reference every user gets.
         *
         * `PackageManager.getApplicationIcon` comes FIRST on purpose: it is exactly what this
         * list already draws for user 0 (see the `userId == 0` branch below), so a clone is
         * pixel-identical to the entry it sits next to instead of merely "also an icon of that
         * app". `LauncherApps` (the launcher activity's own drawable) is the fallback for the
         * cases `getApplicationIcon` cannot answer, and is deliberately NOT preferred: on
         * HyperOS it can hand back a themed/adaptive variant, which would make main and clone
         * differ again — the very bug this method exists to end.
         */
        fun mainSpaceIcon(context: Context, pkg: String): Drawable? {
            runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { return it }

            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val handle = MultiUserHelper.getUserHandle(0)
            if (launcherApps != null && handle != null) {
                return runCatching {
                    launcherApps.getActivityList(pkg, handle).firstOrNull()
                        ?.getIcon(context.resources.displayMetrics.densityDpi)
                }.getOrNull()
            }
            return null
        }

        /** The corner mark for a secondary user, drawn OVER the main-space artwork. */
        private fun badgedIcon(context: Context, app: BubbleApp, base: Drawable): Drawable {
            val userHandle = MultiUserHelper.getUserHandle(app.userId)
            if (app.userId == 999) {
                // HyperOS / MIUI XSpace: its API takes OUR drawable and only decorates it, which
                // is exactly what keeps the artwork identical.
                val xspaceIcon = runCatching {
                    val cls = Class.forName("miui.securityspace.XSpaceUserHandle")
                    val m = cls.getMethod("getXSpaceIcon", Context::class.java, Drawable::class.java)
                    m.invoke(null, context, base) as? Drawable
                }.getOrNull()
                if (xspaceIcon != null) return xspaceIcon
                return drawXSpaceBadge(context, base)
            }

            // Other profiles (work profile, …): the system badge is an overlay on the SAME
            // artwork by construction, so it is preferred when the ROM actually adds one.
            if (userHandle != null) {
                val badged = runCatching { context.packageManager.getUserBadgedIcon(base, userHandle) }.getOrNull()
                if (badged != null && badged !== base) return badged
            }
            return drawXSpaceBadge(context, base)
        }

        /**
         * Draws an authentic HyperOS / MIUI style dual-app badge onto [baseIcon] as a fallback.
         */
        private fun drawXSpaceBadge(context: Context, baseIcon: Drawable): Drawable {
            val width = baseIcon.intrinsicWidth.coerceAtLeast(144)
            val height = baseIcon.intrinsicHeight.coerceAtLeast(144)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            baseIcon.setBounds(0, 0, width, height)
            baseIcon.draw(canvas)

            // Badge radius: ~17% of icon size
            val badgeRadius = width * 0.17f
            val cx = width - badgeRadius - (width * 0.03f)
            val cy = height - badgeRadius - (height * 0.03f)

            // Orange badge circle background (#FF7600)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF7600")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, badgeRadius, bgPaint)

            // Two overlapping white rings/loops (XSpace dual-app logo)
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = badgeRadius * 0.22f
            }
            val ringR = badgeRadius * 0.44f
            val offset = badgeRadius * 0.22f
            canvas.drawCircle(cx - offset, cy - offset * 0.5f, ringR, ringPaint)
            canvas.drawCircle(cx + offset, cy + offset * 0.5f, ringR, ringPaint)

            return BitmapDrawable(context.resources, bitmap)
        }
    }
}

/** Lightweight local usage counter supporting multi-user keys. */
object LaunchCountStore {
    private const val PREFS = "usage"
    private const val KEY_PREFIX = "c_"

    fun get(context: Context, pkg: String, userId: Int = 0): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (userId == 0) pkg else "$pkg#$userId"
        val count = p.getInt(KEY_PREFIX + key, -1)
        if (count != -1) return count
        return p.getInt(KEY_PREFIX + pkg, 0)
    }

    fun increment(context: Context, pkg: String, userId: Int = 0) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (userId == 0) pkg else "$pkg#$userId"
        p.edit().putInt(KEY_PREFIX + key, get(context, pkg, userId) + 1).apply()
    }
}

/** Global memory cache for ready-to-render Compose ImageBitmaps. */
object AppIconCache {
    private val memoryCache = LruCache<String, ImageBitmap>(300)

    /** [cloneBadge] is part of the key: the same app#user renders two different images. */
    private fun key(packageName: String, userId: Int, px: Int, cloneBadge: Boolean) =
        "$packageName#$userId@$px#badge=$cloneBadge"

    fun get(packageName: String, userId: Int, px: Int, cloneBadge: Boolean): ImageBitmap? =
        memoryCache.get(key(packageName, userId, px, cloneBadge))

    fun put(packageName: String, userId: Int, px: Int, cloneBadge: Boolean, bitmap: ImageBitmap) {
        memoryCache.put(key(packageName, userId, px, cloneBadge), bitmap)
    }

    suspend fun loadOrGet(
        context: Context,
        app: BubbleApp,
        px: Int,
        cloneBadge: Boolean = RemotePrefs.DEFAULT_CLONE_BADGE,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        get(app.packageName, app.userId, px, cloneBadge)?.let { return@withContext it }

        val drawable = AppRepository.getIcon(context, app, cloneBadge)
            ?: AppRepository.mainSpaceIcon(context, app.packageName)
            ?: return@withContext null

        val imageBitmap = runCatching {
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()

        if (imageBitmap != null) {
            put(app.packageName, app.userId, px, cloneBadge, imageBitmap)
        }
        imageBitmap
    }
}
