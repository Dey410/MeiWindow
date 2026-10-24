package com.repl.bubbledrawer.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import com.repl.bubbledrawer.R

/**
 * 聚合支付：Flyme `QuickPayWindowHelper`（windowmode/utils/n → C2801n）+ `V0/a` 的复刻。
 *
 * Flyme 那边的链路是「聚合支付」条目（本地合成的 shortcut id = `aggregate_payment`）被点击后，
 * 并不启动任何应用，而是把**同一个小窗联系人条**（`C2831I`）切到支付模式：条里摆
 * 微信扫一扫 / 收付款、支付宝扫一扫 / 付款码四个入口，条目通过
 * `LauncherApps` 拉到的动态快捷方式按 id 绑定（`m9063g`），点谁就 `startShortcut` 谁。
 *
 * 这里分成三块：
 *  - [entry]：功能页那枚独立条目（图标取自 Flyme SystemUITools 的 `drawable/aggregate_payment`）；
 *  - [resolveEntries]：SystemUI 侧把四个支付入口解析成可启动的 [ShortcutItem]
 *    （id 映射自 Flyme `m9063g`：`launch_type_scan_qrcode`/`launch_type_offline_wallet`/`1001`/`1002`）；
 *  - [requestShow]：App 进程（管理页）没有联系人条，发广播让 SystemUI 弹。
 */
object AggregatePayment {

    private const val TAG = "AggregatePayment"

    /** App 端请求 SystemUI 弹出聚合支付条的广播（FanHost 里有对应接收器，带发送方校验）。 */
    const val ACTION_SHOW = "com.repl.bubbledrawer.action.SHOW_AGGREGATE_PAY"

    /** 内建条目的稳定键。合成包名用模块自己：收藏/详情序列化的 `pkg#id` 规则原样适用。 */
    const val PACKAGE = "com.repl.bubbledrawer"
    const val ID = "aggregate_payment"
    const val PIN_KEY = "$PACKAGE#$ID"

    /** LauncherApps 查询 flags：manifest | dynamic | pinned（与 ShortcutRepository 扫描同一族）。 */
    private const val QUERY_FLAGS = 1035

    /**
     * 应用专属直达方式：支付宝用 `alipays://`，微信用它自己那条
     * `ShortCutDispatchAction` + `LauncherUI.Shortcut.LaunchType`。
     *
     * 微信的目标 Activity 必须是 `com.tencent.mm.ui.ShortCutDispatchActivity`（反编译确认：
     * 它才是读 `LauncherUI.Shortcut.LaunchType` 并分发到 BaseScanUI / WalletOfflineEntranceUI
     * 的地方；`LauncherUI` 本身不处理这个 action）。这个 Activity 是 `singleInstance`，
     * 只在 `onCreate` 分发、没有 `onNewIntent` —— 所以每次发射前要先清掉旧实例的任务，
     * 否则第二次点击只会走 `onNewIntent`，页面不动。
     *
     * 为什么要自己拼微信的 Intent：`LauncherApps.startShortcut` 交给系统后，微信的
     * 分发 Activity 再起 `BaseScanUI` / `WalletOfflineEntranceUI`（它自己带
     * `FLAG_ACTIVITY_NEW_TASK`）。如果这时任务不是小窗，后续页面就全屏；而且一旦留下一个
     * 全屏微信任务，下一次快捷方式还会复用它 —— 2026-10-24 用户实测：「微信打开收付款是
     * 全屏，退出去下次扫一扫也变全屏」。自己拼 Intent 才能把 freeform options +
     * `setLaunchTaskId` 一起交给这次启动。
     */
    data class DirectLaunch(
        val scheme: String? = null,
        val action: String? = null,
        val component: String? = null,
        val extraKey: String? = null,
        val extraValue: String? = null,
    )

    /**
     * 四个支付入口，顺序照 Flyme（`m9052r` 的 0..3）：
     * 微信扫一扫 → 微信收付款 → 支付宝扫一扫 → 支付宝付款码。
     *
     * `id` 是各家应用自己声明的动态快捷方式 id（Flyme `m9063g` 绑定用的就是它们）；
     * `1001/1002` 的方向（扫一扫/付款码）从 Flyme 的绑定关系反推，真机若发现反了只需对调这两行。
     */
    private data class Spec(
        val pkg: String,
        val id: String,
        val labelRes: Int,
        val iconRes: Int,
        val direct: DirectLaunch? = null,
    )

    private val SPECS = listOf(
        Spec(
            "com.tencent.mm", "launch_type_scan_qrcode", R.string.pay_wechat_scan, R.drawable.wechat_scan,
            direct = DirectLaunch(
                action = "com.tencent.mm.ui.ShortCutDispatchAction",
                component = "com.tencent.mm.ui.ShortCutDispatchActivity",
                extraKey = "LauncherUI.Shortcut.LaunchType",
                extraValue = "launch_type_scan_qrcode",
            ),
        ),
        Spec(
            "com.tencent.mm", "launch_type_offline_wallet", R.string.pay_wechat_money, R.drawable.wechat_money,
            direct = DirectLaunch(
                action = "com.tencent.mm.ui.ShortCutDispatchAction",
                component = "com.tencent.mm.ui.ShortCutDispatchActivity",
                extraKey = "LauncherUI.Shortcut.LaunchType",
                extraValue = "launch_type_offline_wallet",
            ),
        ),
        Spec(
            "com.eg.android.AlipayGphone", "1001", R.string.pay_alipay_scan, R.drawable.alipay_scan,
            direct = DirectLaunch(scheme = "alipays://platformapi/startapp?appId=10000007&sourceId=scan3dtouch"),
        ),
        Spec(
            "com.eg.android.AlipayGphone", "1002", R.string.pay_alipay_code, R.drawable.alipay_pay,
            direct = DirectLaunch(scheme = "alipays://platformapi/startapp?appId=20000056"),
        ),
    )

    /** 一个解析完成的支付入口：展示信息 + 可启动的条目（联系人条 bindPay 用）。 */
    data class PayAction(
        val icon: Drawable?,
        val label: String,
        val launch: ShortcutItem?,
        val direct: DirectLaunch? = null,
    )

    /** 功能页的独立条目。图标/文案都是模块自己的资源（Flyme SystemUITools 的图标已拷进 res）。 */
    fun entry(context: Context): ShortcutItem = ShortcutItem(
        id = ID,
        packageName = PACKAGE,
        label = context.getString(R.string.aggregate_payment),
        type = ShortcutType.AGGREGATE_PAYMENT,
        iconDrawable = runCatching { context.getDrawable(R.drawable.aggregate_payment) }.getOrNull(),
    )

    /**
     * [entry] 的进程无关取法：SystemUI 进程里 `context` 是系统界面的，模块资源要借
     * [com.repl.bubbledrawer.xposed.ModuleResources] 的包上下文解析；App 进程原样返回自己。
     */
    fun entryIn(context: Context): ShortcutItem =
        entry(com.repl.bubbledrawer.xposed.ModuleResources.packageContext(context) ?: context)

    /** 面板/管理页的点击分流判据：序列化往返后 type 仍是自己，pinKey 兜底。 */
    fun isEntry(item: ShortcutItem): Boolean =
        item.type == ShortcutType.AGGREGATE_PAYMENT || item.pinKey == PIN_KEY

    /** App 进程用：自己没有联系人条，交给 SystemUI 弹（导出广播，发送方带身份校验）。 */
    fun requestShow(context: Context) {
        runCatching {
            com.repl.bubbledrawer.xposed.PinSyncReceiver.sendBroadcastWithIdentity(
                context,
                Intent(ACTION_SHOW).setPackage("com.android.systemui"),
            )
            Log.i(TAG, "SHOW_REQUESTED")
        }
    }

    /**
     * 解析四个支付入口（SystemUI 进程调用 —— 这里的 LauncherApps 查询和管理页扫描同权）。
     *
     * 优先用应用发布的动态快捷方式（`startShortcut` 直达扫一扫/付款码页）；应用装了但拉不到
     * 对应 shortcut 时退到主界面 Intent（Flyme `m9074v` 的降级语义）；应用没装则跳过该应用。
     * 全部跳过 = 返回空列表，调用方提示「未找到支付入口」。
     */
    fun resolveEntries(context: Context): List<PayAction> {
        // 图标/文案是模块资源：在 SystemUI 进程里必须借模块包的 Resources 解析（见 ModuleResources）。
        val module = com.repl.bubbledrawer.xposed.ModuleResources.packageContext(context) ?: context
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val shortcutsByPkg = HashMap<String, Map<String, android.content.pm.ShortcutInfo>>()
        val out = ArrayList<PayAction>(SPECS.size)
        for (spec in SPECS) {
            val info = shortcutsFor(launcherApps, spec.pkg, shortcutsByPkg)[spec.id]
            // LauncherApps 返回的 ShortcutInfo 里 `getIntents()` **恒为 null**：ShortcutService
            // 用 CLONE_REMOVE_FOR_LAUNCHER 明确把 intent 剥掉了（「Launchers can only start a
            // shortcut intent with LauncherApps#startShortcut」）。所以这里拿不到 URI 是正常的，
            // 真正的启动必须走 startShortcut（见 ContactBarController.launchPayEntryInNewWindow）。
            //
            // 只有「应用没发布这个快捷方式」才退到主界面 Intent。快捷方式存在时绝不能拿主界面
            // Intent 顶上：那会让每条都显示成可启动，实际只是把应用主页面拉起来 —— 2026-10-24
            // 「四个入口点了都不跳转」的直接原因。
            val shortcutUri = info?.intents?.firstOrNull()?.toUri(0)
            val intentUri = entryIntentUri(shortcutUri, launchIntentUri(context, spec.pkg), info != null)
            if (info == null && intentUri == null) {
                Log.i(TAG, "ENTRY_SKIPPED pkg=${spec.pkg} id=${spec.id} (app missing or no launch intent)")
                continue
            }
            val launch = ShortcutItem(
                id = spec.id,
                packageName = spec.pkg,
                label = module.getString(spec.labelRes),
                type = ShortcutType.APP_SHORTCUT,
                shortcutInfo = info,
                intentUri = intentUri,
            )
            out += PayAction(
                icon = runCatching { module.getDrawable(spec.iconRes) }.getOrNull(),
                label = module.getString(spec.labelRes),
                launch = launch,
                direct = spec.direct,
            )
        }
        return out
    }

    /** 按包名拉一次动态快捷方式并按 id 索引；查询失败按「没有」处理，结果在本次调用内缓存。 */
    private fun shortcutsFor(
        launcherApps: LauncherApps?,
        pkg: String,
        cache: MutableMap<String, Map<String, android.content.pm.ShortcutInfo>>,
    ): Map<String, android.content.pm.ShortcutInfo> {
        if (launcherApps == null) return emptyMap()
        cache[pkg]?.let { return it }
        val found = runCatching {
            val query = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(QUERY_FLAGS)
            launcherApps.getShortcuts(query, Process.myUserHandle()).orEmpty().associateBy { it.id }
        }.onFailure {
            Log.w(TAG, "SHORTCUT_QUERY_FAILED pkg=$pkg", it)
        }.getOrDefault(emptyMap())
        cache[pkg] = found
        return found
    }

    /**
     * 一条支付入口最终用来直启的 Intent URI（纯逻辑，单测锁住）。
     *
     * 有 [ShortcutInfo] 时**只认它自己的 intent URI**，拿不到就保持 null（启动由
     * `LauncherApps.startShortcut` 完成）—— 绝不能退到应用主界面 Intent：那会把「点扫一扫」
     * 变成「打开微信首页」，正是 2026-10-24 用户报的「点了不跳转」。没有 ShortcutInfo
     * （应用没发布这个快捷方式）才允许退到主界面 Intent。
     */
    internal fun entryIntentUri(
        shortcutUri: String?,
        appLaunchUri: String?,
        hasShortcut: Boolean,
    ): String? = shortcutUri ?: if (hasShortcut) null else appLaunchUri

    /** 应用装了但没发布对应 shortcut 时的降级：主界面 Intent（Flyme `m9061F` 的等价物）。 */
    private fun launchIntentUri(context: Context, pkg: String): String? =
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg)?.toUri(0) }.getOrNull()
}