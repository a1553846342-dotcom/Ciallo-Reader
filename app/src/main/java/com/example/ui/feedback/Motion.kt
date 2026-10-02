package com.example.ui.feedback

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import android.annotation.SuppressLint
import androidx.compose.ui.platform.LocalContext

/**
 * 全 App 动效与触觉的统一令牌（设计稿 §4）：
 * - 弹簧 stiffness≈300 / damping≈22（拿起与落下 damping≈16，更"重"一点）；
 * - 普通过渡 200~250ms easeOutCubic；
 * - 粒子与飞入总时长 ≤700ms；
 * - 触觉分级：selection / light / medium / heavy / success，设置里可关。
 */
object AppMotion {
    /** 常规弹簧：dampingRatio = 22 / (2·√300) ≈ 0.635 */
    val springDefault: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.635f, stiffness = 300f, visibilityThreshold = 0.001f)

    /** 拿起 / 落下：dampingRatio = 16 / (2·√300) ≈ 0.462（更有分量） */
    val springLift: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.462f, stiffness = 300f, visibilityThreshold = 0.001f)

    /** 果冻弹跳（放置成功的 Ch​ip 回弹） */
    val springJelly: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.40f, stiffness = 340f, visibilityThreshold = 0.001f)

    /**
     * 松手在空白处 → 归位回弹：dampingRatio 0.62 → 会轻微越过原位再收住，
     * 这是"有重量地弹回"而不是"线性飘回"的关键（苹果的 cancel drop 就是这个手感）。
     */
    val springReturn: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.62f, stiffness = 300f, visibilityThreshold = 0.001f)

    /** 归位时的缩放收敛（1.08 → 1.0）：几乎不过冲，避免落位时抖。 */
    val springSettle: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.78f, stiffness = 340f, visibilityThreshold = 0.001f)

    /** 跟随手指的倾斜收敛（阻尼偏高，跟手但不抖）。 */
    val springTilt: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.70f, stiffness = 200f, visibilityThreshold = 0.01f)

    /** 被分类"吸进去"：全程加速，末段最快（Material FastOutLinearIn 的等价贝塞尔）。 */
    val easeSuckIn: Easing = CubicBezierEasing(0.4f, 0.0f, 1f, 1f)

    val springStiff: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** easeOutCubic */
    val easeOutCubic: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    const val TRANSITION_MS = 220
    const val HEART_MS = 350
    /** 「加入我喜欢的」心形迸发：一整套（光晕 + 主心 + 卫星心 + 拖尾）的总时长 */
    const val HEART_BURST_MS = 1150
    const val PARTICLE_MS = 600
    const val LIFT_MS = 260
    /** 归位回弹的总时长（弹簧收敛 + 收尾余量） */
    const val RETURN_MS = 560L
    /** 被分类吸入的时长 */
    const val DROP_MS = 380

    /**
     * 多选态下「已选中」卡片的缩放。
     *
     * ⚠️ 归位动画必须收敛到**这个值**而不是 1.0：书是带着选中态落位的，
     * 幽灵卡若收到 1.0，落位瞬间就比真实卡片大一圈，看着像没对齐。
     * 书架卡片与归位幽灵卡共用这个常量，别各写一份。
     */
    const val SELECTED_SCALE = 0.94f

    /** 长按进入多选 */
    const val LONG_PRESS_MS = 350L
    /** 多选态下再次长按"已选中"的书 → 拿起 */
    const val PICK_UP_MS = 250L
    /** 悬停分段 >600ms 弹簧加载切栏 */
    const val SPRING_LOAD_MS = 600L
}

/** 系统「减少动态效果」：由 MainActivity 注入（读 Settings.Global.ANIMATOR_DURATION_SCALE）。 */
val LocalReduceMotion = compositionLocalOf { false }

/** 触觉开关（设置项，默认开）。 */
val LocalHapticsEnabled = compositionLocalOf { true }

/**
 * 触觉总开关的**非组合环境**镜像。
 *
 * 为什么需要一个全局 object 而不只有 CompositionLocal：漫画翻页、裁切滑块等处的震动是直接
 * 调 `View.performHapticFeedback`（走 LocalView），组合期之外、协程里都在触发，CompositionLocal
 * 传不进去。这里由 MainActivity 用 SideEffect 同步一次，读取方只做一次布尔判断，零订阅成本。
 * ⚠️ 它是「进程级」状态，不参与重组，因此不会引入额外的 recomposition。
 */
object HapticsGate {
    @Volatile
    var enabled: Boolean = true
}

/** [HapticsGate] 关闭时替换 LocalHapticFeedback 的空实现：所有 Compose 侧震动静默。 */
object MutingHapticFeedback : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(
        feedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType
    ) {
        // 故意什么都不做
    }
}

/** 触觉分级。 */
enum class HapticKind { SELECTION, LIGHT, MEDIUM, HEAVY, SUCCESS }

/**
 * 语义化触觉：直接用系统 Vibrator（Compose 的 HapticFeedbackType 只有寥寥几种，
 * 且各版本可用常量不一致，做不出 light/medium/heavy 的分级）。
 * 统一受 [LocalHapticsEnabled] 开关控制；无马达/无权限时静默降级。
 */
class AppHaptics(
    private val context: android.content.Context,
    private val enabled: () -> Boolean,
) {
    fun perform(kind: HapticKind) {
        if (!HapticsGate.enabled) return
        if (!enabled()) return
        runCatching {
            val vibrator = vibrator() ?: return
            if (!vibrator.hasVibrator()) return
            val (millis, amplitude) = when (kind) {
                HapticKind.SELECTION -> 8L to 48      // 轻 tick
                HapticKind.LIGHT -> 12L to 110        // light impact
                HapticKind.MEDIUM -> 18L to 160       // 进入多选
                HapticKind.HEAVY -> 26L to 220        // 拿起
                HapticKind.SUCCESS -> 16L to 190      // 放入完成
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        millis,
                        amplitude.coerceIn(1, 255),
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(millis)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun vibrator(): android.os.Vibrator? {
        val app = context.applicationContext
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val context = LocalContext.current
    val enabled = LocalHapticsEnabled.current
    // 开关可能被设置页实时改写：用 remember(key) 重建，避免闭包固化旧值
    return remember(context, enabled) { AppHaptics(context) { enabled } }
}

/**
 * 读系统「减少动态效果」。
 *
 * 开发者选项 / 无障碍里关闭动画时，系统会写这三个全局 scale。不同 ROM 写得不太一样：
 * 有的只写 [Settings.Global.ANIMATOR_DURATION_SCALE]（小米「删除动画」开关），
 * 有的三个都写 0（原生开发者选项 → 动画时长缩放设为「关闭」），
 * 还有的厂商会把它们设成 0.5 这种中间值。
 *
 * 因此这里判定为：三者**任一**被关到近乎 0 <=> 视为开启「减少动态效果」。
 * 不用 `== 0f` 严格相等：部分 ROM 写 0.001 之类，严格相等会漏判。
 */
@Composable
fun systemReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scaleKeys = listOf(
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
            android.provider.Settings.Global.WINDOW_ANIMATION_SCALE
        )
        scaleKeys.any { key ->
            val scale = try {
                android.provider.Settings.Global.getFloat(context.contentResolver, key, 1f)
            } catch (e: Throwable) {
                1f
            }
            scale <= REDUCED_MOTION_SCALE_EPSILON
        }
    }
}

/**
 * 判定阈值：系统动画 scale <= 该值即认为动画已被关到"看不出来"。
 *
 * 刻意取一个很小的 epsilon 而不是 0f 严格相等：部分 ROM 会写 0.001 / 0.005 这种值。
 *
 * 同时刻意**不**把 0.5（常见的「动画 0.5x」档）算作 reduceMotion ——
 * 0.5x 视觉上仍看得见过渡，把它判成 reduceMotion 会让 App 里一串效果凭空消失，
 * 等于为了适配而牺牲视觉还原度。真正需要跳过动画的只有"被关掉"这一种情况。
 */
private const val REDUCED_MOTION_SCALE_EPSILON = 0.01f

/** 子树覆盖用（预览/测试）。 */
@Composable
fun ProvideMotion(
    reduceMotion: Boolean,
    hapticsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        LocalHapticsEnabled provides hapticsEnabled,
        content = content,
    )
}
