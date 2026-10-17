package com.repl.bubbledrawer.launch

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.repl.bubbledrawer.data.MultiUserHelper
import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * The ONLY seam that talks to the window system for "how to open".
 *
 * Flyme original (`AppLauncherWindow.java:792-814`): ActivityOptions bundle with
 * private keys `start_windowmode` (true / 512) and `virtual_mode` (1035) then
 * startActivityAsUser — requires the flyme framework, unportable.
 * This replica opens fullscreen; a future freeform strategy only replaces this file.
 */
fun interface ILaunchStrategy {
    fun launch(context: Context, app: BubbleApp): Boolean
}

/**
 * 目标应用的启动 Intent —— 全屏启动（[FullscreenLaunchStrategy]）与「系统闸门小窗」
 * （[GateLaunchStrategy]）共用这一份构造，免得两个策略对"分辨率/多用户取哪个 Activity"各说各话。
 *
 * 取 Activity 的顺序与原来一致：LauncherApps 的启动 Activity → PackageManager 里按包名找 →
 * `getLaunchIntentForPackage`；都没有就返回 null（调用方据此报失败，而不是发一个空 Intent）。
 *
 * @param resetTaskIfNeeded 桌面语义（"回到应用根页"）。小窗启动**不要**它 —— 见
 *   [FreeformLaunchStrategy] 的类注释：ROM 自己的小窗启动不会重置任务。
 */
internal fun appLaunchIntent(
    context: Context,
    app: BubbleApp,
    resetTaskIfNeeded: Boolean = true,
): Intent? {
    val userHandle = MultiUserHelper.getUserHandle(app.userId)
    val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    val act = if (userHandle != null && launcherApps != null) {
        runCatching { launcherApps.getActivityList(app.packageName, userHandle).firstOrNull() }.getOrNull()
    } else null

    val comp = act?.componentName ?: MultiUserHelper.findComponentForPackage(context, app.packageName, app.userId)

    return (if (comp != null) {
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = comp
        }
    } else {
        context.packageManager.getLaunchIntentForPackage(app.packageName)
    })?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resetTaskIfNeeded) addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

class FullscreenLaunchStrategy : ILaunchStrategy {
    override fun launch(context: Context, app: BubbleApp): Boolean = try {
        val userHandle = MultiUserHelper.getUserHandle(app.userId)
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val i = appLaunchIntent(context, app) ?: return false
        val comp = i.component

        if (app.userId != 0 && userHandle != null) {
            val asUserOk = runCatching {
                val method = Context::class.java.getMethod(
                    "startActivityAsUser",
                    Intent::class.java,
                    UserHandle::class.java,
                )
                method.invoke(context, i, userHandle)
                true
            }.getOrElse { false }
            if (asUserOk) return true

            if (comp != null && launcherApps != null) {
                val launcherOk = runCatching {
                    launcherApps.startMainActivity(comp, userHandle, null, null)
                    true
                }.getOrElse { false }
                if (launcherOk) return true
            }
        }

        context.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }
}
