# 轻量打开（六开关）

> 目标：把系统里六种"跳转"改成小窗打开，全部由模块开关控制。判定逻辑在
> `com.repl.bubbledrawer.lightopen.LightOpen`（纯函数，单测覆盖），落地在两类钩子上：
> system_server 的闸门（3 个开关）与 SystemUI 的注入（3 个开关）。

## 1. 机制：两种完全不同的实现

### 1.1 system_server 闸门（分享至应用 / 文本操作 / 应用间跳转）

真机 ROM（`miui-services.jar`，pudding OS4.0.0.32）里的调用链：

```
ActivityStarter.executeRequest
  → ActivityStarterImpl.resolveActivity(Request, ActivityTaskSupervisor)
       request.activityOptions = MiuiFreeformServiceStub.getInstance()
                                    .checkStartActivityByFreeForm(...)      ← 返回值写回 Request
  → MiuiFreeformServiceImpl.checkStartActivityByFreeForm(...) : SafeActivityOptions
        if (supportFreeformByDeveloper && startActivityByFreeForm(aInfo, intent, fullscreenPackageName)) {
            ActivityOptions ff = MiuiMultiWindowUtils.getActivityOptions(ctx, targetPkg, true);
            modifyOptionsByFreeFormOptions(opts, ff);        // setLaunchWindowingMode(5) = FREEFORM
            return SafeActivityOptions.fromBundle(merged.toBundle(), ...);
        }
        return bOptions;                                     // 其余情况原样返回（全屏）
```

被改返回值的 `startActivityByFreeForm` 本体是一次**纯查询**、无副作用：

```java
private boolean startActivityByFreeForm(ActivityInfo aInfo, Intent intent, String fullscreenPackageName) {
    if (mSecurityManagerInternal == null)
        mSecurityManagerInternal = LocalServices.getService(SecurityManagerInternal.class);
    return mSecurityManagerInternal.checkGameBoosterPayPassAsUser(
            aInfo.packageName, intent, UserHandle.getUserId(aInfo.applicationInfo.uid), fullscreenPackageName);
}
```

于是：命中 → 直接返回 `true`（强制小窗）/ `false`（强制全屏）；不命中 → `chain.proceed()`
（保持原生，包括 MIUI 自己对游戏支付场景的判定）。

**为什么不在外层方法上做**：外层负责构造 ActivityOptions 与合并 bOptions，跳过它等于自己重写
落窗逻辑；内层是纯判定，改它是等价且最小的改动。

### 1.2 SystemUI 注入（竖屏/横屏点通知 / 控制中心长按）

这两处的调用进程是 `com.android.systemui`，而闸门开头就为它早退：

```java
WindowProcessController caller = atm.mProcessMap.getProcess(realCallingPid);
if (caller != null && "com.android.systemui".equals(caller.mName)) {
    Slog.d(TAG, " checkStartActivityByFreeForm systemui launch return");
    return bOptions;                    // ← SystemUI 的启动原样返回
}
```

反过来说，SystemUI 自己带过去的 options 会被原样采用 —— 所以这几处由 SystemUI 侧注入 MIUI 官方
姿势构造的小窗 options（`MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, /*noCheck*/ true, false)`）。

#### 结构：上膛 → 漏斗消费

真机上"点通知/长按磁贴"的路径**不止一条**（列表通知、悬浮通知/灵动岛、原生磁贴、插件磁贴……），
每条最后造 options 的位置也不同。所以采用两级结构，覆盖全部路径且只注入一次：

**① 上膛**（各入口先把"这次该不该小窗"算出来，放进一个带 TTL 的槽）

| 入口 | 覆盖 | 备注 |
|---|---|---|
| `StatusBarNotificationActivityStarter.startNotificationIntentPublic(...)`（7 参） | 所有"点通知" | 通知归属取 `NotificationEntry.mSbn.packageName`（`creatorPackage` 会被 MIUI 用自己的 PI 顶掉：日志里出现过 `pkg=android`、`pkg=com.android.systemui`） |
| `ActivityStarterImpl.postStartActivityDismissingKeyguard(PendingIntent[…] / Intent[…])` | 悬浮通知、插件磁贴、控制中心跳转 | 插件 dex 里 `FocusNotificationUtil`（灵动岛）与各 `*Tile` 都调它 |
| `MiuiQSTileBaseView$$ExternalSyntheticLambda2.onLongClick(View)` | **插件磁贴**长按 | lambda 捕获了磁贴对象（`f$0`），反射 `getLongClickIntent()`；插件磁贴本体在 `miui.systemui.plugin` 里，钩不到它自己的 `longClick` |
| `QSTileImpl.handleLongClick(Expandable)` | SystemUI 自己的磁贴（WiFi/蓝牙等） | — |

**② 消费**：`ActivityStarterUtilsKt.createActivityOptions(int, RemoteAnimationAdapter)` 是 SystemUI 造
options 的唯一出口，命中上膛就把小窗 bundle 换进去，MIUI 自己的锁屏解锁、通知动画、面板收起全部保持原样。
上膛带 2 秒 TTL，过期/未命中都会打日志。

**③ 兜底**（有些路径不走上面两个）

| 钩子 | 为什么需要 |
|---|---|
| `PendingIntent.send(...)` / `sendAndReturnResult(...)`（**所有带 Bundle 参数的重载**） | 灵动岛点开走的是 `DynamicIslandWindowAnimController.openAppUseDefaultAnim` 第 1984 行的 **1 参 `pi.send(bundle)`**；只钩 7 参重载会整条漏掉（2026-10-15 真机踩到） |
| `ContextImpl.startActivity(Intent, Bundle)` | SystemUI（含插件）进程内直接发起的启动，例如控制中心面板卡片 |
| `DynamicIslandWindowAnimController.openAppUseDefaultAnim(String, PendingIntent)` | 接管式注入（原方法 send 之后只剩一行日志，见 1989–2006 行，跳过不会破坏动画/状态）；与 send 钩子互为冗余 |

注入过的 bundle 带 `bubbledrawer.lightopen.applied=true`，`send` → `sendAndReturnResult` 链条不会重复处理。

#### 来源分类

`postStartActivityDismissingKeyguard` / `send` / `startActivity` 是公共出口，靠**调用栈**区分是谁发起的：
栈里出现 `FocusNotification` / `miui.systemui.notification` / `DynamicIsland` / `HeadsUp` → 通知规则；
`quicksettings` / `controlcenter` / `qs.tileimpl` / `QSTile` → 控制中心规则；**其它一律不干预**
（曾经把"未知来源"也按通知规则处理，会把媒体卡片"打开应用"之类的 SystemUI 启动也卷进来，已收回）。

## 2. 护栏（缺一条就会把"正常启动"当成"跳转"）

ROM 只排除：真实调用进程是 SystemUI、`aInfo == null`、`needFullScreen` + 游戏中心 sdk、
非默认显示器 + 协同模式，以及"目标包 == 当前前台包"。

**它不排除桌面**：桌面点图标时 caller 是 `com.miui.home`（不是 SystemUI）、目标包 != 前台包
（前台是桌面）—— 全局小窗模式下桌面启动也会变小窗。因此 `LightOpen.jumpDecision` 另加：

| 护栏 | 拦住的场景 |
|---|---|
| 前台包 ∈ HOME 包集合（运行时解析 `CATEGORY_HOME` + 显式带 `com.miui.home`，60s 缓存） | 桌面点图标、桌面文件夹、负一屏、桌面搜索、HyperOS 最近任务卡片 |
| 前台包为空 | 锁屏 / 息屏 / 没有明确前台 |
| 目标包 == 前台包 | 同应用内跳转（ROM 也会拦，双保险） |
| 系统拦截框（`com.miui.securitycenter`） | 小米安全中心"是否允许"弹窗不参与小窗（真实目标在用户点"允许"后的第二次启动里） |
| Gemini 启动蹦床（`com.google.android.apps.bard` → `com.google.android.googlequicksearchbox`） | 桌面启动 Gemini 时的蹦床跳转保持全屏（本质是"桌面启动"的延续） |
| SystemUI 侧：目标 ∈ `{android, com.android.systemui, com.miui.home}` | 系统界面自身的启动不参与（`LightOpenSystemUi.SYSTEM_PACKAGES`） |

另外 `packageSupportFreeform()` 为假的目标（不可 resize、MIUI freeform 黑名单/云控名单）会让
`MiuiMultiWindowUtils.getActivityOptions(...)` 返回 `null`：SystemUI 侧这时退到自造的
`ActivityOptions.makeBasic() + setLaunchWindowingMode(FREEFORM)`（日志 `LIGHT_OPEN_FALLBACK_OPTIONS`，
实测 `com.android.settings`、`com.tencent.mobileqq` 都会命中）；system_server 侧则原样返回、保持全屏。

## 3. 开关与判定

| 开关 | 判定函数 | 生效进程 |
|---|---|---|
| 竖屏点通知以小窗打开 | `notificationDecision(settings, 通知归属包, isPortrait=true)` | SystemUI |
| 横屏点通知以小窗打开 | `notificationDecision(settings, 通知归属包, isPortrait=false)` | SystemUI |
| 控制中心长按以小窗打开 | `controlCenterDecision(settings, targetPkg)` | SystemUI |
| 分享至应用（SEND / SEND_MULTIPLE） | `jumpDecision(...)` | system_server |
| 文本操作 | `jumpDecision(...)`：`PROCESS_TEXT` / `WEB_SEARCH`；或带 `android.view.textclassifier.extra.FROM_TEXT_CLASSIFIER` 的 Intent；或带选中文本 extra 的 `VIEW` | system_server |
| 应用间跳转（白/黑名单，按**目标**应用匹配） | `jumpDecision(...)` | system_server |

- **白名单**：名单内的目标应用 → 小窗；其余保持原生。
- **黑名单**：名单外的目标应用 → 一律小窗（全局小窗）；名单内的目标应用 → **强制全屏**
  （压掉 MIUI 原生的轻量打开）。黑名单是否决票：名单内的应用即使命中了分享/文本开关也保持全屏。

全部开关默认关闭；`enabled == false` 时钩子只做一次布尔读取后立即 `proceed()`。

## 4. 生效条件与验证

- **开关/名单改动**：两个进程都即时生效 —— 配置走 LSPosed 远程 prefs 的镜像，
  `LightOpenWatcher` 注册了变更监听 + 2 秒兜底重读（防止进程启动极早期镜像还是空的时把"全 false"
  缓存一辈子，那正是"重启后全部失效"的原因）。
- **模块代码改动**：`adb install -r` 后 SystemUI 与 **system_server 都会热重载**
  （`HOT_RELOADED process=system`），`onHotReloaded` 会重建闸门钩子 —— **不需要重启**。
  唯一需要重启的是**第一次**给 `scope.list` 加上 `system`（作用域变更由 LSPosed 在开机时应用）。
- 日志（`adb logcat`，模块行都带 `LSPosedFramework` 前缀与模块名）：
  - 装载：`LIGHT_OPEN_GATE_INSTALLED enabled=… jump=… mode=… targets=… share=… text=…`、
    `LIGHT_OPEN_NOTIFICATION_HOOK_INSTALLED path=injected|island`、
    `LIGHT_OPEN_NOTIFICATION_ARM_HOOK_INSTALLED params=7`、`LIGHT_OPEN_CONTROL_CENTER_HOOK_INSTALLED`、
    `LIGHT_OPEN_TILE_VIEW_HOOK_INSTALLED`、`LIGHT_OPEN_ARM_PENDING_INTENT_INSTALLED count=… intent=…`、
    `LIGHT_OPEN_OPTIONS_HOOK_INSTALLED`、`LIGHT_OPEN_SEND_HOOK_INSTALLED overloads=…`、
    `LIGHT_OPEN_CONTEXT_LAUNCH_HOOK_INSTALLED`、`LIGHT_OPEN_ISLAND_ANIM_HOOK_INSTALLED`
  - 判定：`LIGHT_OPEN_FREEFORM target=… top=… action=… why=list|share|text|global`、
    `LIGHT_OPEN_FULLSCREEN …`
  - 上膛/消费：`LIGHT_OPEN_*_ENTER …` / `LIGHT_OPEN_OPTIONS_APPLIED|SKIPPED|EXPIRED|NO_OPTIONS`
  - 兜底：`LIGHT_OPEN_SEND_CALL … stack=…` / `LIGHT_OPEN_SEND_APPLIED` /
    `LIGHT_OPEN_CONTEXT_LAUNCH_APPLIED` / `LIGHT_OPEN_ISLAND_ANIM_APPLIED`
  - 诊断探针：`LIGHT_OPEN_TEXT_PROBE action=… comp=… flags=… extras=[…] clip=… fromClassifier=… decision=…`
  - 目标不可用：`*_TARGET_UNAVAILABLE` / `LIGHT_OPEN_*_CLASS_MISSING`（ROM 改类名/字段名时会出现）

真机验证矩阵见 `test-plan-device.md` 的 A26 章节。

## 5. 真机踩坑记录（每次都是先有现象，再去 ROM 里找证据）

| 现象 | 根因 | 修法 |
|---|---|---|
| 通知/磁贴全部失效，重启后才好一点 | LSPosed 远程 prefs 镜像在进程启动早期还没同步，`LightOpenWatcher` 把"全 false"缓存住了 | 变更监听 + 每 2 秒兜底重读 |
| `adb install -r` 之后 system_server 侧全部失效 | 热重载把闸门钩子卸了，而 `onSystemServerStarting` 不会再触发；且 `Class.forName` 用 boot/系统 loader 找不到 `miui-services.jar` 里的类 | `onHotReloaded` 重建 + classloader 逐级回退 |
| 悬浮通知（灵动岛）点开仍旧全屏 | 它走 `DynamicIslandWindowAnimController.openAppUseDefaultAnim` 里的 **1 参 `pi.send(bundle)`**，7 参钩子覆盖不到 | 钩所有带 Bundle 的 `send`/`sendAndReturnResult` 重载 |
| 控制中心长按没反应 | 插件磁贴的 `longClick` 实现在 `miui.systemui.plugin` 里，钩不到 | 改钩 `postStartActivityDismissingKeyguard` + 磁贴视图 lambda |
| 长按磁贴后仍全屏 | 目标不在 MIUI freeform 名单里（`unsupported freeform mode`），`getActivityOptions` 返回 null | 退到自造 freeform options |
| 从桌面开 Gemini，Gemini 里点链接变小窗（"应用间跳转"却是关的） | `文本操作` 那条规则把 `VIEW https` 一律当成文本操作 | 只认 `FROM_TEXT_CLASSIFIER` / 带选中文本 extra 的 `VIEW`；蹦床那一对包名显式放行 |
| 安全中心"是否允许"弹窗被小窗 | 拦截框自己也是"目标应用" | `target == com.miui.securitycenter` 一律不干预 |
