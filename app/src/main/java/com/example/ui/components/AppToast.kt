package com.example.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局提示通道：把散落全站的 `Toast.makeText(...).show()` 收进同一个出口。
 *
 * 为什么要这么绕一层，而不是逐处改写成 `snackbarHostState.showSnackbar(...)`：
 * 1. 全站 60+ 处 Toast 分布在 ViewModel（没有 Composable 环境）、Dialog、BottomSheet、
 *    Activity result 回调里，逐处拿 SnackbarHostState 要么改函数签名要么提 local 变量，
 *    改动面一大就容易碰坏逻辑。
 * 2. 这里刻意做成**与 android.widget.Toast.makeText 完全同签名**的 API：
 *    `AppToast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()`
 *    于是替换动作只是把类名前缀换掉，参数一字不动 —— 零误伤风险（消息里带逗号、
 *    带插值、带嵌套括号的都不需要重新解析）。
 *
 * 行为：宿主 [SnackbarHostState] 在组合中 → 走 App 自己的 snackbar 皮肤（圆角卡、薄荷勾选、
 * 与主题联动）；宿主不在（阅读页 / 独立路由 / 尚未组合）→ 原样退回系统 Toast，
 * **绝不吞掉提示**。
 */
object AppToast {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 自带作用域：show() 可能在 ViewModel 线程被调用，这里统一切回主线程投递。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var host: SnackbarHostState? = null

    private var pending: Job? = null

    /** 由渲染 SnackbarHost 的界面在组合期绑定；同一实例重复绑定无副作用。 */
    fun bind(target: SnackbarHostState) {
        host = target
    }

    /**
     * 解绑。⚠️ 必须同时取消挂起的 showSnackbar：showSnackbar 会一直挂起到被消费为止，
     * 宿主离开组合后再也没人消费它 → 那个协程会永久悬在 [scope] 上（泄漏 + 提示排队错乱）。
     */
    fun unbind(target: SnackbarHostState) {
        if (host === target) {
            host = null
            pending?.cancel()
            pending = null
            runCatching { target.currentSnackbarData?.dismiss() }
        }
    }

    /** 与 [Toast.makeText] 同签名，第三个参数只用来决定时长（Long/Short）。 */
    fun makeText(context: Context, text: CharSequence, duration: Int): AppToastMessage =
        AppToastMessage(context, text.toString(), duration == Toast.LENGTH_LONG)

    internal fun launch(target: SnackbarHostState, message: String, long: Boolean) {
        pending?.cancel()
        pending = scope.launch {
            target.showAppSnackbar(
                message = message,
                kind = AppSnackKind.TOAST,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }

    internal fun postToast(context: Context, message: String, long: Boolean) {
        mainHandler.post {
            runCatching {
                Toast.makeText(
                    context.applicationContext,
                    message,
                    if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** 有宿主走应用内 snackbar，没有则退回系统 Toast —— 统一入口，避免外部摸 [host]。 */
    internal fun dispatch(context: Context, message: String, long: Boolean) {
        val target = host
        if (target != null) launch(target, message, long) else postToast(context, message, long)
    }
}

/** [AppToast.makeText] 的返回句柄，形状对齐 [Toast]（只有 show() 被用到）。 */
class AppToastMessage internal constructor(
    private val context: Context,
    private val message: String,
    private val long: Boolean,
) {
    fun show() {
        AppToast.dispatch(context, message, long)
    }
}

/**
 * 把本界面的 [SnackbarHostState] 登记为全局提示宿主，离开组合时自动解绑。
 * 只在真正渲染 SnackbarHost 的地方调用（目前：主页 Scaffold、书源管理页）。
 */
@Composable
fun BindAppToastHost(hostState: SnackbarHostState) {
    DisposableEffect(hostState) {
        AppToast.bind(hostState)
        onDispose { AppToast.unbind(hostState) }
    }
}
