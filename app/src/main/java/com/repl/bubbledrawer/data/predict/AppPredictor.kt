package com.repl.bubbledrawer.data.predict

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.repl.bubbledrawer.data.LaunchCountStore
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Intelligent App Recommendation Engine.
 * Complete replication of Xiaomi HyperOS SecurityCenter (安全服务) app prediction architecture:
 * 1. 5-dimensional Naive Bayes Classifier (`BayesModel`)
 * 2. 2-order Markov Transition Chain PBB (`MarkovModel`)
 * 3. Reciprocal Rank Fusion (`RankFusion`)
 * 4. 7-day `UsageStatsManager` pre-training & fallback ranking
 */
object AppPredictor {

    private const val TAG = "MeiWindow_Predictor"
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000L

    private val bayesModel = BayesModel()
    private val markovModel = MarkovModel()

    private val recentApps = ArrayDeque<PredictApp>(5)
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 7-day foreground usage time cache: pkg -> totalTimeInForegroundMs
    @Volatile
    private var weeklyUsageTimes: Map<String, Long> = emptyMap()

    /**
     * 当前默认网络的类型，由 [watchNetworkType] 注册的默认网络回调维护。
     *
     * 推荐跑在扇形展开的主线程上（`BubbleDockController.buildPages`），以前每次都现问
     * `activeNetwork` + `getNetworkCapabilities` —— 两次 binder。null = 回调还没送达（或进程没有
     * ACCESS_NETWORK_STATE，注册失败），这时退回同步查询，行为与以前一样。
     */
    @Volatile
    private var networkType: String? = null

    private val networkWatched = AtomicBoolean(false)

    // System packages to ignore during training
    private val SYSTEM_IGNORED_PKGS = setOf(
        "com.android.systemui",
        "com.repl.bubbledrawer",
        "com.miui.home",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "android",
    )

    /**
     * Initializes the recommendation engine in the background by learning from
     * Android's 7-day UsageEvents and UsageStats (SystemUI UID 1000 has native access).
     */
    fun initIfNeeded(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        watchNetworkType(context.applicationContext ?: context)
        scope.launch {
            try {
                loadHistoryFromUsageStats(context.applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load usage history for predictor training", t)
            }
        }
    }

    private suspend fun loadHistoryFromUsageStats(context: Context) = withContext(Dispatchers.IO) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm == null) {
            Log.d(TAG, "UsageStatsManager unavailable")
            return@withContext
        }

        val endTime = System.currentTimeMillis()
        val startTime = endTime - SEVEN_DAYS_MS

        // 1. Query weekly usage stats for fallback ranking (verbatim C5146k.b())
        runCatching {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, startTime, endTime)
            if (!stats.isNullOrEmpty()) {
                val times = HashMap<String, Long>()
                for (s in stats) {
                    val pkg = s.packageName ?: continue
                    if (pkg in SYSTEM_IGNORED_PKGS) continue
                    times[pkg] = (times[pkg] ?: 0L) + s.totalTimeInForeground
                }
                weeklyUsageTimes = times
                Log.i(TAG, "Loaded 7-day usage stats for ${times.size} packages")
            }
        }.onFailure { Log.d(TAG, "queryUsageStats failed: ${it.message}") }

        // 2. Query UsageEvents to reconstruct exact chronological app switch sequences
        runCatching {
            val events = usm.queryEvents(startTime, endTime)
            val eventList = ArrayList<PredictApp>()
            val event = UsageEvents.Event()

            var lastPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // Filter for app entering foreground
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val pkg = event.packageName ?: continue
                    if (pkg in SYSTEM_IGNORED_PKGS) continue
                    if (pkg == lastPkg) continue // collapse repeated resumes of same app
                    lastPkg = pkg

                    eventList.add(
                        PredictApp(
                            pkg = pkg,
                            userId = 0,
                            timestamp = event.timeStamp,
                            network = "WIFI",
                        )
                    )
                }
            }

            if (eventList.isNotEmpty()) {
                Log.i(TAG, "Training Bayes and Markov models with ${eventList.size} historical transitions...")
                bayesModel.train(eventList)
                markovModel.train(eventList)

                synchronized(recentApps) {
                    recentApps.clear()
                    val takeRecent = eventList.takeLast(3)
                    for (app in takeRecent) {
                        recentApps.addLast(app)
                    }
                }
                Log.i(TAG, "Models trained successfully from usage history.")
            }
        }.onFailure { Log.d(TAG, "queryEvents failed: ${it.message}") }
    }

    /**
     * Records an app launch or foreground transition in real-time.
     */
    fun recordLaunch(context: Context, packageName: String, userId: Int = 0) {
        if (packageName in SYSTEM_IGNORED_PKGS) return
        val network = getNetworkType(context)
        val now = System.currentTimeMillis()
        val currentApp = PredictApp(packageName, userId, now, network)

        synchronized(recentApps) {
            if (recentApps.size >= 2) {
                val prev2 = recentApps.elementAt(recentApps.size - 2).key
                val prev1 = recentApps.last().key
                bayesModel.record(prev2, prev1, network, now, currentApp.key)
                markovModel.record(prev2, prev1, currentApp.key)
            } else if (recentApps.size == 1) {
                val prev1 = recentApps.last().key
                markovModel.record("", prev1, currentApp.key)
            }

            if (recentApps.size >= 5) {
                recentApps.removeFirst()
            }
            recentApps.addLast(currentApp)
        }
    }

    /**
     * Recommends top candidate apps based on current context, Bayes + Markov models,
     * RRF rank fusion, and 7-day UsageStats fallback.
     */
    fun getRecommendations(
        context: Context,
        allApps: List<BubbleApp>,
        pinnedKeys: Set<String>,
        maxCount: Int,
    ): List<BubbleApp> {
        if (maxCount <= 0 || allApps.isEmpty()) return emptyList()
        initIfNeeded(context)

        // Lookup maps: by "pkg#userId" and by "pkg" (for default user)
        val appByKey = HashMap<String, BubbleApp>(allApps.size)
        val appByPkg = HashMap<String, BubbleApp>(allApps.size)
        for (app in allApps) {
            val key = "${app.packageName}#${app.userId}"
            appByKey[key] = app
            if (app.userId == 0) {
                appByPkg[app.packageName] = app
            }
        }

        var prev2 = ""
        var prev1 = ""
        synchronized(recentApps) {
            if (recentApps.size >= 2) {
                prev2 = recentApps.elementAt(recentApps.size - 2).key
                prev1 = recentApps.last().key
            } else if (recentApps.size == 1) {
                prev1 = recentApps.last().key
            }
        }

        val network = getNetworkType(context)
        val now = System.currentTimeMillis()

        // 1. Bayes & Markov predictions
        val bayesRanks = bayesModel.predict(prev2, prev1, network, now)
        val markovRanks = markovModel.predict(prev2, prev1)

        // 2. RRF Fusion
        val fused = RankFusion.fuse(bayesRanks, markovRanks)

        val result = LinkedHashSet<BubbleApp>()
        val seenKeys = HashSet<String>()

        // 3. Resolve fused predictions to installed apps
        for (key in fused) {
            val app = appByKey[key] ?: appByPkg[key] ?: continue
            val appKey = "${app.packageName}#${app.userId}"
            if (appKey in pinnedKeys) continue
            if (appKey == prev1) continue // avoid recommending the app currently in foreground
            if (seenKeys.add(appKey)) {
                result.add(app)
            }
            if (result.size >= maxCount) break
        }

        // 4. Fallback backfill using 7-day UsageStats & LaunchCountStore
        if (result.size < maxCount) {
            val usageTimes = weeklyUsageTimes
            val sortedFallback = allApps
                .filter { app ->
                    val appKey = "${app.packageName}#${app.userId}"
                    appKey !in pinnedKeys && appKey !in seenKeys && appKey != prev1
                }
                .sortedWith(
                    Comparator { a, b ->
                        val timeA = usageTimes[a.packageName] ?: 0L
                        val timeB = usageTimes[b.packageName] ?: 0L
                        if (timeA != timeB) {
                            return@Comparator timeB.compareTo(timeA)
                        }
                        b.usageCount.compareTo(a.usageCount)
                    }
                )

            for (app in sortedFallback) {
                val appKey = "${app.packageName}#${app.userId}"
                if (seenKeys.add(appKey)) {
                    result.add(app)
                }
                if (result.size >= maxCount) break
            }
        }

        return result.take(maxCount)
    }

    private fun getNetworkType(context: Context): String {
        networkType?.let { return it }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "OFFLINE"
        return runCatching {
            val active = cm.activeNetwork ?: return "OFFLINE"
            val caps = cm.getNetworkCapabilities(active) ?: return "OFFLINE"
            networkTypeOf(caps)
        }.getOrDefault("WIFI")
    }

    /**
     * 注册一次默认网络回调（在 ConnectivityThread 上回调），之后 [getNetworkType] 只读字段。
     *
     * 幂等。与 [initIfNeeded] 分开：宿主可以在启动时（后台线程）就先把它挂上，而训练必须等到用户
     * 解锁之后（锁定状态下 UsageStats 读不到东西，而训练只跑一次）。
     */
    fun watchNetworkType(context: Context) {
        if (!networkWatched.compareAndSet(false, true)) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    networkType = networkTypeOf(caps)
                }

                override fun onLost(network: Network) {
                    networkType = "OFFLINE"
                }
            })
        }.onFailure { Log.d(TAG, "default network callback unavailable: ${it.message}") }
    }

    private fun networkTypeOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        else -> "OTHER"
    }
}
