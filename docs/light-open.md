# 轻量打开（六开关）

> 目标：把系统里六种"跳转"改成小窗打开，全部由模块开关控制。判定逻辑在
> `com.repl.bubbledrawer.lightopen.LightOpen`（纯函数，单测覆盖），落地在三个钩子上。

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

反过来说，SystemUI 自己带过去的 options 会被原样采用。所以这两处由 SystemUI 侧注入 MIUI 官方
姿势构造的小窗 options：

```java
ActivityOptions opts = MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, /*noCheck*/ true, false);
pendingIntent.send(ctx, 0, fillIn, null, null, null, opts.toBundle());
```

- **通知**：钩 `StatusBarNotificationActivityStarterInjectorImpl$startNotificationIntentInjected$1.run()`
  （R8 生成的 Runnable，字段 `$intent` / `$bundle` 是 public 非 final），把 `$bundle` 换成小窗 bundle，
  其余（锁屏解锁、通知动画、UiOffloadThread 异步发送）保持 MIUI 原样。
  这个 bundle 还补了 `setPendingIntentLaunchFlags(0x18000000)`（NEW_TASK | MULTIPLE_TASK）——
  照抄 MIUI 自己的通知迷你窗（`AppMiniWindowManagerImpl$launchMiniWindowActivity$1.run()`）：应用已经
  有一个全屏任务时，没有 MULTIPLE_TASK 的话 PendingIntent 只会把那个任务提到前台（仍旧全屏）。
- **控制中心**：钩 `QSTileImpl.handleLongClick(Expandable)`。SystemUI 的 `ActivityStarter` 接口没有
  带 ActivityOptions 的重载，所以直接接管这一次启动（锁屏时不接管，交给 MIUI 的解锁流程）；
  尽力调用 `mHost.collapsePanels()` 收面板。已知取舍：没有调用 MIUI 的
  `ControlCenterUtils.getSettingsSplitIntent(...)`（磁贴长按页的"设置分屏"包装被跳过）。

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

另外 `packageSupportFreeform()` 为假的目标（不可 resize、MIUI freeform 黑名单/云控名单）会让
`MiuiMultiWindowUtils.getActivityOptions(...)` 返回 `null`，外层原样返回 → 自动保持全屏，
日志里有 `MiuiMultiWindowUtils: pkg name: X unsupported freeform mode`。

## 3. 开关与判定

| 开关 | 判定函数 | 生效进程 |
|---|---|---|
| 竖屏点通知以小窗打开 | `notificationDecision(settings, creatorPkg, isPortrait=true)` | SystemUI |
| 横屏点通知以小窗打开 | `notificationDecision(settings, creatorPkg, isPortrait=false)` | SystemUI |
| 控制中心长按以小窗打开 | `controlCenterDecision(settings, targetPkg)` | SystemUI |
| 分享至应用（SEND / SEND_MULTIPLE） | `jumpDecision(...)` | system_server |
| 文本操作（PROCESS_TEXT / WEB_SEARCH / VIEW http(s)） | `jumpDecision(...)` | system_server |
| 应用间跳转（白/黑名单，按**目标**应用匹配） | `jumpDecision(...)` | system_server |

- **白名单**：名单内的目标应用 → 小窗；其余保持原生。
- **黑名单**：名单外的目标应用 → 一律小窗（全局小窗）；名单内的目标应用 → **强制全屏**
  （压掉 MIUI 原生的轻量打开）。黑名单是否决票：名单内的应用即使命中了分享/文本开关也保持全屏。

全部开关默认关闭；`enabled == false` 时两个钩子都只做一次布尔读取后立即 `proceed()`。

## 4. 生效条件与验证

- 两个 SystemUI 钩子：改代码/开关后即时生效；热重载会重建。
- system_server 钩子：**需要重启**（system_server 没有热重载入口，见
  `input-hook-architecture.md` §3.3）。作用域 `scope.list` 已加 `system`。
- 日志（`adb logcat`）：
  - `LightOpenGate: LIGHT_OPEN_GATE_INSTALLED ... / LIGHT_OPEN_FREEFORM target=… top=… why=…`
    / `LIGHT_OPEN_FULLSCREEN ...`
  - `LightOpenSystemUi: LIGHT_OPEN_NOTIFICATION pkg=… portrait=…`
    / `LIGHT_OPEN_CONTROL_CENTER pkg=…`
  - 目标不可用时打 `*_TARGET_UNAVAILABLE`（ROM 改了类名/字段名就会看到它）。

真机验证矩阵见 `test-plan-device.md` 的 A26 章节。
