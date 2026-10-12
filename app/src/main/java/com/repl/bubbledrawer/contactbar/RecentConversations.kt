package com.repl.bubbledrawer.contactbar

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy

/** 头像/图标自有副本的落地尺寸：小窗里是 36dp，520dpi 下 ≈117px，留足余量。 */
const val AVATAR_PX = 144

/** One recent conversation, rebuilt from a posted notification (Flyme's `C0653b` analogue). */
data class Conversation(
    val pkg: String,
    val key: String,
    val title: String,
    val icon: Drawable?,
    val pendingIntent: PendingIntent?,
    val postTime: Long,
    /** Notification still posted → the bar shows the unread dot (Flyme's `NewMessageView`). */
    val live: Boolean = false,
    /**
     * `PendingIntent.getIntent()` — the launch request behind the tap, kept the way Flyme keeps it
     * (`C0653b.m3435c()` is exactly this, and `m9364J` sends it as the fill-in Intent).
     *
     * This is what makes the tap reliable on HyperOS: opening an app cancels its notifications, and
     * WeChat cancels their PendingIntents with them, after which `PendingIntent.send` throws
     * `CanceledException` — while the Intent itself still starts fine.
     */
    val launchIntent: Intent? = null,
    val userId: Int = 0,
    /**
     * 头像指纹（[avatarPrint]）—— 用来认「这是同一个人」。
     *
     * 会话的标题认不出人：群消息的标题是 `群名：发送者`（Telegram 一类），而头像就是**发送者的脸** ——
     * 同一个人在几个群里说话，条上就会出现几张一模一样的头像（用户反馈）。联系人条是「联系人」，
     * 所以身份认头像：指纹相同 = 同一个人，只留最新的那条。
     */
    val avatarPrint: String? = null,
)

/**
 * The contact bar's data source — recent conversations of the app that currently owns the
 * small window.
 *
 * Flyme builds this from a NotificationListenerService
 * (`com.flyme.systemuitools.SystemUIToolsNotificationListenerService`, registered at app start
 * with `registerAsSystemService`), buckets notifications per `包名:UserHandle{n}`, requires a
 * large icon plus a non-empty title, drops QQ's 「下线通知」 and caps the list at 20
 * (`windowmode/C2763k.java:405`, `views/C2831I.java:530-554`). We are already inside the
 * SystemUI process, so none of that plumbing is needed: `NotifPipeline` owns every posted
 * notification and hands it to us directly.
 *
 * Verified against the device dump (`out/SystemUI1703_src`):
 *  - `NotifPipeline implements CommonNotifCollection`, constructor
 *    `NotifPipeline(NotifCollection, ShadeListBuilder, RenderStageManager, NotifPipelineInjectorImpl)`
 *    — a single instance for the process, so hooking the constructor gives a permanent handle.
 *  - `NotifPipeline.addCollectionListener(NotifCollectionListener)` (:34) → live updates.
 *  - `NotifPipeline.getAllNotifs()` (:128) returns `mNotifCollection.mReadOnlyNotificationSet`
 *    but asserts the main thread, so the field is read directly and the method kept as fallback.
 *  - `NotificationEntry` exposes the key and the `ExpandedNotification` (fields `key`, `mSbn`).
 *
 * ## Why the list is REMEMBERED, not just read live (HyperOS-specific)
 *
 * MIUI/HyperOS clears an app's notifications as soon as that app is opened — by any route, our
 * freeform launch included (the logic lives in system_server; `out/SystemUI1703_src` contains no
 * auto-clear implementation at all). A bar fed purely by the live notification set would therefore
 * be empty exactly when the small window appears, which is the whole point of the feature. Flyme has
 * no such behaviour, which is why its original can read the notification list directly
 * (`C2763k.java:405`).
 *
 * So notifications are used as a **discovery channel**: every chat notification upserts a
 * [Remembered] record (title, avatar, click intent, last post time) that outlives the notification
 * itself. The bar shows the merge — live first, remembered after — and [TTL_MS] plus the per-app cap
 * keep a stale chat from living forever. Consequence worth knowing: swiping a notification away no
 * longer removes an avatar (Flyme's rule); only time and the caps do.
 */
class RecentConversations(
    private val context: Context,
    private val log: (Int, String, Throwable?) -> Unit,
) {

    /** One rebuild is at most this old; notification callbacks normally refresh immediately. */
    private val refreshIntervalMs = 1_500L

    private var cached: List<Conversation> = emptyList()
    private var cachedPkg: String? = null
    private var cachedAt = 0L
    private var cachedIcons = HashMap<String, Drawable?>()

    /**
     * 通知 key → 头像指纹（[avatarPrint]），与 [cachedIcons] 同生命周期。
     *
     * 头像本身没法当身份用（每次通知都是新的 Bitmap 对象），指纹可以：同一个人在不同群里说话时，
     * 通知的标题不同（`群名：发送者`）但头像字节几乎相同 —— 这就是「几个一样的联系人图标」。
     */
    private var cachedPrints = HashMap<String, String?>()

    /** Conversation memory that survives MIUI's "opening the app clears its notifications". */
    private class Remembered(
        val pkg: String,
        val title: String,
        var key: String?,
        var icon: Drawable?,
        var pendingIntent: PendingIntent?,
        var launchIntent: Intent?,
        var userId: Int,
        var postTime: Long,
        var lastSeenAt: Long,
        var avatarPrint: String? = null,
    )

    private val remembered = HashMap<String, Remembered>()

    /**
     * Conversations the user swiped away, with the post time they were removed at. A newer
     * notification (greater `postTime` — the framework refreshes it on every post *and* update) brings
     * the chat back, which is what "remove from the bar" should mean; [FORGET_TTL_MS] is the backstop.
     */
    private class Forgotten(val postTime: Long, val at: Long)

    private val forgotten = HashMap<String, Forgotten>()

    /** Live notifications seen in the last build — diagnostics only. */
    @Volatile
    var lastLiveCount = 0
        private set

    /**
     * Conversations of [pkg], newest first. Empty when the app never posted anything that looks like
     * a chat (no title / no avatar) — the bar then stays hidden, exactly like Flyme's
     * `m9397W(false)` path.
     */
    fun conversations(pkg: String, force: Boolean = false): List<Conversation> {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && pkg == cachedPkg && now - cachedAt < refreshIntervalMs) return cached
        val built = runCatching { build(pkg) }.getOrElse {
            log(Log.WARN, "CONTACT_BAR_BUILD_FAILED", it)
            emptyList()
        }
        cached = built
        cachedPkg = pkg
        cachedAt = now
        return built
    }

    private fun build(pkg: String): List<Conversation> {
        val now = android.os.SystemClock.elapsedRealtime()
        val live = LinkedHashMap<String, Conversation>()
        for (entry in allEntries()) {
            val conv = toConversation(entry) ?: continue
            // Remember every whitelisted app, not just the one in the small window: by the time a
            // window opens, HyperOS has usually cleared that app's notifications already.
            remember(conv, now)
            if (conv.pkg != pkg || isForgotten(conv)) continue
            // One avatar per conversation: a chat that re-posts (or a summary line) keeps the
            // newest notification only — Flyme keeps a per-key bucket with the same effect.
            // (同一个人的多个会话在 [dedupeByPerson] 里再合成一条。）
            val previous = live[conv.title]
            if (previous == null || conv.postTime >= previous.postTime) live[conv.title] = conv.copy(live = true)
        }
        lastLiveCount = live.size
        prune(now)
        val out = merge(pkg, live)
        logList(pkg, out)
        return out
    }

    /**
     * 条上现在有什么（标题 + 有没有头像指纹 + 是不是还在通知栏里），**只在变化时**打一行。
     *
     * 这是「几个一样的联系人图标」唯一能事后核对的东西：合并发生在数据层，条上是看不见证据的，
     * 而 `#p` 标记说明这条有指纹、能参与认人。
     */
    private fun logList(pkg: String, list: List<Conversation>) {
        val line = "CONTACT_BAR_LIST pkg=$pkg n=${list.size} live=$lastLiveCount " +
            list.take(8).joinToString(" | ") {
                it.title + (if (it.avatarPrint != null) "#p" else "") + (if (it.live) "*" else "")
            }
        if (line == lastListLine) return
        lastListLine = line
        log(Log.INFO, line, null)
    }

    @Volatile
    private var lastListLine: String? = null

    /**
     * Drop one conversation from the bar (Flyme's swipe-to-remove, `ContactListView.C2929d.onSwiped` →
     * `C2831I.m9363I` → the store's remove). The notification itself is left alone; the chat returns
     * once something newer arrives for it.
     */
    fun forget(conversation: Conversation) {
        val now = android.os.SystemClock.elapsedRealtime()
        // 条上那一条可能代表同一个人的好几个会话（见 [dedupeByPerson]）—— 划掉就要划掉整个人，
        // 否则下一次重建会把同一张头像从另一个群的消息里再摆回来。
        val person = conversation.avatarPrint
        val samePerson = remembered.values.filter { record ->
            if (person != null) printsMatch(record.avatarPrint, person) else record.title == conversation.title
        }
        val targets = samePerson.ifEmpty { null }
            ?.map { it.title to it.postTime }
            ?: listOf(conversation.title to conversation.postTime)
        targets.forEach { (title, postTime) ->
            val entry = key(conversation.pkg, title)
            remembered.remove(entry)
            forgotten[entry] = Forgotten(postTime, now)
        }
        cachedPkg = null
        log(
            Log.INFO,
            "CONTACT_BAR_FORGET pkg=${conversation.pkg} title=${conversation.title} " +
                "entries=${targets.size}",
            null,
        )
    }

    private fun isForgotten(conv: Conversation): Boolean {
        val entry = forgotten[key(conv.pkg, conv.title)] ?: return false
        // A newer notification means the chat has something to say again.
        if (conv.postTime > entry.postTime) {
            forgotten.remove(key(conv.pkg, conv.title))
            return false
        }
        return true
    }

    /**
     * Snapshot every whitelisted conversation into the memory. Called on every notification event
     * and once at startup, so the bar has something to show even when the small window is opened
     * long after the notifications were posted — and after HyperOS cancelled them.
     */
    fun observe() {
        runCatching {
            val now = android.os.SystemClock.elapsedRealtime()
            for (entry in allEntries()) {
                toConversation(entry)?.let { remember(it, now) }
            }
            prune(now)
        }.onFailure { log(Log.WARN, "CONTACT_BAR_OBSERVE_FAILED", it) }
    }

    private fun remember(conv: Conversation, now: Long) {
        if (isForgotten(conv)) return
        val record = remembered.getOrPut(key(conv.pkg, conv.title)) {
            Remembered(conv.pkg, conv.title, null, null, null, null, conv.userId, 0L, now)
        }
        if (conv.icon != null) record.icon = conv.icon
        if (conv.pendingIntent != null) record.pendingIntent = conv.pendingIntent
        if (conv.launchIntent != null) record.launchIntent = conv.launchIntent
        record.userId = conv.userId
        record.key = conv.key
        record.avatarPrint = conv.avatarPrint ?: record.avatarPrint
        record.postTime = maxOf(record.postTime, conv.postTime)
        record.lastSeenAt = now
    }

    /** Memory for [pkg], overlaid with whatever is still posted (live wins: newer avatar/intent). */
    private fun merge(pkg: String, live: Map<String, Conversation>): List<Conversation> {
        val merged = LinkedHashMap<String, Conversation>()
        for (record in remembered.values) {
            if (record.pkg != pkg) continue
            merged[record.title] = Conversation(
                pkg = record.pkg,
                key = record.key.orEmpty(),
                title = record.title,
                icon = record.icon,
                pendingIntent = record.pendingIntent,
                postTime = record.postTime,
                launchIntent = record.launchIntent,
                userId = record.userId,
                avatarPrint = record.avatarPrint,
            )
        }
        merged.putAll(live)
        // 一个联系人只留一条（同一个人可能在好几个群里都说了话），再按时间排、再截断 ——
        // 顺序很要紧：先截断会把同一个人的几条都算进 20 条里，真实人数就被挤掉了。
        val out = dedupeByPerson(merged.values.toList())
        if (out.size != merged.size) logMerged(pkg, merged.size, out)
        return if (out.size > MAX_ITEMS) ArrayList(out.subList(0, MAX_ITEMS)) else out
    }

    /** One line when a person's several conversations collapsed — the duplicate-avatar report. */
    private fun logMerged(pkg: String, before: Int, people: List<Conversation>) {
        val line = "CONTACT_BAR_MERGED pkg=$pkg conversations=$before people=${people.size} " +
            "keep=${people.take(6).joinToString { it.title }}"
        if (line == lastMergedLine) return
        lastMergedLine = line
        log(Log.INFO, line, null)
    }

    @Volatile
    private var lastMergedLine: String? = null

    /** Drop conversations nobody refreshed for [TTL_MS], then enforce the per-app and global caps. */
    private fun prune(now: Long) {
        remembered.values.removeAll { now - it.lastSeenAt > TTL_MS }
        forgotten.entries.removeAll { now - it.value.at > FORGET_TTL_MS }
        // 头像副本是 144x144 ARGB（≈83KB/条）而 [cachedIcons] 以前只增不减：记忆有 TTL 和条数上限，
        // 这个缓存没有，一场长会话下来能攒到几十 MB。被遗忘/过期的 key 在这里一起清掉。
        if (cachedIcons.size > MAX_CACHED_ICONS) {
            val live = remembered.values.mapNotNullTo(HashSet()) { it.key }
            cachedIcons.keys.retainAll(live)
            cachedPrints.keys.retainAll(live)
        }
        remembered.values.groupBy { it.pkg }.forEach { (_, list) ->
            if (list.size > MAX_REMEMBERED_PER_APP) {
                list.sortedByDescending { it.lastSeenAt }
                    .drop(MAX_REMEMBERED_PER_APP)
                    .forEach { remembered.remove(key(it.pkg, it.title)) }
            }
        }
        if (remembered.size <= MAX_REMEMBERED_TOTAL) return
        remembered.values
            .sortedByDescending { it.lastSeenAt }
            .drop(MAX_REMEMBERED_TOTAL)
            .forEach { remembered.remove(key(it.pkg, it.title)) }
    }

    private fun key(pkg: String, title: String) = "$pkg|$title"

    private fun toConversation(entry: Any): Conversation? {
        val sbn = member(entry, "getSbn", "mSbn") as? StatusBarNotification ?: return null
        val pkg = (runCatching { sbn.javaClass.getMethod("getOrigPackageName").invoke(sbn) as? String }.getOrNull())
            ?: sbn.packageName
        if (pkg !in IM_WHITELIST) return null
        val key = (member(entry, "getKey", "key") as? String) ?: sbn.key ?: return null
        val n: Notification = runCatching { sbn.notification }.getOrNull() ?: return null
        // Group summaries ("微信 · 3 条新消息") carry the app name, not a person — they would add a
        // bogus avatar and are exactly what the title gate below is meant to reject.
        if (runCatching { n.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.getOrDefault(false)) return null
        val title = runCatching {
            n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        }.getOrNull().orEmpty()
        // Same gate as Flyme's `m8903m()`: a chat notification always carries the sender as the
        // title and the sender's avatar as the large icon. System chatter («下线通知» …) fails it.
        if (title.isEmpty() || title in IGNORED_TITLES) return null
        val pi = n.contentIntent ?: return null

        if (!cachedIcons.containsKey(key)) {
            val raw = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
                ?: runCatching {
                    val extra = n.extras?.get(Notification.EXTRA_LARGE_ICON)
                    (extra as? android.graphics.drawable.Icon)?.loadDrawable(context)
                }.getOrNull()
            cachedIcons[key] = ownAvatar(raw)
            // 指纹只从**通知真的带了头像**时取：没有头像的条目画的是应用图标，认它会把所有人并成一个。
            cachedPrints[key] = cachedIcons[key]?.let { printOf(it) }
            // 带了头像却复制不出来 = 那张图交到我们手上时已经坏了（ROM 回收了它）。条上会退回应用图标，
            // 但要留一行证据 —— 否则"这个人怎么没头像"永远查不出原因。
            if (raw != null && cachedIcons[key] == null) logIconCopyFailed(pkg, key)
        }
        return Conversation(
            pkg = pkg,
            key = key,
            title = title,
            icon = cachedIcons[key],
            pendingIntent = pi,
            postTime = runCatching { sbn.postTime }.getOrNull() ?: 0L,
            // Flyme's `C0653b.m3435c()`: the Intent behind the click, so a later tap still works when
            // the PendingIntent itself has been cancelled along with the notification.
            // `PendingIntent.getIntent()` is hidden in the current SDK, hence reflection.
            launchIntent = runCatching {
                PendingIntent::class.java.getMethod("getIntent").invoke(pi) as? Intent
            }.getOrNull(),
            userId = notificationUserId(sbn),
            avatarPrint = cachedPrints[key],
        )
    }

    /**
     * The avatar's fingerprint ([avatarPrint]): the copy we already keep is redrawn into a 16x16
     * bitmap, which is small enough to compare and coarse enough that re-encodings of the same
     * picture still match.
     */
    private fun printOf(avatar: Drawable): String? = runCatching {
        val side = AVATAR_PRINT_SIDE
        val small = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        avatar.setBounds(0, 0, side, side)
        avatar.draw(canvas)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        small.recycle()
        avatarPrint(pixels, side)
    }.getOrNull()

    /** The avatar we keep — see [ownIconCopy] for why it is a copy and never the source. */
    private fun ownAvatar(source: Drawable?): Drawable? = ownIconCopy(context, source)

    /**
     * 头像复制失败的证据（见 `toConversation`）：这行出现说明**通知带的那张图在交给我们时就已经坏了**
     * —— ROM 回收了它（图标缓存/媒体栈的常规行为）。条上退回应用图标，但原因从此可查。
     */
    private fun logIconCopyFailed(pkg: String, key: String) {
        val line = "CONTACT_BAR_ICON_COPY_FAILED pkg=$pkg key=$key"
        if (line == lastIconCopyFailed) return
        lastIconCopyFailed = line
        log(Log.WARN, line, null)
    }

    @Volatile
    private var lastIconCopyFailed: String? = null

    /** The notification's user, for `startActivityAsUser` (reflection: `getUserId`/`getUser` are hidden). */
    private fun notificationUserId(sbn: StatusBarNotification): Int {        runCatching {            (sbn.javaClass.getMethod("getUserId").invoke(sbn) as? Number)?.let { return it.toInt() }
        }
        runCatching {
            val user = sbn.javaClass.getMethod("getUser").invoke(sbn) ?: return@runCatching
            val id = user.javaClass.getMethod("getIdentifier").invoke(user) as? Number
                ?: user.javaClass.getField("mHandle").get(user) as? Number
            if (id != null) return id.toInt()
        }
        return 0
    }

    /** Every posted notification: the collection's read-only set, or `getAllNotifs()` on main. */
    private fun allEntries(): List<Any> {
        val pipeline = pipelineRef ?: acquirePipeline() ?: return emptyList()
        runCatching {
            val collection = pipeline.javaClass.getField("mNotifCollection").get(pipeline) ?: return@runCatching
            val set = collection.javaClass.getField("mReadOnlyNotificationSet").get(collection) as? Collection<*>
            if (set != null) return set.filterNotNull()
        }
        if (Looper.myLooper() != Looper.getMainLooper()) return emptyList()
        return runCatching {
            (pipeline.javaClass.getMethod("getAllNotifs").invoke(pipeline) as? Collection<*>)?.filterNotNull()
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /** `getX()` method first, then the `x` / `mX` field — jadx exposes Kotlin properties either way. */
    private fun member(target: Any, getter: String, vararg fields: String): Any? {
        runCatching {
            target.javaClass.getMethod(getter).invoke(target)?.let { return it }
        }
        for (name in fields) {
            runCatching {
                target.javaClass.getField(name).get(target)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "BubbleDrawer"
        private const val MAX_ITEMS = 20

        /**
         * How long a conversation stays in the bar after its notification is gone. HyperOS cancels
         * an app's notifications when the app is opened, so this window is what makes the feature
         * usable at all: recent chats keep showing, yesterday's don't.
         */
        private const val TTL_MS = 12 * 60 * 60 * 1000L

        /** How long a swiped-away conversation stays away even if no new notification arrives. */
        private const val FORGET_TTL_MS = 60 * 60 * 1000L

        /** Memory bounds: the bar shows at most 6 avatars, so 12 per app / 60 overall is plenty. */
        private const val MAX_REMEMBERED_PER_APP = 12
        private const val MAX_REMEMBERED_TOTAL = 60

        /** 头像副本/指纹的上限（超出时只留还有记忆引用的那些），见 [prune]。 */
        private const val MAX_CACHED_ICONS = 40

        private const val PROXY_NAME = "BubbleDrawerNotificationListener"

        /** Every class this module ships lives under this prefix — how a stale proxy is recognised. */
        private const val MODULE_PACKAGE = "com.repl.bubbledrawer"

        /**
         * Flyme's own whitelist (`common/windowmode/model/AbstractC2304a.java:49-53`), plus Telegram.
         *
         * Telegram 是后加的：它发的就是标准 MessagingStyle 聊天通知，群消息的标题正好是
         * `群名：发送者`（设备日志里 `StarVoyager Ideal：ksm`、`YuKongA | Chat：Who care`），
         * 也就是「几个一样的联系人图标」那条反馈的形状；而模块作者自己日常只用它 ——
         * 不在白名单里，这个功能在本机上根本无从验证。
         */
        val IM_WHITELIST = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.alibaba.android.rimet",
            "com.tencent.wework",
            "org.telegram.messenger",
        )

        /** Flyme drops QQ's 「下线通知」 explicitly (`C2831I.java:533-539`). */
        private val IGNORED_TITLES = setOf("下线通知", "微信", "QQ", "微信支付", "服务通知")

        @Volatile
        private var pipelineRef: Any? = null

        @Volatile
        private var listenerAttached = false

        /** The proxy we registered, so [detachListener] can take it back out on hot reload. */
        @Volatile
        private var attachedProxy: Any? = null

        @Volatile
        private var listenerClassRef: Class<*>? = null

        @Volatile
        private var moduleRef: XposedModule? = null

        /** Fired on the main thread whenever a notification appears, updates or goes away. */
        @Volatile
        var onChanged: (() -> Unit)? = null

        /**
         * Hook the one `NotifPipeline` instance of this process. Called before the bar exists, so
         * the captured reference is kept statically and read on demand.
         */
        fun installHooks(module: XposedModule, classLoader: ClassLoader) {
            moduleRef = module
            val pipelineClass = runCatching {
                classLoader.loadClass("com.android.systemui.statusbar.notification.collection.NotifPipeline")
            }.getOrNull() ?: run {
                module.log(Log.WARN, TAG, "CONTACT_BAR_NOTIF_PIPELINE_MISSING", null)
                return
            }
            listenerClassRef = runCatching {
                classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener",
                )
            }.getOrNull()

            pipelineClass.declaredConstructors.forEach { ctor ->
                runCatching {
                    module.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.contactbar.pipeline.${ctor.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val instance = chain.thisObject
                            if (instance != null) {
                                pipelineRef = instance
                                attachListener(instance)
                            }
                            result
                        }
                }
            }
            // A hot reload happens long after SystemUI built its object graph, so the constructor
            // hook above never fires — pull the same singleton out of the Dagger component instead
            // (`SystemUIAppComponentFactoryBase.systemUIInitializer` → `getSysUIComponent()` →
            // `ReferenceSysUIComponentImpl.notifPipelineProvider`, verified in the device dump).
            //
            // **主线程是硬要求**：`addCollectionListener` 在热重载线程上抛 `InvocationTargetException`
            // （2026-10-11 设备实测：LSPosed 的 25978 线程装不上，而历史上每一次成功安装都发生在主线程
            // ——`install()` 就是主线程调的）。所以构造 hook 在这里装，碰 pipeline 的部分发到主线程。
            val finish = Runnable {
                if (pipelineRef == null) acquirePipeline(classLoader)
                module.log(Log.INFO, TAG, "CONTACT_BAR_HOOKS_INSTALLED pipeline=${pipelineRef != null}", null)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                finish.run()
            } else {
                Handler(Looper.getMainLooper()).post(finish)
            }
        }

        /**
         * Late binding of the process-wide `NotifPipeline`: the Dagger component exposes it as a
         * public provider field. Silent no-op on any other ROM.
         */
        private fun acquirePipeline(classLoader: ClassLoader? = null): Any? {
            pipelineRef?.let { return it }
            val loader = classLoader ?: listenerClassRef?.classLoader ?: return null
            val instance = runCatching {
                val factory = Class.forName("com.android.systemui.SystemUIAppComponentFactoryBase", false, loader)
                val initializer = factory.getField("systemUIInitializer").get(null) ?: return@runCatching null
                val component = initializer.javaClass.getMethod("getSysUIComponent").invoke(initializer)
                    ?: return@runCatching null
                component.javaClass.getField("notifPipelineProvider").get(component)?.let { provider ->
                    provider.javaClass.getMethod("get").invoke(provider)
                }
            }.getOrNull() ?: return null
            if (!instance.javaClass.name.endsWith("NotifPipeline")) return null
            pipelineRef = instance
            attachListener(instance)
            return instance
        }

        /** `addCollectionListener` keeps a plain listener set — no main-thread assertion. */
        private fun attachListener(instance: Any) {
            val listenerClass = listenerClassRef ?: return
            val module = moduleRef ?: return
            if (listenerAttached) return
            runCatching {
                val add = instance.javaClass.methods.firstOrNull {
                    it.name == "addCollectionListener" && it.parameterCount == 1
                } ?: return
                // A java.lang.reflect.Proxy must answer equals/hashCode itself: the default handler
                // returning null makes `Intrinsics.areEqual` unbox null into a boolean and crash the
                // SystemUI process (`NamedListenerSet.remove` compares every registered listener,
                // which runs on unrelated paths such as ModalControllerImpl.exitModal when the
                // keyguard is shown). Identity semantics are the correct answer here anyway.
                val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { instance, method, args ->
                    when (method.name) {
                        "equals" -> instance === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(instance)
                        "toString" -> PROXY_NAME
                        else -> {
                            if (method.name.startsWith("onEntry")) {
                                // **只对白名单里的聊天应用做全量重建。** 通知事件是全系统的：音乐
                                // 播放器的进度通知一秒一条、下载/系统提示同样不少，而它们跟联系人条
                                // 一点关系都没有 —— 以前每一条都会走一遍"遍历所有通知 + 重建列表 +
                                // 写一行模块日志"（主线程文件 I/O），一首歌就是几百次。
                                if (isBarRelevant(args)) onChanged?.invoke()
                                // Diagnostics for the HyperOS behaviour this class exists to survive:
                                // when the app is opened its notifications are cancelled, so an
                                // `onEntryRemoved` burst right before the small window appears is
                                // expected — the memory keeps the bar populated (see the class KDoc).
                                if (method.name == "onEntryRemoved") logRemoval(args?.firstOrNull())
                            }
                            // Never null: the listener interface is all-void today, but a future/other
                            // method with a primitive return would blow up on unboxing (that exact bug
                            // already cost us one SystemUI crash on the input-receiver proxy).
                            com.repl.bubbledrawer.launch.FlymeFreeformController.proxyDefault(method.returnType)
                        }
                    }
                }
                // Drop proxies left behind by an earlier generation of this module BEFORE adding the
                // new one. A hot reload replaces our classes but not the objects the previous
                // generation already registered, and a stale proxy from a buggy build keeps crashing
                // every `NamedListenerSet.remove` until the process restarts. Removal goes through the
                // raw `listeners` list on purpose: the set's own `remove` compares with `equals`,
                // which is exactly what must not be called on the object being cleaned up.
                purgeModuleProxies(instance, module)
                add.invoke(instance, proxy)
                attachedProxy = proxy
                listenerAttached = true
                module.log(Log.INFO, TAG, "CONTACT_BAR_NOTIF_LISTENER_ATTACHED")
            }.onFailure { module.log(Log.WARN, TAG, "CONTACT_BAR_NOTIF_LISTENER_FAILED", it.cause ?: it) }
        }

        /**
         * 这条通知事件跟联系人条有没有关系 —— 只认白名单里的聊天应用。
         *
         * 判断必须便宜：`onEntryAdded/Updated` 给的是 `NotificationEntry`（读它自己的 `mSbn.packageName`，
         * 一次字段反射），`onEntryRemoved` 只给通知 key（`userId|pkg|id|tag|uid`，按 `|` 切开找白名单包名）。
         * 拿不准一律按"无关"处理：宁可少一次刷新，也不要为一条音乐通知重建整个列表。
         */
        private fun isBarRelevant(args: Array<Any?>?): Boolean {
            val first = args?.firstOrNull() ?: return false
            if (first is String) return imPackageOfKey(first) != null
            val pkg = runCatching {
                (first.javaClass.getField("mSbn").get(first) as? StatusBarNotification)?.packageName
            }.getOrNull()
            return pkg != null && pkg in IM_WHITELIST
        }

        /** The `NamedListenerSet` behind `NotifPipeline.mNotifCollection` — see the dump notes. */        private fun listenerList(pipeline: Any): java.util.Collection<Any?>? = runCatching {
            val collection = pipeline.javaClass.getField("mNotifCollection").get(pipeline) ?: return null
            val set = collection.javaClass.getField("mNotifCollectionListeners").get(collection) ?: return null
            @Suppress("UNCHECKED_CAST")
            set.javaClass.getField("listeners").get(set) as? java.util.Collection<Any?>
        }.getOrNull()

        /** `true` for a `java.lang.reflect.Proxy` this module created (any generation). */
        private fun isModuleProxy(candidate: Any?): Boolean {
            if (candidate == null || !Proxy.isProxyClass(candidate.javaClass)) return false
            return runCatching {
                Proxy.getInvocationHandler(candidate).javaClass.name.startsWith(MODULE_PACKAGE)
            }.getOrDefault(false)
        }

        private fun purgeModuleProxies(pipeline: Any, module: XposedModule) {
            runCatching {
                val listeners = listenerList(pipeline) ?: return
                var removed = 0
                listeners.removeIf { wrapper ->
                    val inner = runCatching {
                        wrapper?.javaClass?.getField("listener")?.get(wrapper)
                    }.getOrNull()
                    val stale = isModuleProxy(inner)
                    if (stale) removed++
                    stale
                }
                if (removed > 0) {
                    module.log(Log.INFO, TAG, "CONTACT_BAR_STALE_PROXY_PURGED removed=$removed", null)
                }
            }.onFailure { module.log(Log.WARN, TAG, "CONTACT_BAR_PURGE_FAILED", it) }
        }

        /**
         * Unregister our listener. Called from `ContactBarController.dispose()` so a hot reload does
         * not leave a proxy of the outgoing generation inside SystemUI's listener set.
         */
        fun detachListener() {
            val pipeline = pipelineRef ?: return
            val proxy = attachedProxy
            runCatching {
                val listeners = listenerList(pipeline) ?: return
                listeners.removeIf { wrapper ->
                    runCatching { wrapper?.javaClass?.getField("listener")?.get(wrapper) }.getOrNull() === proxy
                }
            }
            attachedProxy = null
            listenerAttached = false
        }

        /** Diagnostics hook (adb broadcast) — how many notifications the pipeline currently holds. */
        fun debugCount(): Int = runCatching {
            val pipeline = pipelineRef ?: return 0
            val collection = pipeline.javaClass.getField("mNotifCollection").get(pipeline)
            (collection.javaClass.getField("mReadOnlyNotificationSet").get(collection) as? Collection<*>)?.size ?: 0
        }.getOrDefault(0)

        /** Rate-limited `CONTACT_BAR_NOTIF_REMOVED` line: proof of the clear-on-open behaviour. */
        @Volatile
        private var lastRemovalLogAt = 0L

        private fun logRemoval(entry: Any?) {
            val module = moduleRef ?: return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRemovalLogAt < 250L) return
            lastRemovalLogAt = now
            val sbn = runCatching {
                (entry?.javaClass?.getField("mSbn")?.get(entry)) as? StatusBarNotification
            }.getOrNull()
            val pkg = sbn?.packageName ?: "?"
            val title = runCatching {
                sbn?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            }.getOrNull()
            module.log(Log.INFO, TAG, "CONTACT_BAR_NOTIF_REMOVED pkg=$pkg title=$title", null)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 一个联系人只占一条（纯算术，见 `ConversationDedupeTest`）
// ---------------------------------------------------------------------------------------------

/** 头像指纹的分辨率：16x16 足够认人，又小到可以逐字节比对。 */
const val AVATAR_PRINT_SIDE = 16

/**
 * 头像/图标的**自有副本**：把 [source] 画进一张我们自己的 [px]×[px] 位图。
 *
 * 为什么必须复制，而不是把 [source] 直接交给 View：
 *  - `Icon.loadDrawable()` 给的 `BitmapDrawable` 是**按引用**包着通知里的那张位图，而本类会把它
 *    留在记忆里十几个小时（[RecentConversations] 的 TTL）；
 *  - 图标/封面位图的主人是 ROM —— 图标缓存、通知管线、媒体栈都会在自己的时机 `recycle()` 它们
 *    （`SlideIconView.toBitmap` 的注释里记着同一件事），而画面里还有引用；
 *  - 于是绘制那一刻就是 `Canvas: trying to use a recycled bitmap`，而且是在**画**的时候崩，
 *    堆栈里只有 `BitmapDrawable.draw`（`ContactBarView` 里那张 2026-10-09 的 wrong-thread 崩溃
 *    旁边就是这么一串）。经 `BitmapShader`（`CircleImageView`）画一张外来位图同样致命。
 *
 * 复制失败时返回 **null**（绝不回退成 [source]）：调用方会用备用图标，而"拷贝失败"最常见的原因
 * 正是那张图已经不完整了 —— 把它交出去就是把崩溃从这一帧推迟到下一帧。
 */
fun ownIconCopy(context: Context, source: Drawable?, px: Int = AVATAR_PX): Drawable? {
    source ?: return null
    // Already dead when handed to us: nothing to copy, and the view must not see it.
    if (source is BitmapDrawable && source.bitmap.isRecycled) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val src = source as? BitmapDrawable
        if (src != null) {
            // Center-crop like the `CircleImageView` would have (CENTER_CROP of a non-square
            // avatar must not turn into a stretch).
            val bmp = src.bitmap
            val side = minOf(bmp.width, bmp.height)
            val left = (bmp.width - side) / 2
            val top = (bmp.height - side) / 2
            canvas.drawBitmap(
                bmp,
                Rect(left, top, left + side, top + side),
                Rect(0, 0, px, px),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            source.setBounds(0, 0, px, px)
            source.draw(canvas)
        }
        BitmapDrawable(context.resources, bitmap)
    }.getOrNull()
}

/**
 * 通知 key（`userId|pkg|id|tag|uid`）里白名单聊天应用的那个包名，没有就返回 null。
 *
 * `onEntryRemoved` 只给 key（`NotificationEntry` 已经拆掉了），而通知事件是全系统的 ——
 * 靠它把"这条跟我有关"的判断压到一次字符串切分（见 `RecentConversations.isBarRelevant`）。
 */
fun imPackageOfKey(key: String): String? =
    key.split('|').firstOrNull { it in RecentConversations.IM_WHITELIST }

/** 每个通道 4 位 —— 1/16 的量化步长，重新编码不会改变结论。 */
private const val PRINT_LEVELS = 16

/** 量化级的字母表：一个头像 = `side * side * 3` 个十六进制字符。 */
private const val PRINT_CHARS = "0123456789abcdef"

/**
 * 头像指纹：把 [pixels]（`side x side` 的 ARGB）按通道量化成十六进制字符（每像素 3 个）。
 *
 * 为什么需要它：会话的身份是标题，而群消息的标题是 `群名：发送者`（Telegram 一类）—— 同一个人在
 * 几个群里说话就有几个标题，**头像却是同一张脸**，于是联系人条上出现几张一模一样的头像（用户反馈）。
 * 头像本身不能当 key（每次通知都是新的 Bitmap），指纹可以。
 *
 * alpha 不参与：同一个人两次通知里的不透明度不同，不该被当成两个人。
 */
fun avatarPrint(pixels: IntArray, side: Int = AVATAR_PRINT_SIDE): String {
    val count = minOf(pixels.size, side * side)
    val out = StringBuilder(count * 3)
    for (i in 0 until count) {
        val pixel = pixels[i]
        out.append(level((pixel shr 16) and 0xFF))
            .append(level((pixel shr 8) and 0xFF))
            .append(level(pixel and 0xFF))
    }
    return out.toString()
}

private fun level(channel: Int): Char =
    PRINT_CHARS[(channel * PRINT_LEVELS / 256).coerceIn(0, PRINT_LEVELS - 1)]

/**
 * 两张头像是不是同一个人。
 *
 * 逐位相等太严格：同一张图在两次通知里可能是不同的分辨率/重采样结果（我们的 16x16 是从 144x144
 * 的副本再缩一次得到的），差一两个量化级很正常。所以判据是「平均差小于一个量化级，且差得明显的
 * 通道不超过 1/8」—— 同一张脸轻松通过，不同的头像（哪怕同色系）通不过。
 *
 * 指纹缺失（通知没带头像、绘制失败）时一律判为不同：没有头像的条目画的是应用图标，
 * 认下来会把所有这样的人并成一条。
 */
fun printsMatch(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    if (a.isEmpty() || a.length != b.length) return false
    var sum = 0
    var far = 0
    for (i in a.indices) {
        val diff = kotlin.math.abs(a[i] - b[i])
        sum += diff
        if (diff > 2) far++
    }
    return sum <= a.length && far * 8 <= a.length
}

/**
 * 同一个人只留最新的一条（联系人条是联系人，不是会话列表）。
 *
 * 顺序与择优规则：
 *  - 新的在前（`postTime` 大者胜；同一毫秒时「还在通知栏里」的那条胜，它才带红点）；
 *  - 没有指纹的条目**只按标题**去重 —— 沿用原来的行为，也不会把两个没头像的人并掉；
 *  - 被合并掉的会话不是丢了：划掉这一条会连它一起划掉（见 `RecentConversations.forget`）。
 */
fun dedupeByPerson(items: List<Conversation>): List<Conversation> {
    val sorted = items.sortedWith(
        compareByDescending<Conversation> { it.postTime }.thenByDescending { it.live },
    )
    val kept = ArrayList<Conversation>(sorted.size)
    val prints = ArrayList<String?>(sorted.size)
    val titles = HashSet<String>(sorted.size)
    for (conversation in sorted) {
        val print = conversation.avatarPrint
        val duplicate = if (print != null) {
            prints.any { printsMatch(it, print) }
        } else {
            titles.contains(conversation.title)
        }
        if (duplicate) continue
        kept += conversation
        prints += print
        titles += conversation.title
    }
    return kept
}
