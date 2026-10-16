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

/**
 * `Notification.EXTRA_CONVERSATION_TITLE`（"android.conversationTitle"）—— 这个常量是 @hide，
 * 所以写字面量。群通知的会话名（群名）在 MessagingStyle 之外就靠它。
 */
private const val EXTRA_CONVERSATION_TITLE = "android.conversationTitle"

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
     * 条上显示的名字、也是「这是哪个会话」的身份 = **会话名**：群消息就是群名，1:1 会话就是对方。
     *
     * 用户 2026-10-12 的裁定（原话「群通知应该用群名啊」）：群通知的标题是 `群名：发送者`
     * （`YuKongA | Chat：Perlinkca`），同一个群里两个人说话就是两条标题不同的通知 —— 而名字列只有
     * 45dp，两条都截成 `YuKongA | Chat`，看起来就是"重复的联系人"。会话名是**前半段**（群名），
     * 人是后半段；条上按会话名去重、按会话名显示。
     *
     * 拿不到（没有 MessagingStyle，标题里也没有分隔符）时退回 [title]。
     *
     * 它同时是记忆里的**主键**（`RecentConversations` 的 `remembered`/`forgotten`）：一个会话一条，
     * 而不是一条通知一条 —— QQ 会把未读数挂在标题末尾（`通信12301潘宇锋(2条新消息)`），
     * 按标题归档就是一个会话好几条（见 [normalizeConversationName]）。
     */
    val label: String? = null,
) {
    /** [label] 拿不到时的退路：标题本身。 */
    val displayLabel: String get() = label?.takeIf { it.isNotEmpty() } ?: title
}

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
 *
 * ## 记忆的主键是**会话名**，不是通知标题（2026-10-16）
 *
 * 一个会话可能有好几条标题不同的通知：Telegram 的群里两个人说话是 `群名：甲` / `群名：乙`，
 * QQ 则把未读数挂在标题末尾（`通信12301潘宇锋(2条新消息)`）。按标题归档就会**一个会话好几条记忆**
 * —— 条上是几个一样的头像（名字列只有 45dp，全截成同一个前缀），每个应用的配额也被吃掉。
 * 所以 `remembered`/`forgotten` 的键是 `包名|会话名`（[conversationId]），会话名由
 * [normalizeConversationName] 把那些**会变的东西**剪掉（未读数）、[conversationName] 再剪掉发送者。
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

    /** Conversation memory that survives MIUI's "opening the app clears its notifications". */
    private class Remembered(
        /**
         * `包名|会话名` —— 记忆的**主键**，一个会话一条。不是通知标题：同一个会话的通知标题会变
         * （群里的不同发送者、QQ 的 `(2条新消息)`），按标题存就会一个会话攒出好几条记忆
         * （条上是几个一样的头像，而且把每个应用 20 条的配额吃掉）。
         */
        val id: String,
        val pkg: String,
        /** 最近一条通知的**原始标题**（诊断与无障碍用；未读数后缀一变它就跟着变，所以不做身份）。 */
        var title: String,
        var key: String?,
        var icon: Drawable?,
        var pendingIntent: PendingIntent?,
        var launchIntent: Intent?,
        var userId: Int,
        var postTime: Long,
        var lastSeenAt: Long,
        /** 条上显示的名字（见 [Conversation.label]）：会话名，群消息就是群名。 */
        var label: String,
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
            // 键是**会话名**而不是通知标题：同一个会话的几条通知标题不同（群里的两个人、
            // QQ 的 `(2条新消息)`），按标题存就是几条并排站着（[dedupeByConversation] 只是兜底）。
            val previous = live[conv.displayLabel]
            if (previous == null || conv.postTime >= previous.postTime) {
                live[conv.displayLabel] = conv.copy(live = true)
            }
        }
        lastLiveCount = live.size
        prune(now)
        val out = merge(pkg, live)
        logList(pkg, out)
        return out
    }

    /**
     * 条上现在有什么（**会话名** + 是不是还在通知栏里），**只在变化时**打一行。
     *
     * 这是「两条一样的联系人」唯一能事后核对的东西：合并发生在数据层，条上是看不见证据的 ——
     * 用户 2026-10-12 的复现就是从这一行看出来的（两条 `YuKongA | Chat：…` 是同一个群）。
     */
    private fun logList(pkg: String, list: List<Conversation>) {
        val line = "CONTACT_BAR_LIST pkg=$pkg n=${list.size} live=$lastLiveCount " +
            list.take(8).joinToString(" | ") {
                it.displayLabel + (if (it.live) "*" else "")
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
        // 条上那一条代表**一个会话**（见 [dedupeByConversation]），记忆也是按会话存的 —— 所以划掉就是
        // 划掉这一个会话。以前按通知标题扫（记忆是按标题存的），扫不到的那些标题
        // （QQ 的 `(4条新消息)` 还没出现过）照样能把同一个会话从另一条消息里摆回来。
        val id = conversationId(conversation.pkg, conversation.displayLabel)
        val postTime = remembered[id]?.postTime ?: conversation.postTime
        remembered.remove(id)
        forgotten[id] = Forgotten(postTime, now)
        cachedPkg = null
        log(
            Log.INFO,
            "CONTACT_BAR_FORGET pkg=${conversation.pkg} label=${conversation.displayLabel} " +
                "title=${conversation.title}",
            null,
        )
    }

    private fun isForgotten(conv: Conversation): Boolean {
        val id = conversationId(conv.pkg, conv.displayLabel)
        val entry = forgotten[id] ?: return false
        // A newer notification means the chat has something to say again.
        if (conv.postTime > entry.postTime) {
            forgotten.remove(id)
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
        val id = conversationId(conv.pkg, conv.displayLabel)
        val record = remembered.getOrPut(id) {
            Remembered(id, conv.pkg, conv.title, null, null, null, null, conv.userId, 0L, now, conv.displayLabel)
        }
        if (conv.icon != null) record.icon = conv.icon
        if (conv.pendingIntent != null) record.pendingIntent = conv.pendingIntent
        if (conv.launchIntent != null) record.launchIntent = conv.launchIntent
        record.userId = conv.userId
        record.key = conv.key
        record.title = conv.title
        record.label = conv.displayLabel
        record.postTime = maxOf(record.postTime, conv.postTime)
        record.lastSeenAt = now
    }

    /** Memory for [pkg], overlaid with whatever is still posted (live wins: newer avatar/intent). */
    private fun merge(pkg: String, live: Map<String, Conversation>): List<Conversation> {
        val merged = LinkedHashMap<String, Conversation>()
        for (record in remembered.values) {
            if (record.pkg != pkg) continue
            merged[record.id] = Conversation(
                pkg = record.pkg,
                key = record.key.orEmpty(),
                title = record.title,
                icon = record.icon,
                pendingIntent = record.pendingIntent,
                postTime = record.postTime,
                launchIntent = record.launchIntent,
                userId = record.userId,
                label = record.label,
            )
        }
        merged.putAll(live)
        // 一个**会话**只留一条（同一个群的每个人说话都是一条通知），再按时间排、再截断 ——
        // 顺序很要紧：先截断会把同一个群的几条都算进 20 条里，真实会话数就被挤掉了。
        val out = dedupeByConversation(merged.values.toList())
        if (out.size != merged.size) logMerged(pkg, merged.size, out)
        return if (out.size > MAX_ITEMS) ArrayList(out.subList(0, MAX_ITEMS)) else out
    }

    /** One line when a conversation's several notifications collapsed — the duplicate-entry report. */
    private fun logMerged(pkg: String, before: Int, kept: List<Conversation>) {
        val line = "CONTACT_BAR_MERGED pkg=$pkg notifications=$before conversations=${kept.size} " +
            "keep=${kept.take(6).joinToString { it.displayLabel }}"
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
        }
        remembered.values.groupBy { it.pkg }.forEach { (_, list) ->
            if (list.size > MAX_REMEMBERED_PER_APP) {
                list.sortedByDescending { it.lastSeenAt }
                    .drop(MAX_REMEMBERED_PER_APP)
                    .forEach { remembered.remove(it.id) }
            }
        }
        if (remembered.size <= MAX_REMEMBERED_TOTAL) return
        remembered.values
            .sortedByDescending { it.lastSeenAt }
            .drop(MAX_REMEMBERED_TOTAL)
            .forEach { remembered.remove(it.id) }
    }

    /** 记忆的主键：`包名|会话名`。同一个会话的几条通知（标题各不同）落在同一格。 */
    private fun conversationId(pkg: String, label: String) = "$pkg|$label"

    /**
     * `Notification.MessagingStyle.extractMessagingStyleFromNotification(Notification)` —— **@hide**，
     * 所以走反射（模块跑在 SystemUI 进程里，不受 hidden API 限制）。查找缓存一次：
     * 每条通知都要用它拿「群名」和「发送者头像」。
     */
    private val extractMessagingStyle: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.app.Notification\$MessagingStyle")
                .getMethod("extractMessagingStyleFromNotification", Notification::class.java)
        }.getOrNull()
    }

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

        // **谁发的**（只为了头像）：MessagingStyle 是权威答案（Telegram 就用它）—— 人 = 最后一条
        // 不是自己的消息的发送者；通知本身不带大图标时，头像只能从这里拿。
        val style = runCatching { extractMessagingStyle?.invoke(null, n) as? Notification.MessagingStyle }
            .getOrNull()
        val sender = runCatching {
            style?.messages?.lastOrNull { it.senderPerson != null && it.senderPerson != style.user }
                ?.senderPerson
        }.getOrNull()
        // **这是哪个会话**（条上的身份与名字）：MessagingStyle 的 `conversationTitle` 是权威答案
        // （群名；1:1 会话为 null）。没有它就看 `EXTRA_CONVERSATION_TITLE`（@hide，用字面量），
        // 再不行才从标题的 `群名：发送者` 里取前半段 —— 用户 2026-10-12 的裁定「群通知应该用群名啊」。
        // 三个来源都过一遍 [normalizeConversationName]：QQ 的未读数后缀可能挂在标题上，
        // 也可能挂在会话名字段里（设备日志里它是从标题进来的，但没必要赌这一点）。
        val label = runCatching { style?.conversationTitle?.toString() }
            .getOrNull()?.let(::normalizeConversationName)?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                n.extras?.getCharSequence(EXTRA_CONVERSATION_TITLE)?.toString()
            }.getOrNull()?.let(::normalizeConversationName)?.takeIf { it.isNotEmpty() }
            ?: conversationName(title)

        if (!cachedIcons.containsKey(key)) {
            val raw = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
                ?: runCatching {
                    val extra = n.extras?.get(Notification.EXTRA_LARGE_ICON)
                    (extra as? android.graphics.drawable.Icon)?.loadDrawable(context)
                }.getOrNull()
                // 头像的最后一站：MessagingStyle 里那位发送者自己的图标 —— Telegram 的聊天通知就靠它
                // （通知本身不带大图标，只认 `getLargeIcon` 的话条上只能画应用图标）。
                ?: runCatching { sender?.icon?.loadDrawable(context) }.getOrNull()
            cachedIcons[key] = ownAvatar(raw)
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
            label = label,
        )
    }

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

        /**
         * Memory bounds. Flyme caps its list at 20 (`C2763k.java:405`) and the bar shows the same 20
         * (`MAX_ITEMS`) —— 条现在能左右滑动，所以每个应用真的可能有 20 条要显示。
         */
        private const val MAX_REMEMBERED_PER_APP = 20
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
// 一个会话只占一条（纯算术，见 `ConversationDedupeTest`）
// ---------------------------------------------------------------------------------------------

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

/**
 * 条上的身份 = **会话名**（群消息是群名，1:1 会话是对方）—— [Conversation.displayLabel]。
 * 一个会话只留最新的一条。
 *
 * 顺序与择优规则：
 *  - 新的在前（`postTime` 大者胜；同一毫秒时「还在通知栏里」的那条胜，它才带红点）；
 *  - 同一个群里的两个人说话 = 两条标题（`群名：甲` / `群名：乙`）但**同一个会话名** → 合成一条，
 *    条上显示群名（用户 2026-10-12 的裁定「群通知应该用群名啊」；他复现的
 *    `YuKongA | Chat：Perlinkca` 与 `YuKongA | Chat：Yife Playte` 就是同一个群里的两个人，
 *    名字列只有 45dp，两条都截成 `YuKongA | Chat`，看起来像两条重复的联系人）；
 *  - QQ 把未读数挂在标题末尾（`通信12301潘宇锋(2条新消息)`，用户 2026-10-16 的复现）→
 *    [normalizeConversationName] 剪掉之后同一个会话名 → 合成一条；
 *  - 同一个人在两个群里说话 = 两个会话名 → 两条，各显示自己的群名；
 *  - 被合并掉的标题不是丢了：划掉这一条会连它一起划掉（见 `RecentConversations.forget`）。
 *
 * 记忆已经按会话名归档（见 `RecentConversations` 的类注释），这里是最后一道兜底：任何两条显示名字
 * 相同的记录都只留一条。
 */
fun dedupeByConversation(items: List<Conversation>): List<Conversation> {
    val sorted = items.sortedWith(
        compareByDescending<Conversation> { it.postTime }.thenByDescending { it.live },
    )
    val kept = ArrayList<Conversation>(sorted.size)
    val labels = HashSet<String>(sorted.size)
    for (conversation in sorted) {
        if (!labels.add(conversation.displayLabel)) continue
        kept += conversation
    }
    return kept
}

/**
 * 标题里**会话名**的那一半：`群名：发送者` → `群名`（群通知的标题形状，设备日志实例：
 * `StarVoyager Ideal：ksm`、`YuKongA | Chat：Perlinkca`）。没有分隔符时就是标题本身（1:1 会话）。
 * 两边都过一遍 [normalizeConversationName]（未读数后缀可能在整个标题末尾，也可能在前半段末尾）。
 *
 * 只在 `MessagingStyle.conversationTitle` 与 `EXTRA_CONVERSATION_TITLE` 都拿不到时才走这里 ——
 * 那两个是权威答案，这个只是退路。
 */
fun conversationName(title: String): String {
    for (separator in listOf('：', ':')) {
        val index = title.indexOf(separator)
        if (index <= 0) continue
        val name = normalizeConversationName(title.substring(0, index))
        if (name.isNotEmpty()) return name
    }
    return normalizeConversationName(title)
}

/**
 * QQ 挂在标题末尾的未读数：`通信12301潘宇锋(2条新消息)`（**用户 2026-10-16 的复现**，
 * `CONTACT_BAR_LIST` 里原样三行同一个人：
 * `通信12301潘宇锋 | … | 通信12301潘宇锋(2条新消息) | 通信12301潘宇锋(3条新消息) | …`）。
 *
 * 它随每条新消息变化，于是**同一个会话**在记忆里、在条上各占好几条 —— 而名字列只有 45dp，
 * 几条都截成「通信123…」，看上去就是"重复的联系人"（2026-10-12 那次是 Telegram 的
 * `群名：发送者`，形状不同，病根一样：把**会变的东西**当成了身份）。
 */
private val UNREAD_COUNT_SUFFIX = Regex("[（(\\[【]\\s*\\d+\\s*条新消息\\s*[）)\\]】]\\s*$")

/**
 * 会话名的规范化：去掉末尾那些"每来一条消息就变"的装饰（[UNREAD_COUNT_SUFFIX]）。纯算术，见
 * `ConversationDedupeTest`。全去掉会变空（标题本身就是这串装饰）时退回原样，绝不返回空串。
 */
fun normalizeConversationName(raw: String): String {
    val trimmed = raw.trim()
    val stripped = UNREAD_COUNT_SUFFIX.replace(trimmed, "").trim()
    return stripped.ifEmpty { trimmed }
}
