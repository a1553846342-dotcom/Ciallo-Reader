package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppFonts

/**
 * 滚动联动折叠头部——四个 Tab 页（书库/书架/统计/设置）共享。
 *
 * 根因修复（见《根因诊断报告》）：此前四页头部是复制粘贴的 88~92dp 固定玻璃卡，
 * 与滚动状态零联动，常驻挤压约 11% 屏高。
 *
 * 状态机（代理C）：Expanded（默认，标题24sp+副标题）→ Collapsed（滚过约 20dp 即收起：
 * 标题缩至 19sp、副标题高度与透明度归零、内外 padding 收紧）→ 回到顶部恢复 Expanded。
 * 收起/展开由 [rememberHeaderCollapsed] 的 derivedStateOf 派生——只在状态翻转时重组，
 * 滚动过程中零监听开销；高度动画 220ms 一次性补间，无逐帧常驻计算。
 *
 * 视觉效果：滚动后头部收缩（Statistics 无大槽位约 51dp；Library/Home 因 48dp 最小触控
 * 槽位收起态约 62-74dp），为列表让出可视高度；标题始终保留（位置感不丢失）。
 */

/** 折叠判定：滚过首项顶部约 20dp（密度无关，LocalDensity 换算）即收起，回到顶部恢复。derivedStateOf 只在布尔翻转时通知。 */
@Composable
fun rememberHeaderCollapsed(state: LazyListState, forceCollapsed: Boolean = false): Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    val scrolled by remember(state, thresholdPx) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
    }
    return scrolled || forceCollapsed
}

@Composable
fun rememberHeaderCollapsed(state: LazyGridState, forceCollapsed: Boolean = false): Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    val scrolled by remember(state, thresholdPx) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
    }
    return scrolled || forceCollapsed
}

@Composable
fun rememberHeaderCollapsed(state: LazyStaggeredGridState, forceCollapsed: Boolean = false): Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    val scrolled by remember(state, thresholdPx) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
    }
    return scrolled || forceCollapsed
}

/**
 * 折叠信号的「lambda 版」：返回一个**不在本页订阅滚动状态**的求值函数，
 * 真正的读取发生在 [TabScreenHeader] 内部（它自己用 derivedStateOf 包一层）。
 *
 * 为什么要有这个版本：[rememberHeaderCollapsed] 返回的是 Boolean，页面体一旦解包，
 * 折叠状态每次翻转都会重组**整个页面**composable（书库/书架页动辄 3000+ 行函数体）。
 * 改成把 lambda 传下去之后，页面体零订阅，重组范围只剩头部那一小块。
 * 与 LibraryScreen 的 `headerFractionSource` 同一手法（那边早就是 lambda 了）。
 */
@Composable
fun rememberHeaderCollapsedSource(state: LazyListState, forceCollapsed: () -> Boolean = { false }): () -> Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    return { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx || forceCollapsed() }
}

/** 网格版折叠信号，语义同 [rememberHeaderCollapsedSource]。 */
@Composable
fun rememberHeaderCollapsedSource(state: LazyGridState, forceCollapsed: () -> Boolean = { false }): () -> Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    return { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx || forceCollapsed() }
}

/** 瀑布流版折叠信号，语义同 [rememberHeaderCollapsedSource]。 */
@Composable
fun rememberHeaderCollapsedSource(state: LazyStaggeredGridState, forceCollapsed: () -> Boolean = { false }): () -> Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    return { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx || forceCollapsed() }
}

/**
 * @param collapsed   是否收起（由 [rememberHeaderCollapsed] 派生；传了 [collapsedSource] 时忽略）
 * @param collapsedSource 折叠信号的 lambda 版（[rememberHeaderCollapsedSource]）。传入时本页不再订阅
 *                  滚动状态，折叠态在头部内部 derivedStateOf 求值 —— 重组范围收缩到头部。
 * @param titleColor  标题色（各页传 glassTitleColor()，与背景明暗联动）
 * @param titleVisible false 时不组合标题列（书架页搜索展开态占用整行）
 * @param leading     标题前的槽位（如设置页返回键）
 * @param trailing    标题后的槽位（如书库下载入口、书架搜索框）
 */
@Composable
fun TabScreenHeader(
    collapsed: Boolean = false,
    collapsedSource: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    titleVisible: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    // 折叠态在头部作用域内求值：lambda 版只让头部订阅，页面体零订阅。
    // derivedStateOf 保证滚动过程中仅在布尔翻转时重组一次头部（逐帧零开销）。
    val collapsedNow by remember(collapsedSource, collapsed) {
        derivedStateOf { collapsedSource?.invoke() ?: collapsed }
    }

    val outerV by animateDpAsState(if (collapsedNow) 6.dp else 10.dp, tween(220), label = "hdrOuterV")
    val innerV by animateDpAsState(if (collapsedNow) 7.dp else 12.dp, tween(220), label = "hdrInnerV")
    val titleSize by animateFloatAsState(if (collapsedNow) 19f else 24f, tween(220), label = "hdrTitle")
    val subAlpha by animateFloatAsState(if (collapsedNow) 0f else 1f, tween(180), label = "hdrSubAlpha")
    // 副标题槽位：12sp 系统字体的自然行高约 17.6~19sp（厂商字体 metrics 不同），
    // 18dp 会把英文降部（如 Y / & 的下缘）裁掉。放大到 20dp 并去掉硬编码
    // lineHeight（原 14sp 比字体自然高度还小，段落盒自身就会截断字形）。
    // 切换到打包 Noto 字体后 metrics 稳定，但槽位仍留余量以容纳 fallback 字体。
    val subH by animateDpAsState(if (collapsedNow) 0.dp else 20.dp, tween(220), label = "hdrSubH")

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = outerV),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = innerV),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke(this)
            if (titleVisible && title != null) {
                if (leading != null) Spacer(Modifier.width(2.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = titleSize.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        fontFamily = AppFonts.Serif
                    )
                    // 副标题用高度+透明度双通道收起：收起态不占布局空间。
                    // 不设 lineHeight：让段落盒用字体自然行高，避免硬编码值小于
                    // 字体实际高度时截断字形（"LIBRARY & SEARCH" 下半被裁的根因）。
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = titleColor.copy(alpha = 0.75f),
                            letterSpacing = 1.5.sp,
                            modifier = Modifier
                                .height(subH)
                                .alpha(subAlpha)
                        )
                    }
                }
            }
            trailing?.invoke(this)
        }
    }
}
