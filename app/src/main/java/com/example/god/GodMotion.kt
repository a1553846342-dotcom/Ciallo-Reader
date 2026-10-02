package com.example.god

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.luminance
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * 神回动效与视觉规范（需求第九节）。
 *
 * 所有时长 / 弹簧 / 配色集中在此，调参只需改这一处。
 */
object GodMotion {

    /* ── 弹簧 ── */

    /** 主弹簧：dampingRatio 0.72 / stiffness 380 —— 有轻微回弹的"精致感" */
    fun <T> springMain(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = 380f)

    /** 轻弹簧：按压、小控件 */
    fun <T> springLight(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 520f)

    /** 软弹簧：大位移 / 整页转场 */
    fun <T> springSoft(): SpringSpec<T> = spring(dampingRatio = 0.90f, stiffness = 220f)

    /** 低阻尼弹簧（拍立得摆动、进场弹入） */
    fun <T> springBouncy(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 260f)

    /* ── 时长（250~450ms，无线性硬切） ── */
    const val FAST_MS = 180
    const val NORMAL_MS = 260
    const val SLOW_MS = 380
    const val SHEET_MS = 420

    /** 区块错峰间隔（需求：50~70ms） */
    const val STAGGER_MS = 60
    /** 拍立得错峰摆动 */
    const val SWING_STAGGER_MS = 60

    /** 慢速呼吸周期（第 1 名金色光环） */
    const val BREATH_MS = 3200
    /** 光泽扫过间隔（主按钮 / 标题流光） */
    const val SHINE_MS = 2600
    /** 黑胶旋转周期 */
    const val VINYL_SPIN_MS = 6000
    /** 金色流光描边一圈（章节卡） */
    const val BORDER_SWEEP_MS = 4200

    fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST_MS)
    fun <T> normal(): FiniteAnimationSpec<T> = tween(NORMAL_MS)
    fun <T> slow(): FiniteAnimationSpec<T> = tween(SLOW_MS)

    /* ── 圆角 / 尺寸 ── */
    const val SHEET_CORNER = 28f
    const val CARD_CORNER = 18f
    const val BUTTON_CORNER = 14f

    /* ── 触发阈值 ── */
    /** 阻尼拉伸触发阈值（dp 等效） */
    const val PULL_THRESHOLD_DP = 96f
    /** 橡胶带阻尼系数（越大越费力） */
    const val PULL_DAMPING = 0.55f

    /* ── 按压反馈：缩放 0.96 + 亮度微变 ── */
    const val PRESS_SCALE = 0.96f
    const val PRESS_ALPHA = 0.92f
}

/**
 * 神回配色：金 / 琥珀渐变（深色模式降饱和度，避免刺眼）+ 金银铜。
 */
object GodGold {

    // 金
    val LightStart = Color(0xFFFFE29A)
    val LightMid = Color(0xFFF5B942)
    val LightEnd = Color(0xFFD98E1F)

    val DarkStart = Color(0xFFE0CB96)
    val DarkMid = Color(0xFFC79A3E)
    val DarkEnd = Color(0xFF9A7223)

    // 银
    val SilverLightStart = Color(0xFFF1F3F8)
    val SilverLightEnd = Color(0xFFAEB6C4)
    val SilverDarkStart = Color(0xFFC6CAD3)
    val SilverDarkEnd = Color(0xFF868D9A)

    // 铜
    val BronzeLightStart = Color(0xFFF6CDA8)
    val BronzeLightEnd = Color(0xFFC47F4E)
    val BronzeDarkStart = Color(0xFFD4B295)
    val BronzeDarkEnd = Color(0xFF96613C)

    /** 主强调渐变（横向） */
    @Composable
    fun goldGradient(darkTheme: Boolean): List<Color> =
        if (darkTheme) listOf(DarkStart, DarkMid, DarkEnd)
        else listOf(LightStart, LightMid, LightEnd)

    /** 竖向渐变（按钮 / 台阶） */
    @Composable
    fun goldVertical(darkTheme: Boolean): Brush =
        Brush.verticalGradient(goldGradient(darkTheme))

    /** 名次配色：1 金 / 2 银 / 3 铜，其余用主题次级色 */
    @Composable
    fun medalColors(rank: Int, darkTheme: Boolean): List<Color> = when (rank) {
        1 -> goldGradient(darkTheme)
        2 -> if (darkTheme) listOf(SilverDarkStart, SilverDarkEnd)
        else listOf(SilverLightStart, SilverLightEnd)
        3 -> if (darkTheme) listOf(BronzeDarkStart, BronzeDarkEnd)
        else listOf(BronzeLightStart, BronzeLightEnd)
        else -> listOf(LightMid.copy(alpha = 0.75f), LightEnd.copy(alpha = 0.55f))
    }
}

/**
 * 主题明暗 —— 神回各处统一入口。
 *
 * ⚠️ **不能用 `isSystemInDarkTheme()`**：本 App 有自己的主题设置（浅色 / 深色 /
 * 跟随系统），系统是浅色而 App 设为深色时，神回的金色明度、纸面、随笔便签底色、
 * 星标配色会整套错位（实测：深色主题下随笔便签仍是米黄纸）。
 * 与 `ChapterStatusRow` 同款做法 —— 按配色方案背景的真实亮度判定。
 */
@Composable
fun godIsDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

/**
 * 系统「移除动画 / 动画时长缩放为 0」检查。
 * 命中时所有无限循环动画停用，转场降级为简单淡入淡出。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val resolver = context.contentResolver
            val duration = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
            duration == 0f || transition == 0f
        }.getOrDefault(false)
    }
}

/** 减少动态效果时：用 120ms 纯淡入淡出代替弹簧/位移。 */
@Composable
fun <T> godSpec(reduce: Boolean, spec: () -> AnimationSpec<T>): AnimationSpec<T> =
    if (reduce) tween(120) else spec()

/**
 * 统一按压反馈：缩放 [GodMotion.PRESS_SCALE] + 亮度微变。
 * 用 graphicsLayer lambda 形式，按压不触发外部重组。
 */
fun Modifier.godPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    scale: Float = GodMotion.PRESS_SCALE,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (enabled && pressed) scale else 1f
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = target,
        animationSpec = GodMotion.springLight<Float>(),
        label = "godPress",
    )
    this.graphicsLayer {
        scaleX = animated
        scaleY = animated
        alpha = if (enabled && pressed) GodMotion.PRESS_ALPHA else 1f
    }
}

/** 错峰延迟：第 index 块的入场延迟。 */
fun staggerDelay(index: Int, base: Int = 0): Int = base + index * GodMotion.STAGGER_MS
