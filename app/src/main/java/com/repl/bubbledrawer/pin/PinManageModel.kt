package com.repl.bubbledrawer.pin

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * State holder behind the pin-manager page. ONE instance is created per host
 * (PinManageActivity / FanHost's overlay panel) and survives recomposition; the composable
 * reads it through `collectAsStateWithLifecycle`-compatible observable fields
 * (docs/ui-guidelines.md "Flow 收集" + "强跳过友好的状态形状").
 *
 * The cell list is REMEMBERED OUTSIDE the scroll content lambda and keyed on
 * (apps, pins): the original recomputed `Sections.build` on every adapter pass; with the
 * grid this caching is what keeps scroll cheap (same rationale as the guidelines'
 * "排序 + 分块结果必须在 LazyColumn 之外缓存").
 */
class PinManageModel(
    private val loadApps: suspend () -> ImmutableList<BubbleApp>,
    private val pinsOf: () -> List<PinnedRef>,
    /** Persisted on drag end / unpin. */
    private val onOrderChange: (List<PinnedRef>) -> Unit,
    private val recommendConfig: () -> Pair<Boolean, Int> = { true to 8 },
    private val loadRecommendations: (suspend (List<BubbleApp>, Set<String>, Int) -> List<BubbleApp>)? = null,
    /**
     * 分身角标开关（[com.repl.bubbledrawer.xposed.RemotePrefs.KEY_CLONE_BADGE]）—— 由宿主读设置后传进来：
     * 面板跑在 SystemUI 里（LSPosed 远程组），管理页跑在应用进程（本地镜像）。图标本身永远取主空间
     * 那张，这一项只管要不要叠角标。
     */
    val cloneBadge: Boolean = com.repl.bubbledrawer.xposed.RemotePrefs.DEFAULT_CLONE_BADGE,
    private val loadShortcuts: (suspend () -> Pair<List<com.repl.bubbledrawer.shortcut.ShortcutItem>, List<com.repl.bubbledrawer.shortcut.ShortcutItem>>)? = null,
    initialTab: Int = 0,
    private val functionPinsOf: () -> List<String> = { emptyList() },
    private val onFunctionOrderChange: (List<String>) -> Unit = {},
) {
    /** 0 = 应用 (Apps), 1 = 功能 (Shortcuts / Features) */
    var selectedTab: Int by mutableStateOf(initialTab)
        private set

    fun selectTab(index: Int) {
        if (selectedTab != index) {
            selectedTab = index
        }
    }

    var functionPins: List<String> by mutableStateOf(emptyList())
        private set

    fun refreshFunctionPins() {
        functionPins = functionPinsOf()
    }

    fun isFunctionPinned(item: com.repl.bubbledrawer.shortcut.ShortcutItem): Boolean =
        functionPins.contains(item.pinKey)

    fun toggleFunctionPin(item: com.repl.bubbledrawer.shortcut.ShortcutItem) {
        val key = item.pinKey
        val next = if (functionPins.contains(key)) functionPins - key else functionPins + key
        // 同 [togglePin]：刚写下的这一份就是权威。写入会同步触发 prefs 监听（值 + 戳两个 key 在
        // 同一个文件里），监听者回头再读一次可能拿到撕裂的那一对，于是刚点的这一下被旧值顶掉 ——
        // 用户看到的是「第二次开始点了没反应」。写回之后这里再断言一次，页面与手一致。
        functionPins = next
        onFunctionOrderChange(next)
        functionPins = next
    }

    var appShortcuts: List<com.repl.bubbledrawer.shortcut.ShortcutItem> by mutableStateOf(emptyList())
        private set

    var desktopShortcuts: List<com.repl.bubbledrawer.shortcut.ShortcutItem> by mutableStateOf(emptyList())
        private set

    val allShortcuts: List<com.repl.bubbledrawer.shortcut.ShortcutItem>
        get() {
            val list = mutableListOf<com.repl.bubbledrawer.shortcut.ShortcutItem>()
            val seen = mutableSetOf<String>()
            for (item in appShortcuts + desktopShortcuts) {
                if (seen.add(item.pinKey)) {
                    list.add(item)
                }
            }
            return list
        }

    /** 需求 3：从扇形面板打开的更多应用，只显示用户手动添加的功能 */
    val pinnedFunctions: List<com.repl.bubbledrawer.shortcut.ShortcutItem>
        get() {
            val allMap = allShortcuts.associateBy { it.pinKey }
            val list = functionPins.mapNotNull { allMap[it] }
            if (!isSearching || searchQuery.isBlank()) return list
            val q = searchQuery.trim()
            return list.filter {
                it.label.contains(q, ignoreCase = true) || it.subLabel?.contains(q, ignoreCase = true) == true
            }
        }

    /**
     * 拼音排序键缓存。
     *
     * 这些 getter 在一次组合里会被读好几遍（列表、字母条、空态判断…），而 `AppSortKey.of` 是
     * 一次汉字→拼音转换 —— 不缓存就是同一个应用名反复转换；面板里那还是 SystemUI 的主线程。
     */
    private val functionSortKeys = HashMap<String, String>()

    private fun functionSortKey(appName: String): String =
        functionSortKeys.getOrPut(appName) { com.repl.bubbledrawer.pinyin.AppSortKey.of(appName) }

    /** 需求 5：以应用名称为小标题作为分类，应用名称使用拼音排序 */
    val groupedFunctions: List<AppFunctionGroup>
        get() {
            val query = if (isSearching) searchQuery.trim() else ""
            val groups = mutableMapOf<String, MutableList<com.repl.bubbledrawer.shortcut.ShortcutItem>>()
            for (item in allShortcuts) {
                val appName = item.subLabel?.ifBlank { null } ?: item.label
                if (query.isNotEmpty() && !item.label.contains(query, ignoreCase = true) && !appName.contains(query, ignoreCase = true)) {
                    continue
                }
                groups.getOrPut(appName) { mutableListOf() }.add(item)
            }
            return groups.map { (appName, items) ->
                AppFunctionGroup(
                    appName = appName,
                    sortKey = functionSortKey(appName),
                    items = items,
                )
            }.sortedBy { it.sortKey }
        }

    val functionLetters: List<String>
        get() {
            val list = mutableListOf<String>()
            list.add("★")
            val lettersFromGroups = groupedFunctions.map { group ->
                val ch = group.sortKey.firstOrNull()?.uppercaseChar() ?: '#'
                if (ch in 'A'..'Z') ch.toString() else "#"
            }.distinct()
            list.addAll(lettersFromGroups)
            return list.distinct()
        }

    /** false = view mode (tap launches); true = edit mode (tap pins, long-press drags). */
    var manageMode: Boolean by mutableStateOf(false)
        private set

    var cells: List<Cell> by mutableStateOf(emptyList())
        private set

    var apps: ImmutableList<BubbleApp> by mutableStateOf(persistentListOf())
        private set

    /** Index-bar letters, display order (★ first — original marks it ★, SlideLaunchAppSettings :326). */
    var letters: List<String> by mutableStateOf(emptyList())
        private set

    /** Search state. */
    var isSearching: Boolean by mutableStateOf(false)
    var searchQuery: String by mutableStateOf("")

    fun startSearch() {
        isSearching = true
        searchQuery = ""
    }

    fun cancelSearch() {
        isSearching = false
        searchQuery = ""
    }

    /**
     * 这一页当前的收藏集合（`pkg#userId`）。
     *
     * 之前 [isPinned] 每格都调一次 `pinsOf()` —— 一次页面构建就是几百次**和解读**（每次都可能把
     * 落后的一方写回共享组），把刚写下的编辑用旧值顶掉的机会也被放大几百倍（2026-10-12
     * 「收藏自动反复添加/移除」）。收藏列表在一次构建里本来就是同一份，这里在 [rebuild] 时取一次。
     *
     * 还没构建过时（快照为 null）退回现读：那种状态下页面是空的，不会有人来问。
     */
    private var pinnedKeys: Set<String>? = null

    fun isPinned(app: BubbleApp): Boolean =
        (pinnedKeys ?: pinsOf().map { "${it.packageName}#${it.userId}" }.toSet())
            .contains("${app.packageName}#${app.userId}")

    val displayedCells: List<Cell>
        get() {
            if (!isSearching || searchQuery.isBlank()) {
                return cells
            }
            val q = searchQuery.trim()
            val matched = apps.mapNotNull { app ->
                val rank = searchRank(app, q)
                if (rank > 0) app to rank else null
            }.sortedWith { (a, rankA), (b, rankB) ->
                if (rankA != rankB) rankA.compareTo(rankB)
                else {
                    val lenCmp = a.label.length.compareTo(b.label.length)
                    if (lenCmp != 0) lenCmp
                    else if (b.usageCount != a.usageCount) b.usageCount.compareTo(a.usageCount)
                    else a.sortKey.compareTo(b.sortKey)
                }
            }.map { it.first }

            if (matched.isEmpty()) {
                return listOf(Cell.Label("无搜索结果"))
            }
            return listOf(Cell.Label("搜索结果")) + matched.map { Cell.App(it, "搜索结果") }
        }

    /**
     * 搜索排序优先级：
     * 1: 首字匹配 (app.label.startsWith)
     * 2: 拼音首字匹配 (app.sortKey.startsWith 或 app.initials.startsWith)
     * 3: 字符包含 (app.label / sortKey / initials 包含)
     * 4: 包名包含 (app.packageName 包含)
     * 0: 不匹配
     */
    fun searchRank(app: BubbleApp, query: String): Int {
        if (app.label.startsWith(query, ignoreCase = true)) return 1
        if (app.sortKey.startsWith(query, ignoreCase = true) ||
            app.initials.startsWith(query, ignoreCase = true)) return 2
        if (app.label.contains(query, ignoreCase = true) ||
            app.sortKey.contains(query, ignoreCase = true) ||
            app.initials.contains(query, ignoreCase = true)) return 3
        if (app.packageName.contains(query, ignoreCase = true)) return 4
        return 0
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Fired once [cells]/[letters] have actually been (re)built.
     *
     * The host used to log `describe()` right after [reload] and one frame later — both
     * BEFORE the load coroutine finished, so the "ground truth" line always read
     * `items=0 letters=0` even on a page full of apps (device log 2026-10-09: panel visibly
     * complete while every MORE_PANEL_TREE line said 0). The rebuild is the only moment the
     * page content is known, so that is where the host is told.
     */
    var onContentChanged: (() -> Unit)? = null

    @MainThread
    fun reload() {
        scope.launch {
            apps = loadApps()
            rebuild()
            functionPins = functionPinsOf()
            loadShortcuts?.let { loader ->
                runCatching {
                    val (appsList, desktopList) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        loader()
                    }
                    appShortcuts = appsList
                    desktopShortcuts = desktopList
                }
            }
        }
    }

    @MainThread
    fun refreshShortcuts() {
        scope.launch {
            loadShortcuts?.let { loader ->
                runCatching {
                    val (appsList, desktopList) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        loader()
                    }
                    appShortcuts = appsList
                    desktopShortcuts = desktopList
                }
            }
        }
    }

    /**
     * Re-read only the pins (no app-list pass). The SystemUI panel writes pins through the
     * handoff while this page may already be open — without this the app's 收藏 kept the
     * pre-edit list on screen next to a panel that showed the new one.
     */
    @MainThread
    fun refreshPins() {
        refreshFunctionPins()
        rebuild()
    }

    @MainThread
    fun toggleManageMode() {
        manageMode = !manageMode
        if (!manageMode) persistOrder()
        rebuild()
    }

    fun managerLabel(complete: String, manage: String): String = if (manageMode) complete else manage

    /** ★ drag reorder: move the Pin at [from] to [to] (both Pin positions, cells-indexed). */
    fun movePin(from: Int, to: Int) {
        if (from == to) return
        val c = cells
        if (from !in c.indices || to !in c.indices) return
        if (c[from] !is Cell.Pin || c[to] !is Cell.Pin) return
        val mutable = c.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        cells = mutable
        // Persisting per move would spam the bridge; the drag end calls [persistOrder].
    }

    fun persistOrder() {
        val order = cells.filterIsInstance<Cell.Pin>().map { PinnedRef(it.app.packageName, it.app.userId) }
        if (order.isNotEmpty()) onOrderChange(order)
    }

    fun togglePin(app: BubbleApp) {
        val current = pinsOf().toMutableList()
        val ref = PinnedRef(app.packageName, app.userId)
        if (!current.remove(ref)) current.add(ref)
        onOrderChange(current)
        // 刚写下的这一份就是权威，**不要立刻再和解读一次**：写入值与戳分两个 key，同一毫秒的平票
        // 可能把旧值读回来，表现就是「点了没动，再点别的才刷新」（用户 2026-10-12 报告）。
        rebuild(pins = current)
    }

    /**
     * One-line summary of what the page currently shows, for the module log — replaces the
     * View-tree walk of the old PinManageView.describe(): with Compose the texts are
     * state, so the summary is derivable without touching the UI.
     */
    fun describe(tabText: String, headText: String): String =
        "texts=[$tabText,$headText] items=${cells.size} letters=${letters.size} manage=$manageMode"

    /**
     * Rebuild the cell list.
     *
     * @param pins 已知的收藏列表（[togglePin] 刚写下的那份）；null = 从存储现读。
     *
     * 两条纪律，都是用户 2026-10-12 那两条报告换来的：
     *  1. **`pinnedKeys` 与 `cells` 必须来自同一次快照**。图标读 [isPinned]（`pinnedKeys`），
     *     位置读 `cells` —— 一个同步落、一个在协程里晚一步落，就会"图标变了、位置没变，
     *     再点别的才刷新"。
     *  2. **旧的重建不许覆盖新的**（[rebuildGeneration]）：带推荐的那条路要跳一次 IO，
     *     连续两次操作时先发起的那个可能后完成，用旧快照把刚刷好的列表盖回去。
     */
    private fun rebuild(pins: List<PinnedRef>? = null) {
        val generation = ++rebuildGeneration
        val (enabled, count) = recommendConfig()
        val currentPins = pins ?: pinsOf()
        // 一次重建只读一次（缓存给 isPinned 用）：见上面的 `pinnedKeys`。
        val pinned = currentPins.map { "${it.packageName}#${it.userId}" }.toSet()
        if (enabled && loadRecommendations != null && apps.isNotEmpty()) {
            scope.launch {
                val recs = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { loadRecommendations.invoke(apps, pinned, count) }.getOrDefault(emptyList())
                }
                if (generation != rebuildGeneration) return@launch
                applyCells(currentPins, pinned, enabled, count, recs)
            }
        } else {
            applyCells(currentPins, pinned, enabled, count, emptyList())
        }
    }

    /** 唯一落地点：`pinnedKeys` 与 `cells` 一起更新，别处不要再单独改它们。 */
    private fun applyCells(
        pins: List<PinnedRef>,
        pinned: Set<String>,
        enabled: Boolean,
        count: Int,
        recommended: List<BubbleApp>,
    ) {
        pinnedKeys = pinned
        cells = Sections.build(
            all = apps,
            pins = pins,
            recommend = enabled,
            recommendCount = count,
            recommendedApps = recommended,
        )
        letters = (listOf("★") + Sections.labelsOf(cells)).distinct()
        onContentChanged?.invoke()
    }

    /** 每次重建的序号：只有最新那次的异步结果可以落地（见 [rebuild]）。 */
    private var rebuildGeneration = 0

    /** Detach from the (foreign, long-lived) host process. */
    fun release() {
        scope.cancel()
    }
}

/**
 * 需求 5：以应用名称为小标题分类的功能分组
 */
data class AppFunctionGroup(
    val appName: String,
    val sortKey: String,
    val items: List<com.repl.bubbledrawer.shortcut.ShortcutItem>,
)
