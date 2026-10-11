# 真机验证清单（Android 13+，无 root 优先）

安装：
```
E:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## A0 应用列表完整性（重启后不缩水）
> 复现过的故障：重启两次后扇子"更多"面板只剩「设置/联系人」。原因：模块在**开机时**（用户还没解锁）
> 于 SystemUI 进程里枚举应用，平台此时只给出直启子集（实测 2 个 vs 解锁后 216 个），而这份结果被
> `AppRepository.cache` 缓存到 SystemUI 结束。
- [ ] 重启手机，**先不要解锁**：`adb logcat -s MeiWindow_AppRepo` 出现
      `loadAll finished … userUnlocked=false`（这一步列表小是平台行为，不算故障）
- [ ] 解锁后：同 tag 出现 `APP_LIST_RELOADED reason=android.intent.action.USER_UNLOCKED total=2xx`，
      并且扇子/更多面板立刻是**全量应用**（不是只有设置/联系人）
- [ ] 手动核对：`adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PROBE` →
      `PROBE env uid=… / queryLauncherActivitiesForUser(user=0) -> 2xx`（应与 `pm list packages` 的量级一致）
- [ ] 未开双开时 `getAllUserIds result: [0]`（不应出现 999）；开启双开后才应出现 999 与分身子集
- [ ] 面板打开后 `MORE_PANEL_TREE … items=… letters=…` 的 items/letters **非 0**（真实内容而非空态）

## A1 安装即用
- [ ] 打开"气泡抽屉"，勾选**启用角落手势** → 弹悬浮窗授权 → 允许 → 返回自动开启服务（通知栏常驻）
- [ ] 通知点击回到主页，开关仍为开

## A2 角落手势与返回手势共存
- [ ] **应用内**（浏览器/微信/设置等前台）右角斜向上滑 → 气泡扇展开（此前只在桌面生效）
- [ ] **应用内**左角斜向上滑 → 展开方向朝右；系统返回仍正常
- [ ] 正上滑（返回）不被劫持：不出现"既返回又开扇"
- [ ] 若被系统手势抢走：扇静默收起，无残留（ACTION_CANCEL 路径）
- [ ] 设置页三滑杆（内缩/宽/高）调整后重启服务，起手势区域随之变化
- [ ] 触发位=侧边中部：左右边缘中段横滑出扇

### A2 日志判据（LSPosed 模块日志，tag `BubbleDrawer`）
- [ ] 启动即 `PREFS_APPLIED … transport=gesture-monitor`（写 `spy-view` 说明监视器没建起来，
      日志里 `MON_INPUT_UNAVAILABLE_FALLBACK_SPY_VIEW` 带原因）
- [ ] 一次成功手势：`MON_DOWN side=… x=… y=… mode=pre-own lat=N` → `MON_ACTIVATE …`
- [ ] 应用内**不再**出现 `MON_DOWN` 紧跟 `MON_SYSTEM_CANCEL … owned=true`（旧症状：
      `SPY_*_DOWN` +6~22ms `SPY_*_SYSTEM_CANCEL`；`owned=true` 表示这一笔被系统抢了回去）
- [ ] `dumpsys input` 的 `Gesture Monitors (implemented as spy windows)` 里有
      `[Gesture Monitor] BubbleDrawer-corner`

## A3 展开动效（对照 Flyme 录屏逐帧）
- [ ] 展开：整扇从角落飞出、-270°旋入带过冲（-7.6 控制点回弹），130ms 主体 + 130ms 延迟的 250ms 收尾
- [ ] 收起：先 100ms 侧移+微旋，再 100ms 延迟后 5°→-270° 旋回角落并缩到 0.8
- [ ] "更多"瓦片为原版 icon_gesture_more_app 图形（白圆环+三点）

## A4 瞄准与启动
- [ ] 手指在扇内移动：命中气泡出现白色波纹环（130ms），无图标放大
- [ ] 环带外松手 → 取消收起；气泡半径带内松手 → **全屏启动**该应用
- [ ] 快速朝角落方向甩出（>2500px/s）→ 取消手势（原版 onFling 语义）

## A4b 小窗启动与任务身份（同一应用只应有一个实例 / 一个小窗）
> 设置页"小窗启动"关闭 = 全屏；开启 = 小窗。依据：ROM 自己的小窗（侧边栏、WMShell linkage、
> 标题栏"小窗"）都只带 `NEW_TASK`，从不带 `MULTIPLE_TASK`；"新建小窗"是显式动作且有 2 个上限。
- [ ] 应用**未运行** → 从扇子打开 → 出现小窗（冷启动，正常）
- [ ] 该小窗开着 → 收起后再从扇子选**同一应用** → 还是**那一个**小窗被带到前台，没有第二个
- [ ] 应用在后台（先打开再回桌面）→ 从扇子打开 → 小窗里是**离开时那个界面**，不是重新冷启动的首页
- [ ] 应用在前台全屏 → 从扇子选同一应用 → 只出现一个小窗（不是"全屏一个 + 小窗里又一个实例"）
- [ ] 连续从扇子打开同一应用 3 次后：`adb shell dumpsys activity activities` 里该应用只有一个小窗任务
      （搜 `windowingMode=5` / freeform root task）
- [ ] 小窗之间的多实例仍按系统规则工作（不同应用各一个小窗；同一应用要第二个小窗只能走系统"新建小窗"）

## A5 "更多"面板（overlay 管理页）
- [ ] 气泡条尾部"更多"→ **当前窗口上方**浮出管理页 overlay（不再全屏新页），样式与固定管理页一致
      （56dp 仿 ActionBar：标题 + 管理/完成 + ✕；4 列网格、★区、A–Z、字母条都在）
- [ ] 面板**屏幕居中**；设置页"更多面板"四个滑杆（长/宽 = 占屏幕 %、图标 dp、文字 sp）改完下次开面板生效
- [ ] 点面板外部 → 关闭；轻遮罩 `#33000000` 随面板一起收
- [ ] 管理/完成可用：编辑态点击加/减固定、★区长按拖动排序，退出面板后顺序仍在
- [ ] 面板里点应用 → 面板收起，应用以**面板的位置/大小**开小窗（日志 `MORE_LAUNCH … rect=`）
- [ ] `adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_MORE` 可开关面板；
      `adb logcat -s BubbleDrawer` 有 `MORE_PANEL_SHOW [l,t][r,b] screen=… pct=… page=texts=[…]`
- [ ] 全屏管理页仍可从设置页进入，且 ActionBar 的"管理/完成"菜单在

## A6 固定管理
- [ ] 标题"选择快捷启动的应用"，tab"应用"，网格内点图标加固定（绿?角标=remove/add）
- [ ] 超过 6 个 → toast"最多固定 6 个"
- [ ] A–Z 分组：微信在 W、支付宝在 Z、QQ 在 Q、数字开头在 0 桶、符号在 #（最底）
- [ ] 右侧字母条：点字母滚动到组；滚动时当前字母同步
- [ ] ★ 区长按拖动排序；"管理/完成"切换后顺序持久化
- [ ] 固定后回主界面 → 角落滑动气泡条即时更新

## A7 持久与保活
- [ ] 杀后台后服务自启（START_STICKY）；重启手机后开关仍工作（首次需手动启动一次，二期加 BOOT）
- [ ] 固定列表重启不丢

## A8 小窗联系人条（Flyme「联系人头像」移植）
前置：设置页「实验性功能 → 小窗联系人条」默认开启。

**数据来源与 HyperOS 的关键差异**：头像/标题取自**通知**（Flyme 原版同样如此），但 HyperOS
在应用被打开时会清掉该应用的全部通知（任意方式打开，包括小窗）。因此模块把通知当"发现渠道"：
每条聊天通知都会写入一份**会话记忆**（标题 / 头像 / 点击意图 / 最近时间），通知被清掉后仍然保留
—— 否则小窗一出现列表就空了。记忆上限：每应用 12 条、总计 60 条、12 小时未再出现则过期。

- [ ] 先让微信来消息（通知栏有微信通知）→ 再把微信以小窗打开 → 小窗**正下方**浮出「头像 + 名称」条
      （名称 = 通知标题；日志 `CONTACT_BAR_DATA pkg=com.tencent.mm items=N live=0 remembered=N`：
      `live=0` 正是"打开即清通知"的证据，条靠 `remembered` 显示）
- [ ] 通知被清时能在日志看到 `CONTACT_BAR_NOTIF_REMOVED pkg=com.tencent.mm title=…`
- [ ] **圆角与小窗一致**（ROM 的 `getCornerRadius()`；日志里 `CONTACT_BAR_GEOM` 可对照），不是胶囊形
- [ ] **实时拉伸小窗**：拖右下角改尺寸时，条宽逐帧跟随，不出现错位/滞后（日志
      `CONTACT_BAR_GEOM … visual=… bar=… animating=true`，`bar` 的 width 始终 == `visual` 的 width）；
      变宽后能多显示几个头像
- [ ] **尺寸阈值**：设置页滑块（默认 60%，= 小窗宽度须达到屏幕宽度的 60%）—— 把小窗拖到比阈值更窄 → 条消失（`CONTACT_BAR_HIDE reason=TOO_SMALL`）；
      拖回来 → 条回来；滑到 0 → 不再按尺寸隐藏
- [ ] **上滑/下拉头像 → 移除该联系人**（`CONTACT_BAR_FORGET pkg=… title=…`）；移除最后一个后整条消失
      （`CONTACT_BAR_HIDE reason=EMPTY_AFTER_REMOVE`）；该会话来了**新消息**后应重新出现
- [ ] 小窗**移动**（拖顶部把手）时条跟着走
- [ ] **两个小窗并存**：操作另一个小窗 → 条消失（`CONTACT_BAR_HIDE reason=UNFOCUSED` /
      `CONTACT_BAR_LOST_FOCUS` 或 `CONTACT_BAR_FOCUS_SWITCH`）；**点回微信小窗 → 条回来**
      （`CONTACT_BAR_FOCUS_SWITCH` 或 `CONTACT_BAR_REGAINED_FOCUS`）。
      信号是 `MultiTaskingTaskRepository.updateFreeformTaskToTop`（任何小窗获得焦点都会触发）
- [ ] 小白条上滑 → **迷你小窗**：条立即消失（日志 `CONTACT_BAR_HIDE reason=MINI`）；
      从迷你恢复成普通小窗 → 条自动回来（`CONTACT_BAR_SHOW`）
- [ ] **关闭小窗动画期间不再跟随**：点 ✕ / 小白条上滑关闭的**那一刻**条就消失
      （`CONTACT_BAR_TASK_CLOSING` + `CONTACT_BAR_HIDE reason=CLOSING`），不跟着收起动画走
- [ ] 贴边（普通/迷你贴边，mode 2/3）不显示条
- [ ] 点某个头像 → 会话在小窗内打开。Flyme 的点击链逐字对齐：
      `pi.send(ctx,0,intent,null,null,null,options)` 失败 → `startActivityAsUser(intent, options)`。
      对应日志：`CONTACT_BAR_OPEN_PI`（首选）→ `CONTACT_BAR_OPEN_INTENT`（PI 已被微信随通知一起
      cancel，但 Intent 仍能启动 —— 这就是"时灵时不灵"的修复点）→ `CONTACT_BAR_OPEN_PLAIN` /
      `CONTACT_BAR_OPEN_FALLBACK`（最后兜底，退化为打开应用本体）
- [ ] **键盘弹出时不遮挡输入法**：窗口下方放不下就让到窗口上方，两处都让不开则隐藏
      （`CONTACT_BAR_HIDE reason=IME_NO_ROOM`），键盘收起后自动回来。信号来自
      `MiuiFreeformModeDisplayInfo.setImeVisibility`（日志 `IME_VISIBILITY showing=… height=…`），
      取不到时退化为每 300ms 问一次输入法服务
- [ ] 非 IM 应用（如抖音）小窗不显示条（`CONTACT_BAR_HIDE reason=NOT_IM_APP`）
- [ ] 小窗关闭后条消失（`CONTACT_BAR_HIDE reason=TASK_VANISHED`）
- [ ] 关闭设置开关 → 立即消失（`CONTACT_BAR_PREF_CHANGED enabled=false`）
- [ ] 新消息即时生效：小窗开着时来一条新微信 → 头像条刷新（未读红点出现在头像右上角）
- [ ] **锁屏/解锁不崩**（回归：`NamedListenerSet.remove` 曾因通知监听代理 `equals` 返回 null 抛 NPE）
- [ ] **Flyme 样式小窗：点小窗外关闭不崩**（回归：`SurfaceControlInputReceiver.onInputEvent` 返回 void/bool 混用，
      代理返回 null 会在 `InputEventReceiver.dispatchInputEvent` 拆箱 NPE）
- [ ] 真机自测小窗命令（比手势快）：`adb shell am start --windowingMode 5 -n <pkg>/<activity>`
      —— 注意这样拉起的是**较小**的 freeform 窗口（本机约屏幕宽 33%），默认 60% 阈值下会被
      `TOO_SMALL` 隐藏，属预期；要看条可用手势开正常小窗或把阈值调低
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr CONTACT_BAR`
      （启动时应有 `CONTACT_BAR_HOOKS_INSTALLED pipeline=true` 与 `CONTACT_BAR_NOTIF_LISTENER_ATTACHED`）

## A9 Flyme 样式轻量小窗（黑色遮罩 / 窗外点击关闭）
前置：设置页「实验性功能 → Flyme 样式轻量小窗」开启；`黑色遮罩` 与 `窗外点击关闭` 默认都开。
遮罩是**一块**全屏 `SurfaceControl`（挂在 RootTaskDisplayArea 下、按 z 序压在目标小窗之下），
所以控制器必须始终知道"现在哪一个小窗拥有它"——状态由 `windows`（taskId → 窗口对象）维护。
- [ ] 开一个小窗 → 窗外变暗 35%，点窗外 → 小窗关闭（`OUTSIDE_SURFACE_TAP_DISMISS taskId=…`）
- [ ] **两个小窗并存**：第二个小窗出现后**仍然有遮罩**，点窗外关掉的是**当前这个小窗**，
      剩下的那个小窗**遮罩回到它身上**（日志 `DIM_TARGET … reason=TO_FRONT|APPEARED_RAW`，
      切换时为 `DIM_FOCUS_SWITCH`，点外部关闭后是 `DIM_TARGET … reason=AFTER_OUTSIDE_CLOSE`）
- [ ] 点另一个小窗（或拖它的把手）→ 遮罩换到它下面（`DIM_FOCUS_SWITCH`）
- [ ] 小白条上滑 → 迷你小窗：遮罩立刻消失（`DIM_SUSPENDED reason=GESTURE_MINI`），
      迷你恢复成普通小窗后遮罩回来（`TASK_RESTORED_TO_FREEFORM … RETAKE TAKEOVER`）
- [ ] 关闭单个小窗的动画期间遮罩不再跟出来（`DIM_SUSPENDED reason=CLOSING`）
- [ ] 上滑悬停 → 交还系统原生小窗：不再有遮罩（`DIM_TAKEOVER_CANCELLED reason=SWIPE_UP_HOLD`），
      该小窗重新出现/恢复普通小窗后失效
- [ ] 关掉 `黑色遮罩` 而保留 `窗外点击关闭`：看不见遮罩但点窗外仍能关（alpha=0 的输入层）
- [ ] 两个开关都关 → 遮罩与点击都不生效（`DIM_HIDDEN reason=DISABLED`）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr /R "DIM_ SHOW_DIM BACKGROUND_SURFACE OUTSIDE_"`
      —— 正常情况下每 700ms 会重新压一次遮罩（自愈），日志只在状态变化时出现
      （`SHOW_DIM_SURFACE` / `DIM_HIDDEN` / `DIM_TARGET`），不会刷屏

## A10 扇形外观与时长（滑出振动 / 自动收起 / 图标大小 / 形状 / 分身角标）
入口：设置页「扇形面板外观」（图标数量 / 半径 / 滑出振动 / 不操作自动收起 / 图标大小 / 图标形状 / 分身角标）。
- [ ] **滑出时振动**（默认开）：从下角滑出、扇形**开始展开的那一帧**振一下（轻 tick，不是重按那种重 click）；
      关掉后同一动作完全无振动，图标悬停的轻 tick 仍在
- [ ] **不操作自动收起**（默认 10 秒，范围 0–30 秒）：滑出后手不点任何图标 → 到点自动收回
      （日志 `FAN_RETRACT_AUTO_TIMEOUT`）；手指还在滑动时不会中途消失（每个事件重新计时）；
      拖到最左「不自动收起」→ 一直停留，只有点选应用 / 点按面板外部 / 熄屏才收
- [ ] 点按外部、选中应用、熄屏三条既有收起路径不受影响（`FAN_RETRACT_VOID` / 选中即收 / `FAN_RETRACT_SCREEN_OFF`）
- [ ] **图标大小**（默认 44 dp，范围 32–64 dp）：拖动后扇形图标与选中光环一起变大变小，
      扇形半径不变、图标间不重叠；扇开着改动即时生效（`FAN_REBIND` 路径 = `rebindFanIfBusy`）
- [ ] **图标形状**：圆形（默认，Flyme 原样）↔ 系统样式圆角矩形；后者图标与选中光环都变成圆角方形
      （圆角半径 = 图标边长 × 0.225，与桌面图标观感一致）
- [ ] 切换形状后点选/悬停/翻页都正常（光环跟手、无残影）
- [ ] **分身角标**（默认开，见 A17）：双开应用的图标**图本身**与主空间那个应用完全一样，
      只在右下角多一个分身角标；关掉后两者像素级一致（扇子、更多面板、管理页三处都变）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr FAN_`（`FAN_RETRACT_*`、`FAN_PRESSURE_PAGE_TURN`）；
      分身图标来源：`adb logcat -s MeiWindow_AppRepo | findstr CLONE_ICON`

## A11 小窗内横屏（窗口必须落在屏幕内）
Flyme 样式下小窗的大小 = 一个缩放系数 × ROM 自己的**未缩放**任务 bounds（`MiuiMultiWindowUtils.getPossibleBounds`）。
这个 bounds 与方向有关：竖屏窗口宽 ≈ 短边 × ratio，横屏窗口宽 ≈ 短边 × ratio × 屏幕长宽比
（本机 1220×2656 → 约 2.18 倍）。所以同一个「小窗大小」设置在横屏时会撑出屏幕；而 ROM 的旋转路径
（`MiuiFreeformModeAnimation.startFreeformOrientationChangeShellTransition`）只会把窗口**平移**回屏内，**不会**缩小它。
现在生效系数取 `min(设置值, ROM 自己 reviewFreeFormBounds 后的值)`——后者恰好是"装得下"的上限，
竖屏时两者相等（设置值原样生效），横屏时自动收到屏幕宽度。
- [ ] 小窗里点应用的横屏按钮（视频全屏 / 游戏）→ 窗口变成**横屏条**、居中、不超出屏幕
      （宽 ≈ 屏幕宽 − 两侧 6dp，高 = 宽 ÷ 屏幕长宽比）
- [ ] 再转回竖屏 → 回到原来的竖屏大小与居中位置，不需要重开小窗
- [ ] 横屏时黑色遮罩仍铺满全屏、点窗外仍能关闭（A9 的判据不受影响）
- [ ] 横屏时联系人条贴着窗口下边、宽度跟着窗口走，不超出屏幕
- [ ] 直接以小窗打开一个横屏应用（游戏）→ **同样落在屏幕内并且居中**（上下也居中）
      —— ROM 自己只会把横屏小窗横向居中、并且把它**顶到屏幕最上方**
      （`getTopMargin` 在竖屏显示下直接返回 0），所以居中一律由模块按"装得下"的系数算
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr FREEFORM_SCALE`
      —— `target` = 设置值，`rom` = ROM 按屏幕算出的上限，`used` = 实际生效值；
      `clamped=true` 表示这次被上限压小了（横屏正常就是 true，竖屏应为 false）
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr CENTERED_BOUNDS`
      —— `scale` 是模块用来摆放的系数，`src=` 说明它是从哪来的（`rom` = ROM 自己给的，`assumed` = ROM
      review 过的设置值，`setting` = 裸设置值），`visual=(宽x高)` 是窗口在屏上的大小；
      横屏应满足 `visual 宽 ≤ 屏幕宽`、且 `rect.left/top` 都是正数（居中而非贴边）
- [ ] **摆放用的系数必须等于 ROM 真正使用的系数** —— 这是「设置了居中却看起来没居中」的头号原因：
      窗口是按**未缩放** bounds 定位、再乘系数绘制的，系数错一点，窗口本身就偏半格。
      日志里 `applied ≠ assumed` 说明这台 ROM **没有**按模块的设置值给小窗定尺寸
      （`MiuiFreeformLaunchConfig.getFreeformArg` / `getFreeFormScale` 这条链没接上），
      这时必须以 `applied` 为准（`src=rom`）；两个数值相等时说明设置值本来就是它用的值。
      `applied` 的来源：迷你→普通恢复取 `getRestoredBounds` 的返回值（`singleTap` 原样把它交给
      `setAnimParam`），启动/切换路径取模块自己 `getFreeFormScale` 钩子给 ROM 的那个答案
- [ ] 出现 `CENTER_SCREEN_MISMATCH` 说明 `Resources.getSystem().displayMetrics` 与 ROM 的
      `getDisplaySize`（= `windowConfiguration.getMaxBounds`）不是同一个尺寸 —— 模块已统一改用 ROM 的值
      （两条居中路径都受影响，所以只差这一点也会整体偏）

## A12 禁止小窗偏移（第二个小窗仍开在同一个位置）
HyperOS 的「错开」在 `MiuiMultiWindowUtils.avoidIfNeeded:1807`（手机走这一支）：新小窗与已有小窗
重叠时，把它挪到 `已有窗口.left + FREEFORM_RECT_OFFSET_X_ZIZHAN` / `.top + FREEFORM_RECT_OFFSET_Y_ZIZHAN`
（:319-320 = **78dp / 44dp**，即"往右下偏一点"）。所有避让都汇聚在 void 且原地改参的
`avoidAsPossible(Rect, Rect, Rect)` 上（启动 `getFreeformRect:1461` / `getCustomFreeformRect:1541`、
全屏→小窗 `MulWinSwitchInteractUtil:104`、迷你→普通恢复 `MiuiFreeformModeMiniStateHandler:276`
与 `MiuiFreeformModePinHandler:953`），所以关掉它一处即可。
入口：实验性功能 → 窗口尺寸与位置 → **禁止小窗偏移**（**默认关**）。
**关键**：ROM 的避让在 `getCustomFreeformRect` *内部*，而模块的「小窗居中」在那个方法*返回之后*重新
摆放窗口 —— 所以居中一开，ROM 那次避让就被覆盖，关着开关也看不到偏移（用户实测到的现象）。
因此关掉开关时，模块在居中之后**自己再调用一次 ROM 的 `avoidAsPossible`**，把避让补回来
（方向与屏幕内夹取沿用 ROM 的逻辑），日志 `CENTERED_BOUNDS_AVOIDED … delta=(dx,dy)`。
- [ ] **保持关闭**（默认）+ 居中默认开：开一个小窗 → 再开第二个 → 第二个相对第一个**往右下错开**
      （本机约 右 103px、下 143px；`CENTERED_BOUNDS_AVOIDED delta=(…)`，`delta` 为 0 时说明两个窗口
      没有重叠，此时本来就无需避让）
- [ ] **打开开关**：同样操作 → 两个小窗**位置完全重合**（日志 `FREEFORM_OFFSET_SKIPPED`）
- [ ] 开关是热切换：改完直接再开小窗即生效，不需要重启系统界面（设置值就在 `cfg.xml` 里）
- [ ] **关掉 Flyme 样式小窗总开关**后重复前两项：打开=重合、关闭=往右下错开
      （本开关**不依赖**总开关：它压的是 HyperOS 自己的避让，原生小窗模式同样生效）
- [ ] 迷你小窗恢复成普通小窗时同样按开关处理（关=错开，开=重合）
- [ ] **迷你小窗点击还原**（`TASK_RESTORED_TO_FREEFORM`）落在屏幕中央：日志有
      `CENTERED_BOUNDS rect=…`；**只有一个窗口时不应出现 `CENTERED_BOUNDS_AVOIDED`** ——
      避让的"另一个小窗"只认 shell 仓库里**还活着**、且不是正在摆放的那一个
      （`MultiTaskingTaskRepository.getFreeformTasksInZOrder` → `getMiuiFreeformTaskInfo`，
      日志尾部带 `src=shell|tracked`）；跳过的情形记
      `CENTERED_BOUNDS_AVOID_SKIPPED reason=SELF_OR_SAME_PACKAGE|NO_OTHER_WINDOW`。
      早期版本拿遮罩跟踪的那个窗口去避让，而它可能是**已经关掉**的窗口（位置恰好和新窗口重叠，
      因为新小窗就开在默认位置上），于是新窗口被推离居中位置 78dp/44dp —— 这就是
      「OS3 上小窗居中不生效」（`activeTaskInfo` 只在下一次 focus 事件才更新，`onTaskVanished`
      过去并不清它）。
- [ ] **迷你→普通恢复还有第二道门**：`MiuiFreeformModeMiniStateHandler.singleTap` 的顺序是死的 ——
      先 `getRestoredBounds(taskInfo, rect)`（模块在这一步居中），紧接着
      `avoidOtherFreeformTaskIfNeed(taskInfo, rect, scale)` 原地改**同一个 `Rect` 对象**。
      这是唯一一个「模块写完之后 ROM 还会改写」的摆放点（启动/切换路径的避让都在 ROM 方法*内部*，
      也就是模块居中*之前*）。模块在上一步已经自己做过这个决定（`avoided=true`，或「禁止小窗偏移」
      明确要求不偏移），所以那一次避让被跳过，日志
      `RESTORE_AVOID_SKIPPED rect=… — the module's own placement stands`；模块没替 ROM 做决定的场合
      （开关关着、且模块自己也没算出偏移）仍然由 ROM 说话。
      判定：有第二个普通小窗时，还原后的窗口要么与它错开（开关关）、要么与它**重叠但居中**（开关开），
      都不应该"既重合又偏半格"
- [ ] 迷你小窗自己的排布（同侧迷你自动排队）、贴边/侧边栏避让不受影响
- [ ] 全屏 → 小窗切换（小白条上滑悬停等）与直接打开的表现一致

## A13 小白条上滑 → 迷你小窗（HyperOS 3 与 4 走的不是同一套代码）
> 先看一条**通用**前提：`adb install -r` 会触发 LSPosed 热重载（`autoHotReload`）。热重载会把上一代的
> **所有** hook 摘掉，而模块过去只在那条路径里重建「角落手势监视器」，**没有重装方法 hook** —— 于是
> 每次装完新包，Flyme 样式小窗的整套 hook（黑色遮罩 / 小窗居中 / 小白条手势 / 联系人条 / IME）都会
> 静默失效，直到系统界面重启。已修（`SystemUiHookInstaller.reinstall` 现在会重跑
> `FlymeFreeformController.installHooks`），判断依据是安装后立刻能在日志里看到
> `HOOK_INSTALLED_BoundsCentering rect=… mini=…`：**没有这行就说明当前进程里 hook 是死的**。

这个手势在 ROM 里两边不一样，模块因此有两条路径：

- **HyperOS 4**：`MiuiFreeformModeMoveHandler.onBottomCaptionHandleMotionEvents` 的 ACTION_UP 里有
  **迷你分支**，条件是 `|velocity| < 1000 && getDestinationScaleX() <= getMiniBottomUpLimit() + miniScale
  && !isLastTargetIsFullscreen() && deltaY < 0`。模块的做法是**操纵输入**：把 `getVelocity()` 归零、
  把 `getMiniBottomUpLimit()` 抬到 999（`hookVelocityMonitor` / `hookMiniBottomUpLimit`），让 ROM 自己
  走那条分支；状态转换（mode=1、迷你系数、shell transaction）全是 ROM 干的，模块只把 release 动画的
  类型从 2 改成 11（官方迷你动画）并把目标摆到迷你位置。
- **HyperOS 3**：ACTION_UP **只有**三条出口 —— 关闭（`deltaY < -80 && velocity < -1000` 或系数 < 0.35）、
  全屏（`deltaY > 80` 或超过阈值）、**回弹**，**没有迷你分支**（整个 dex 里也搜不到
  `triggerMiniThreshold` / `getMiniBottomUpLimit`）。所以上面那套"操纵输入"在 OS3 上等于没做任何事：
  用户看到的就是"上滑一下，窗口弹回原位"。

OS3 上模块改为调用 ROM 自己的转换入口 `MiuiFreeformModeController.fromFreeformToMini(int)`
（ROM 的「控制中心」进迷你小窗走的就是它：mode=1 + 迷你系数 + 侧边落位 + WCT + 类型 11 动画 + 避让）。
它有两道门槛，所以实现是**重试**而不是调用一次：

1. 窗口在做连续动画时它直接 return（`isRunningContinuousAnimation()` = 动画类型 1/2/3 还在表里，
   而底部小白条拖拽登记的正是 2）。这个标记由 `proceed()` 启动的 release 动画结束时清掉，何时清掉
   从外面看不到 —— 于是每 60ms 试一次，直到 `onTaskModeChanged` 报出 mode=1 为止。
   （`executeOnShell` 是异步派发，所以"试一次"= 派发一次调用，成没成由那个回调判定。）
2. 万一 release 动画永远不上报完成（标记一直挂着），第 10 次（≈600ms）时模块**只清这一个标记**
   （`removeRunningAnimationType(2)`），让 ROM 的转换接手 —— 这一步在日志里是 `MINI_FALLBACK_UNBLOCK`。

- [ ] OS3：小窗上滑小白条 → **变成侧边迷你小窗**（窗口收缩并落到左上/右上角）
- [ ] OS3 日志判据：`ACTION_UP_SWIPE_MINI taskId=… dy=…` → `MINI_SWIPE_FALLBACK taskId=…` →
      `MINI_SWIPE_CONVERTED_BY_ROM taskId=… old=0 new=1` → `MINI_FALLBACK_CONVERTED taskId=… afterMs=…`
      （`afterMs` = 等 release 动画花了多久；若 `afterMs` 很小，说明窗口是直接滑过去的，
      若接近 300~600ms，说明它先弹回原位再贴边 —— 想消掉这段回弹再做一版）
- [ ] OS4 回归：同一手势必须是 `ACTION_UP_SWIPE_MINI` → `MINI_SWIPE_CONVERTED_BY_ROM` →
      `MINI_SWIPE_NATIVE`，**不应**出现 `MINI_SWIPE_FALLBACK` / `MINI_FALLBACK_*`
- [ ] 启动时一眼看出 ROM 走哪条：`HOOK_INSTALLED_MiuiFreeformModeTaskInfo.getMiniBottomUpLimit count=1`
      = OS4（有原生迷你分支）；`HOOK_SKIPPED_MiuiFreeformModeTaskInfo.getMiniBottomUpLimit` = OS3
      （没有，由模块驱动）。两样都不该出现的是 `MINI_CONVERSION_NOT_ON_THIS_ROM`／
      `MINI_FALLBACK_UNAVAILABLE`（这台 ROM 连转换入口都没有 —— 请把日志发我）
- [ ] 迷你小窗恢复成普通小窗后，黑色遮罩与联系人条照常回来（A8/A9 的判据）
- [ ] OS3 上「上滑悬停 → 交还原生小窗」与「下滑 → 全屏」不受影响
- [ ] 快捷判据：`adb logcat -s BubbleDrawer | findstr /R "MINI_"`

## A14 顶部「重启系统界面」按钮（需要 root）
入口：主页（魅窗）右上角的 miuix `Refresh` 图标。
用途：模块的 hook 都活在 SystemUI 进程里 —— `adb install -r` 触发的 LSPosed 热重载会丢弃上一代
所有 hook，只重建模块自己的运行时（`SystemUiHookInstaller.reinstall`），进程里那些"只在启动时读一次"
的状态也不会重来。想从干净进程复现问题、或让改动立刻生效，按这个比重启手机快。
实现：`SystemUiRestart` 走 `su -c`，命令依次尝试 `killall` → `pkill -f` → `pidof` + `kill -9`
（toybox 三个都有，本机实测 `su -c "pidof com.android.systemui"` 直接返回 pid）。
- [ ] 有 root：点击 → toast「正在重启系统界面…」→ 状态栏/导航栏消失约一秒后回来；
      `adb logcat -s BubbleDrawer` 出现 `SYSTEMUI_RESTART exit=0`，随后 SystemUI 进程 id 变化
      并且重新打出整串 `HOOK_INSTALLED_*` / `FLYME_FREEFORM_CONTROLLER_STARTED`
- [ ] 无 root（或拒绝授权）：toast「重启失败：需要 root 权限」，SystemUI 不动
      （日志 `SYSTEMUI_RESTART exit=…` 非 0）
- [ ] 重启后设置页、扇子、小窗功能一切照旧（模块在 SystemUI 启动时重新装 hook）

## A15 诊断页（没有 adb 的测试机也能定位）
入口：主页（魅窗）→ **实验性功能**下面一行 **诊断**（`DiagnosticsScreen`）。
它自己读模块日志、给出"哪些成功、哪些有问题"，不需要电脑：`ModuleLogSource` 走 `su -c`
（与 A14 同一个 root 前提，第一次会弹授权框）读两个来源 ——

1. `logcat -d -t 4000 -s BubbleDrawer`（现场，最新，但 ring buffer 会滚）；
2. `/data/adb/lspd/log/modules_*.log` 最新一个的尾部（LSPosed 的模块日志，重启进程也不会丢）。

拼接顺序是"持久日志在前、logcat 在后"，所以分析器里"最后一行"永远等于"最近发生的事"——
`HOT_RELOADED` 那类需要判断先后的结论才不会读反。判定逻辑在 `ModuleLogDiagnosis`（纯函数，单测
`ModuleLogDiagnosisTest` 里用的就是真机日志的原文），每一项给出 正常 / 注意 / 有问题 / 信息
+ 一句话原因 + **原始证据行**。

- [ ] 打开诊断页 → 顶部结论卡（"全部正常" / "N 项有问题"）+ 下面按组列出每一项
- [ ] 有 root：第一次弹授权框；允许后 `adb logcat -s BubbleDrawer` 出现 `DIAGNOSIS su-missing`（若没有 su）
      或直接读到内容；拒绝授权 → 页面显示"root 命令没有返回内容…"，**不转圈**
- [ ] **复现一次问题再回来重新诊断**（右上角刷新，或「操作 → 重新诊断」）：结论会变
- [ ] 判据自检：`CENTERED_BOUNDS rect=…` 那一行的落点必须等于 `(屏幕 − 可见尺寸) / 2`，
      差超过 2px 就报"不在屏幕中央"并给出 `偏 (dx, dy) px` —— 这是"设置了居中却没居中"的
      直接证据，页面不用人去心算
- [ ] `applied ≠ assumed` 会报"这台 ROM 的尺寸不是设置值决定的"（配合上面「尺寸配置 hook」那项）
- [ ] 若安装后发生过热重载且没重装 hook：报"hook 是死的"，并在同一页给出「重启系统界面」入口
- [ ] 「复制诊断结果」→ 文字进剪贴板（含结论、每项原因、关键原文，已裁掉 LSPosed 前缀），
      可直接粘贴发人
- [ ] 界面里改了开关后，结论里"系统界面读到的设置"应能反映出来（不一致会直接报出来：
      设置没同步 = 功能静默失效）
- [ ] 多了 **【边角手势】** 一组：采集通道（手势监听 / 降级 SPY 窗口）、最近一次角落按下
      有没有抢下指针流（`mode=pre-own` + 用时 ms）、抢下后有没有被系统夺回（见 A16）

## A16 角落屏蔽手势（回桌面不再和扇面一起出现）
现象（用户报告）：重启系统界面之后，或者很久没划角落再划时，`全面屏手势（回桌面）`会和扇形面板
**一起**弹出。

成因（设备实测）：角落那一笔必须在 **MiuiHome 的原生识别器之前**把指针流抢过来
（`InputManagerService: pilferPointers` 落在触摸后约 2ms；晚一点 launcher 就
`app requestRedirect` 接管了，而它的 HOME 是"看最初几个向上采样"就决定的，事后收不回来）。
所以 DOWN 这条路线上任何会阻塞的东西都在赌这一笔：

- `WindowManager.addView`（键盘高度探针窗口）曾在第一个角落 DOWN 里现场创建 —— 每次 SystemUI
  启动后**第一笔**角落手势必然输掉这场竞赛，表现就是"重启系统界面后第一下会回桌面"；
- 主线程队列（SystemUI 启动、扇面/联系人条/面板的窗口工作）曾排在 DOWN 回调前面；
- `PowerManager` / `KeyguardManager` 的 binder 调用、`WindowManager` 指标读取都在抢之前。

现在的实现：探针窗口在手势通道启动时建好（`startMonitorTransport`，主线程、与手指无关；功能关掉
时随通道一起移除）、insets 监听维护缓存；几何与电源/锁屏状态都是缓存字段（配置变化 / 亮灭屏 / 锁屏
事件 + 输入线程上看门狗：**亮屏 250ms、熄屏 5s** —— 这是本功能唯一不靠手指触发的开销，熄屏慢下来
既省唤醒又不影响判定）、DOWN 回调跑在 `bubbledrawer-corner-input` 专用线程
（`THREAD_PRIORITY_URGENT_DISPLAY`），启动时 `MON_WARMUP` 预热一遍，抢占的判定区也改成
`max(28dp, 触发区自身的纵向半径)`（此前触发区高于 28dp 的那部分按下只是"观察"，同样会被 launcher
抢走 HOME）。

- [ ] **重启系统界面**（A14 的按钮，或 `adb shell su -c "killall com.android.systemui"`）→
      等界面回来 → **第一笔**右下角内滑：只出扇面，**不回桌面**；左上/左下角同理
- [ ] 很久不用（或熄屏一段时间后）再划角落：同样只出扇面
- [ ] 角落轻点（不滑动）仍然是被点的那个应用收到点击（键盘弹着的时候更要试：
      左下角按键只输入一次）
- [ ] 角落**外**的底部上滑（屏幕中间、离角 100dp 以上）依旧正常回桌面 —— 屏蔽只发生在触发区内

### A16 日志判据
- [ ] 每一笔 `MON_DOWN … band=true ime=0px mode=pre-own lat=N`：`lat` 是个位到十几毫秒
      （真机实测正常 2ms；`lat` 超过 8ms 诊断页会开始提示"偏慢"）
- [ ] 紧接着 `MON_PILFER_RETRY ok=true lat=…`（这一笔在 Activate 时又抢了一次 ——
      DOWN 那次是抢到了的；**整笔没有这条**说明 DOWN 根本没抢）
- [ ] 这一笔之后**没有** `MON_SYSTEM_CANCEL … owned=true`（有就是"被 launcher 抢回去了"，
      扇面和桌面会一起出现）
- [ ] `MON_INPUT_READY … thread=bubbledrawer-corner-input`（不再是主线程）
- [ ] `MON_WARMUP rounds=24 ms=… cur=… max=…` 在启动时就出现（几何缓存已就绪）
- [ ] 重启后若探针窗口没建起来会有一条 `MON_IME_PROBE_MISSING`（那时键盘上的角落按下会被抢下，
      属于已知降级）

## A17 分身应用（双开）的图标与角标
做法：分身应用的**图本身永远取主空间那张** —— `AppRepository.mainSpaceIcon(pkg)`：先问
`LauncherApps.getActivityList(pkg, user 0)`（桌面上画的那张，含自适应/主题图标），拿不到再退
`PackageManager.getApplicationIcon`。分身用户只决定要不要在这张图上**叠角标**
（`RemotePrefs.KEY_CLONE_BADGE`，设置页「扇形面板外观 → 分身角标」，默认开）：

- XSpace（user 999）：先交给 MIUI 自己的 `XSpaceUserHandle.getXSpaceIcon(context, base)` ——
  它的入参就是上面这张主空间图（MIUI 的双开角标是叠上去的），拿不到才用模块自己画的橙色双环；
- 其它用户配置（工作资料等）：`PackageManager.getUserBadgedIcon(base, handle)`（系统角标同样是叠图）；
- 关掉开关：直接返回主空间那张，和主应用像素级一致。

1.1.8 及以前不是这样：分身图标先走 `LauncherActivityInfo.getBadgedIcon(dpi)`，那是**分身用户自己那份
资源** —— MIUI 预烤的双开图标（另一套美术/尺寸），于是同一个应用在扇子里看着像两个应用。

- [ ] 开双开后：主应用与分身应用在扇子里**图一样**，只有右下角多一个角标
- [ ] 设置页关掉「分身角标」→ 两者完全一致（扇子、更多面板、管理页三处都要看）
- [ ] 开关来回切两次都生效（缓存键里带开关，切换后重新解码，不必重启界面）
- [ ] 主空间那个应用的图标不受影响（`CLONE_ICON` 只对 user≠0 打）
- [ ] 日志：`adb logcat -s MeiWindow_AppRepo | findstr CLONE_ICON` → `badge=true src=main-space+badge`；
      SystemUI 侧另有 `PREFS_APPLIED … icon[cloneBadge=true]`

## 贴边模式（可选，root）
- [ ] 设置页"贴边模式"：无 su → toast 需要 root；有 su → inset=0，正上滑角落也能出扇
