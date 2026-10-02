package com.example.ui.shelf

import androidx.activity.compose.BackHandler
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.LocalGlassBackdrop
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.example.ui.components.iridescentBorder
import com.example.ui.components.liquidGlass
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.HapticKind
import com.example.ui.feedback.LocalHapticsEnabled
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.feedback.rememberAppHaptics
import com.example.ui.theme.LocalAppBottomInset
import com.example.ui.theme.LocalAppBottomInsetNoTabBar
import com.example.ui.theme.MintPrimary
import com.kashif_e.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.trishiraj.shadowglow.consistentShadow

/** 书架外壳（底部 Tab 栏）的显隐：多选态下被悬浮操作栏"替换"。 */
object ShelfChrome {
    private val _tabBarVisible = MutableStateFlow(true)
    val tabBarVisible: StateFlow<Boolean> = _tabBarVisible.asStateFlow()

    fun setTabBarVisible(visible: Boolean) {
        _tabBarVisible.value = visible
    }
}

/** 放置目标 id 约定。 */
const val DROP_TARGET_FAVORITE = "drop::favorite"
const val DROP_TARGET_NEW = "drop::new"
fun dropTargetOfCategory(name: String) = "drop::cat::$name"

/** 「我喜欢的」分类栏的放置目标前缀（与书架分类栏区分开，两套分类互不相干）。 */
const val DROP_PREFIX_FAV_CATEGORY = "drop::favcat::"
fun dropTargetOfFavoriteCategory(name: String) = "$DROP_PREFIX_FAV_CATEGORY$name"

/** 边缘自动滚动的节奏（ms / 跳）。与 [ShelfSelectionState.edgeScroll] 相乘就是实际速度。 */
private const val EDGE_TICK_MS = 80L

/** 手指进入边缘感应带后要停留多久才开始真正滚动（只是路过不算）。 */
private const val EDGE_ARM_NS = 200_000_000L

/**
 * 「我喜欢的」条目在多选 / 拖拽体系里的 key 前缀。
 *
 * ⚠️ 必须和书架书籍区分开：书架用的是 `book.id`，收藏用的是 `sourceId::comicId`，
 * 两者都是字符串且可能撞号。加前缀后宿主才能分清「长按的是书还是收藏」。
 */
const val SHELF_KEY_FAV_PREFIX = "fav::"
fun favShelfKey(itemKey: String) = "$SHELF_KEY_FAV_PREFIX$itemKey"
fun isFavShelfKey(key: String) = key.startsWith(SHELF_KEY_FAV_PREFIX)

/**
 * 松手之后的"飞行"：UI 层据此把幽灵卡从松手点送到目的地，业务回调与动画并行。
 * 状态机本身只管选中/阶段，动画数据不进状态机（保持可单测）。
 */
enum class DragFlightKind {
    /** 吸入分类 / 新建：飞向 chip，缩没 + 目标 chip 果冻回弹 */
    MOVE,

    /** 加入「我喜欢的」：飞回原位 + 心形徽章弹出 */
    FAVORITE,

    /** 落在无效区域：沿弧线弹回原位，保持选中 */
    RETURN,
}

data class DragFlight(
    val id: Long,
    val kind: DragFlightKind,
    val items: List<String>,
    /** 起飞点（宿主局部坐标，幽灵卡中心） */
    val from: androidx.compose.ui.geometry.Offset,
    /** 落点（宿主局部坐标）。只是**初值**：归位时每帧都会重读真实位置，见 [DragFlightLayer] */
    val to: androidx.compose.ui.geometry.Offset,
    /** 原位矩形（宿主局部坐标）：回弹时画虚线占位并随归位淡出。单本时的快捷值。 */
    val origin: androidx.compose.ui.geometry.Rect? = null,
    /**
     * **每个条目各自**的原位矩形（宿主局部坐标）。
     *
     * ⚠️ 多本一起拖时绝不能共用第一本的原位：拖拽中是一摞扇形叠放的卡，
     * 松手却塌成"一本"飞回其中某一本的位置 —— 用户原话「从多本变成一本，
     * 和按住时的动画有很大割裂感」。每一本都要飞回**它自己**的位置。
     * 单本时留空即可，会退回 [origin]。
     */
    val origins: Map<String, androidx.compose.ui.geometry.Rect> = emptyMap(),
    /** 起飞瞬间的倾角：不接住的话幽灵卡会在起飞那一刻"啪"地摆正 */
    val fromRotation: Float = 0f,
    /**
     * 落位时幽灵卡要收敛到的缩放。
     *
     * ⚠️ 必须和真实卡片当时的缩放一致：归位后卡片处于「已选中」态（scale 0.94），
     * 幽灵卡若收到 1.0，落位瞬间就会比卡片大一圈 —— 看着像没对齐。
     */
    val settleTo: Float = 1f,
)

/** 多选态下条目左上角的圆形勾选框。 */
@Composable
fun ShelfSelectBadge(
    selected: Boolean,
    modifier: Modifier = Modifier,
    indexLabel: String? = null,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.7f,
        animationSpec = AppMotion.springDefault,
        label = "select_badge",
    )
    Box(
        modifier = modifier
            .size(22.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (selected) MintPrimary else Color.White.copy(alpha = 0.85f))
            // 选中态用**白色**描边：书封多是深色（MURAKAMI、Neuromancer…），
            // 绿色填充 + 绿色边框会在深色封面上糊成一团；白边在任何封面上都能把
            // 徽章从背景里分离出来（iOS 多选标记的做法）。
            .border(
                1.5.dp,
                if (selected) Color.White.copy(alpha = 0.95f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                CircleShape,
            )
            .semantics {
                contentDescription = if (selected) "已选中${indexLabel ?: ""}" else "未选中"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 多选两根栏（顶栏 / 底部操作栏）共用的「玻璃胶囊」外观。
 *
 * 与主 Tab 栏（AppBottomTabBar）同一套语言，四层叠出来而不是单层均色：
 *   ① 投影 → ② 玻璃底（按画质四档降级）→ ③ 竖向渐变（顶亮→主色淡染→底略深）
 *   → ④ 顶部 1dp 镜面高光 → ⑤ 外圈描边（LOW 用白描边，其余虹彩）。
 *
 * 为什么要分档：[RenderQuality.LOW] 档**不许**碰实时模糊与虹彩（任何设备都要满帧），
 * 走近实心底 + 1dp 白描边；MAX 档再追加更浓的主色辉光与更宽的虹彩描边。
 */
@Composable
private fun Modifier.multiSelectGlassBar(
    shape: Shape,
    primary: Color,
    /**
     * 实时毛玻璃那层的**白色基底不透明度**覆盖值（null = 用默认档位值）。
     *
     * 顶栏传一个很小的值：顶栏背后是页面浅色区域，默认 0.44 的白色基底会让整条胶囊
     * 读成"一块矩形实心白色"（用户原话），把基底压到 0.14 之后才真的像毛玻璃——
     * 磨砂底透出来、不再是白板。动作栏背景较杂，保持默认即可。
     */
    surfaceAlpha: Float? = null,
): Modifier {
    val quality = LocalRenderQuality.current
    val backdrop = LocalGlassBackdrop.current
    val surface = MaterialTheme.colorScheme.surface
    val isMax = quality == RenderQuality.MAX

    return this
        // ① 外投影：LOW 单层黑晕；其余「主色大晕 + 黑色小晕」两层，MAX 更浓
        .then(
            if (quality == RenderQuality.LOW) {
                Modifier.consistentShadow(
                    elevation = 8.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.16f),
                    spotColor = Color.Black.copy(alpha = 0.16f),
                )
            } else {
                Modifier
                    .consistentShadow(
                        elevation = if (isMax) 28.dp else 22.dp,
                        shape = shape,
                        ambientColor = primary.copy(alpha = if (isMax) 0.26f else 0.18f),
                        spotColor = primary.copy(alpha = if (isMax) 0.26f else 0.18f),
                    )
                    .consistentShadow(
                        elevation = 8.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.20f),
                        spotColor = Color.Black.copy(alpha = 0.20f),
                    )
            }
        )
        .clip(shape)
        // ② 玻璃底：HIGH/MAX 且有 backdrop 时走实时毛玻璃，其余按档位用半透明底
        .then(
            when {
                backdrop != null && quality.realtimeGlass -> Modifier.liquidGlass(
                    backdrop = backdrop,
                    shape = shape,
                    surfaceColor = surface.copy(
                        alpha = surfaceAlpha ?: if (isMax) 0.34f else 0.44f
                    ),
                    blurRadius = 8.dp,
                    refraction = false,
                )
                quality == RenderQuality.LOW -> Modifier.background(surface.copy(alpha = 0.97f), shape)
                quality == RenderQuality.MID -> Modifier.background(surface.copy(alpha = 0.80f), shape)
                else -> Modifier.background(surface.copy(alpha = 0.62f), shape)
            }
        )
        // ③ 竖向渐变：顶高光 → 主色淡染 → 底略深。单层均色会有"塑料片"感
        .background(
            brush = Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = if (quality == RenderQuality.LOW) 0.10f else 0.18f),
                0.38f to primary.copy(alpha = if (isMax) 0.22f else 0.16f),
                1.0f to Color.Black.copy(alpha = 0.10f),
            ),
            shape = shape,
        )
        // ④ 顶部镜面高光（specular hairline）：竖渐变只亮最上面一条边。
        //    ⚠️ 内缩 2dp：⑤ 的外圈描边本身也是半透明的一圈，不内缩的话
        //    高光会正好压在描边底下、被吃掉，读起来就没有"内高光"这层了。
        .drawWithCache {
            val inset = 2.dp.toPx()
            val innerW = (size.width - inset * 2f).coerceAtLeast(1f)
            val innerH = (size.height - inset * 2f).coerceAtLeast(1f)
            val outline = shape.createOutline(Size(innerW, innerH), layoutDirection, this)
            val specular = Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.60f),
                0.10f to Color.White.copy(alpha = 0.14f),
                0.30f to Color.Transparent,
                startY = 0f,
                endY = innerH,
            )
            onDrawBehind {
                if (outline is Outline.Rounded) {
                    drawRoundRect(
                        brush = specular,
                        topLeft = Offset(inset, inset),
                        size = Size(innerW, innerH),
                        cornerRadius = outline.roundRect.topLeftCornerRadius,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }

        // ⑤ 外圈描边：LOW 白描边，其余虹彩（MAX 更宽更亮）
        .then(
            if (quality == RenderQuality.LOW) {
                Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), shape)
            } else {
                Modifier.iridescentBorder(
                    shape = shape,
                    colors = listOf(primary, primary.copy(alpha = 0.55f), primary),
                    width = if (isMax) 2.dp else 1.5.dp,
                    alpha = if (isMax) 0.60f else 0.45f,
                )
            }
        )
}

/** 顶部栏：✕ / 已选择 N 本（数字滚动）/ 全选-取消全选。 */
@Composable
fun MultiSelectTopBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedCount by animateIntAsState(targetValue = count, animationSpec = tween(220), label = "count_roll")
    val shape = RoundedCornerShape(50)
    val primary = MintPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                // 毛玻璃保留，但白色基底压到 0.14：
                // 用户说的"矩形实心白色长方形"其实是这层 0.44 白基底在浅色背景上的样子
                // （胶囊本身不是问题）。压掉白基底后才是真正的磨砂玻璃 —— 透出背后内容，
                // 不再是一块白板，也顺带解决"页面标题透上来叠字"的问题。
                .multiSelectGlassBar(shape = shape, primary = primary, surfaceAlpha = 0.14f)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .semantics { contentDescription = "退出多选" }
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.width(10.dp))
            // 「已选择」退成次级信息，把视觉重心让给数字徽标
            Text(
                text = "已选择",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 26.dp, minHeight = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$animatedCount",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "本",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
            Spacer(modifier = Modifier.weight(1f))
            // 两态给**填充**差异，不只是换个图标：未全选=玻璃空心，已全选=主色实心
            val pillFill = if (allSelected) primary else Color.White.copy(alpha = 0.10f)
            val pillInk = if (allSelected) Color.White else primary
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(pillFill)
                    .border(
                        width = 1.dp,
                        color = if (allSelected) primary else primary.copy(alpha = 0.38f),
                        shape = shape,
                    )
                    .clickable { onToggleAll() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (allSelected) Icons.Filled.Close else Icons.Filled.SelectAll,
                        contentDescription = null,
                        tint = pillInk,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (allSelected) "取消全选" else "全选",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = pillInk,
                    )
                }
            }
        }
    }
}

/** 底部悬浮操作栏的一项。 */
data class ShelfAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/** 底部悬浮操作栏（毛玻璃胶囊，弹簧滑入，替换底部导航栏）。 */
@Composable
fun MultiSelectActionBar(
    actions: List<ShelfAction>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    hint: String? = null,
) {
    val shape = RoundedCornerShape(26.dp)
    val primary = MintPrimary
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                // 多选态下悬浮 Tab 栏已被藏起来，这里只能让开系统导航栏。
                // 继续用 LocalAppBottomInset 会让操作栏在底部凭空悬高 92dp。
                .padding(bottom = LocalAppBottomInsetNoTabBar.current),
        ) {
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 与顶栏 / 主 Tab 栏同一套玻璃语言（原本是单层纯色 + 0.5dp 描边，
                    // 和另外两根栏质感割裂，正是用户说的「下方纯色 tab 栏不好看」）
                    .multiSelectGlassBar(shape = shape, primary = primary)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    val alpha = if (action.enabled) 1f else 0.35f
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (action.enabled) Modifier.clickable { action.onClick() } else Modifier
                            )
                            .graphicsLayer { this.alpha = alpha }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            action.icon,
                            contentDescription = action.label,
                            tint = if (action.destructive) MaterialTheme.colorScheme.error else MintPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = action.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (action.destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** 「更多」Sheet：两栏各自的附加操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreActionSheet(
    titles: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("更多操作", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            titles.forEach { title ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(title) }
                        .padding(vertical = 14.dp),
                ) {
                    Text(title, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/** 分类选择 Sheet（操作栏「移动」与拖拽落到「＋新建分类」共用）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<String>,
    title: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    allowCreate: Boolean = true,
    onCreate: ((String) -> Unit)? = null,
) {
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            categories.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(name) }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(16.dp))
                }
            }
            if (allowCreate) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("＋新建分类", fontSize = 14.sp, color = MintPrimary)
                }
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("分类名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 底部弹窗是独立窗口，按钮不能复用主窗口的玻璃坐标。
                    com.example.ui.components.DialogLiquidGlass(fillMaxSize = false) {
                        com.example.ui.components.AppActionButton(
                            text = "创建并放入",
                            onClick = {
                                if (newName.isNotBlank()) {
                                    onCreate?.invoke(newName.trim())
                                    creating = false
                                    newName = ""
                                }
                            },
                            enabled = newName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * 拖拽宿主：包裹书架网格，承载手势、拖拽叠放、放置坞与自动滚动。
 *
 * @param coverOf 拖拽时跟随手指的缩略图内容
 */
@Composable
fun ShelfSelectionHost(
    state: ShelfSelectionState,
    categories: List<String>,
    currentCategory: String,
    showFavoriteTarget: Boolean,
    onDropToCategory: (List<String>, String) -> Unit,
    onDropToFavorite: (List<String>) -> Unit,
    /** 拖到「我喜欢的」某个分类上：加入收藏并归入该分类 */
    onDropToFavoriteCategory: (List<String>, String) -> Unit = { _, _ -> },
    onCreateCategoryThenDrop: (List<String>) -> Unit,
    /** 短按命中某条目（BROWSE → 打开；SELECTING → 切换选中）。手势由宿主统一处理 */
    onTapItem: (String) -> Unit,
    scrollBy: suspend (Float) -> Unit,
    coverOf: @Composable (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * **当前真实存在的条目 key**（书籍 id 与 `fav::sourceId::comicId`）。
     *
     * 宿主拿它裁剪几何表：条目消失后它的矩形必须一起消失，否则那块区域会变成
     * "幽灵热区"——点不到、长按选中不了，甚至把书丢进一个不存在的分类。
     */
    aliveKeys: Set<String> = emptySet(),
    /** 「我喜欢的」的分类名（用来推导哪些落点胶囊是真的还在） */
    favoriteCategories: List<String> = emptyList(),
    /** 命中兜底：卡片坐标回调不可靠时，按列表真实布局信息兜住点击/长按 */
    fallbackHit: (androidx.compose.ui.geometry.Offset) -> String? = { null },
    content: @Composable (itemBounds: @Composable (String) -> Modifier) -> Unit,
) {
    val haptics = rememberAppHaptics()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop

    var hostOffset by remember { mutableStateOf(Offset.Zero) }
    var hostTop by remember { mutableStateOf(0f) }
    var hostBottom by remember { mutableStateOf(0f) }

    /** 松手后的飞行动画（null = 无飞行） */
    var pendingFlight by remember { mutableStateOf<DragFlight?>(null) }
    /** 正在"接收"的 chip：果冻回弹反馈 */
    var pulseTarget by remember { mutableStateOf<String?>(null) }
    var flightSeq by remember { mutableStateOf(0L) }
    /**
     * 「加入我喜欢的」心形迸发的位置（宿主局部坐标）。
     * 独立于 pendingFlight 存在：心要比书飞得久，不能跟着归位飞行一起消失。
     */
    var heartBurstAt by remember { mutableStateOf<Offset?>(null) }

    /** 把 root 坐标的矩形换算到宿主局部坐标 */
    fun localRect(r: androidx.compose.ui.geometry.Rect?): androidx.compose.ui.geometry.Rect? =
        r?.translate(-hostOffset.x, -hostOffset.y)

    /** 幽灵卡中心：卡片画在手指上方，中心比手指高约 10dp */
    val ghostLiftPx = with(density) { 10.dp.toPx() }

    // 落点胶囊的"存活名单"：书架分类 + 我喜欢的分类 + ♡ + 新建
    val aliveTargets: Set<String> = remember(categories, favoriteCategories, showFavoriteTarget) {
        val s = HashSet<String>()
        categories.forEach { s.add(dropTargetOfCategory(it)) }
        favoriteCategories.forEach { s.add(dropTargetOfFavoriteCategory(it)) }
        if (showFavoriteTarget) s.add(DROP_TARGET_FAVORITE)
        s.add(DROP_TARGET_NEW)
        s
    }
    // ⚠️ 几何表只写不删，条目/分类消失后必须按"真实存在的名单"裁一遍，
    // 否则残留矩形会变成幽灵热区：点不动、选不中，甚至把书丢进不存在的分类。
    LaunchedEffect(aliveKeys, aliveTargets) {
        state.pruneGeometry(aliveKeys, aliveTargets)
    }
    // 命中测试**当场**也要按名单过滤（见 [ShelfSelectionState.aliveKeys]）：
    // 后台裁剪要等下一帧，那一帧里点下去就会命中已消失的旧 key。
    state.aliveKeys = aliveKeys
    state.fallbackHit = fallbackHit

    val dropSink = remember { DropSink() }
    val gestureSink = remember { ShelfGestureSink() }
    dropSink.onDrop = { target, items, release ->
        val from = Offset(release.x, release.y - ghostLiftPx)
        val picked = items.firstOrNull()
        // 归位落点必须用**封面**矩形：整卡矩形含书名那一行，中心比封面低，
        // 用它会让书飞到比原位低半张封面的地方，动画结束瞬间再"瞬移"上来。
        val originRect = localRect(picked?.let { state.coverRect(it) })
        val home = originRect?.center ?: from
        // 多本：每一本的落点都要单独记一份，松手后各自飞回各自的位置。
        val origins = items.mapNotNull { k -> localRect(state.coverRect(k))?.let { k to it } }.toMap()

        fun launch(kind: DragFlightKind, to: Offset, origin: androidx.compose.ui.geometry.Rect?) {
            // ⚠️ 上一次飞行还没落位就又来了一次 drop：新的 DragFlight 会顶掉旧的，
            // 旧那一层的 LaunchedEffect 随之被取消 ⇒ 它的 onDone **永远不会执行**
            // ⇒ 上一批条目永远留在 flyingHome 里（alpha 恒为 0）= 书凭空消失。
            // 所以起飞前先把上一批交班掉。
            if (pendingFlight != null) {
                state.endReturnFlight()
                pendingFlight = null
            }
            pendingFlight = DragFlight(
                id = ++flightSeq,
                kind = kind,
                items = items,
                from = from,
                to = to,
                origin = origin,
                origins = origins,
                // 接住松手瞬间的倾角，起飞才不会硬摆正
                fromRotation = if (kind == DragFlightKind.MOVE) 0f else state.ghostTilt,
                settleTo = if (kind == DragFlightKind.MOVE) {
                    1f
                } else {
                    com.example.ui.feedback.AppMotion.SELECTED_SCALE
                },
            )
        }

        when {
            // ① 落在非放置区：弹回原位（保持选中）
            target == null -> {
                haptics.perform(HapticKind.LIGHT)
                // 归位飞行期间真实卡片必须继续藏着，否则松手瞬间就"书已经回来了"
                state.beginReturnFlight(items)
                launch(DragFlightKind.RETURN, home, originRect)
            }
            // ② 落到 ♡：飞回原位 + 心形徽章
            target == DROP_TARGET_FAVORITE -> {
                haptics.perform(HapticKind.SUCCESS)
                state.beginReturnFlight(items)
                // 心形从"松手那一刻书所在的位置"迸发，不是从原位的书卡
                heartBurstAt = from
                launch(DragFlightKind.FAVORITE, home, originRect)
                onDropToFavorite(items)
            }
            // ③ 落到「＋新建分类」：吸入 chip，随后弹命名 Sheet
            target == DROP_TARGET_NEW -> {
                haptics.perform(HapticKind.SUCCESS)
                pulseTarget = target
                launch(DragFlightKind.MOVE, state.targetRect(target)?.center?.let { it - hostOffset } ?: home, null)
                // ⚠️ 必须退出多选：endDrag() 只把 phase 从 DRAGGING 收回 SELECTING，
                // 而拖走的条目已经离开当前列表 —— 留着就是"选中了一本不存在的书"，
                // 底栏一直挂着，之后点任何一本书都变成"切换选中"而不是打开
                // （用户说的"打不开书籍了，而且选中不了"就是这个状态）。
                state.exitSelection()
                onCreateCategoryThenDrop(items)
            }
            // ④ 落到某个分类
            target.startsWith("drop::cat::") -> {
                val name = target.removePrefix("drop::cat::")
                if (name != currentCategory) {
                    haptics.perform(HapticKind.SUCCESS)
                    pulseTarget = target
                    launch(
                        DragFlightKind.MOVE,
                        state.targetRect(target)?.center?.let { it - hostOffset } ?: home,
                        null,
                    )
                    state.exitSelection()   // 同 ③：书已经不在当前分类里了
                    onDropToCategory(items, name)
                } else {
                    // 当前分类自身 = 无效落点：也要优雅回弹，不能"啪"地消失
                    haptics.perform(HapticKind.LIGHT)
                    state.beginReturnFlight(items)
                    launch(DragFlightKind.RETURN, home, originRect)
                }
            }
            // ⑤ 落到「我喜欢的」的某个分类：加入收藏并归入该分类
            target.startsWith(DROP_PREFIX_FAV_CATEGORY) -> {
                val name = target.removePrefix(DROP_PREFIX_FAV_CATEGORY)
                haptics.perform(HapticKind.SUCCESS)
                pulseTarget = target
                launch(
                    DragFlightKind.MOVE,
                    state.targetRect(target)?.center?.let { it - hostOffset } ?: home,
                    null,
                )
                state.exitSelection()   // 同 ③：拖走的条目已离开当前列表
                onDropToFavoriteCategory(items, name)
            }
        }
    }

    // 悬停目标变化时给一颗轻 tick：手指"滑过一个个分类"时是能感觉到的
    LaunchedEffect(state.hoverTarget) {
        if (state.hoverTarget != null) haptics.perform(HapticKind.SELECTION)
    }

    // chip 的果冻反馈只闪一下，之后自动松开
    LaunchedEffect(pulseTarget) {
        if (pulseTarget != null) {
            delay(560)
            pulseTarget = null
        }
    }

    // 边缘自动滚动
    //
    // ⚠️ 三个坑，都让"页面自己跑得比手指还快"（或者干脆不动）：
    //  1) 原来 key 里带 state.edgeScroll：手指在感应带里稍微一动 edgeScroll 就变，
    //     LaunchedEffect 立刻重启 —— 协程被取消后重新进入，第一件事又是 scrollBy 一次。
    //     实际速度于是变成「手指事件频率 × 速度」，手抖两下页面就疯跑。
    //     现在 key 只跟 phase，循环内读当前值，节奏固定 80ms 一跳。
    //  2) 原来还乘了一次 density。edgeScroll 在 moveDrag 里已经按 density 换算成
    //     真实 px（`48f * density`），这里再乘一次就是 3 倍速。
    //  3) 「驻留 200ms 才开始滚」必须在这个循环里计时，不能放到 moveDrag 里：
    //     moveDrag 只在收到 MOVE 事件时被调用，手指停在边缘不动就没有事件，
    //     计时永远推不动 —— 结果反而是按住边缘页面纹丝不动。
    LaunchedEffect(state.phase) {
        var inEdgeSince = 0L
        while (state.phase == ShelfPhase.DRAGGING) {
            val v = state.edgeScroll
            if (v == 0f) {
                inEdgeSince = 0L
            } else {
                val now = System.nanoTime()
                if (inEdgeSince == 0L) inEdgeSince = now
                // 只是路过边缘不算数，得真的停在那里
                if (now - inEdgeSince >= EDGE_ARM_NS) scrollBy(v)
            }
            delay(EDGE_TICK_MS)
        }
    }

    // 多选态隐藏底部 Tab 栏（悬浮操作栏替换）
    LaunchedEffect(state.phase) {
        ShelfChrome.setTabBarVisible(state.phase == ShelfPhase.BROWSE)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { ShelfChrome.setTabBarVisible(true) }
    }

    BackHandler(enabled = state.phase != ShelfPhase.BROWSE) {
        if (state.phase == ShelfPhase.DRAGGING) {
            // 返回键取消拖拽：也走"优雅归位"，不能让卡片凭空消失
            val release = state.pointer
            val items = state.dragging
            state.cancelDrag()
            dropSink.onDrop?.invoke(
                null,
                items,
                if (release == Offset.Unspecified) Offset.Zero else release,
            )
        } else {
            state.exitSelection()
        }
    }

    // ⚠️ 每次组合都要把**最新**的回调换进水槽（见 ShelfGestureSink）：
    // pointerInput 的协程不随组合重启，只认它启动时捕获的那一版闭包；
    // 不换的话手势里拿到的还是"第一次组合时"的书单 —— 切换分类后就点不动了。
    gestureSink.hostOffset = { hostOffset }
    gestureSink.hostTop = { hostTop }
    gestureSink.hostBottom = { hostBottom }
    gestureSink.haptic = { haptics.perform(it) }
    gestureSink.enabled = { true }
    gestureSink.onTap = onTapItem

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val p = c.positionInRoot()
                hostOffset = Offset(p.x, p.y)
                hostTop = c.positionInRoot().y
                hostBottom = c.positionInRoot().y + c.size.height
            }
            .shelfGestures(
                state = state,
                touchSlop = touchSlop,
                sink = gestureSink,
                dropSink = dropSink,
                density = density.density,
            ),
    ) {
        content { key ->
            // 槽位复用时 onGloballyPositioned 不会再回调（位置没变），
            // 这里用上一次布置留下的坐标在**每次组合**补登记一次，
            // 否则切换分类后新书的矩形永远是空的 —— 点不动也选不中。
            val coords = remember {
                mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
            }
            SideEffect { state.registerItem(key, coords.value) }
            Modifier.onGloballyPositioned { c ->
                coords.value = c
                state.registerItem(key, c)
            }
        }

        // ⚠️ 放置目标不再用底部悬浮 Dock（已整块移除）。
        // 用户明确要求：**分类就是页面里「我的书架 / 我喜欢的」下面那一排分类胶囊，
        // 不要另起一栏**。所以 drop target 直接注册在那些 chip 上（见 HomeScreen），
        // 宿主这里只负责画"书被拿走后原位的虚线轮廓"。
        //
        // 原位轮廓必须画在宿主层：书卡自身此刻 alpha=0（避免留下白块），
        // 画在卡内的任何东西都会被一起淡掉。用已修好的像素级 layout 定位，零错位。
        if (state.phase == ShelfPhase.DRAGGING) {
            state.dragging.forEach { key ->
                val r = localRect(state.coverRect(key))
                if (r != null) {
                    key(r.toString()) { PlaceholderBox(rect = r, alpha = 1f) }
                }
            }
        }

        if (state.phase == ShelfPhase.DRAGGING) {
            // 幽灵卡尺寸取自真实封面（列数不同封面大小不同），
            // 写死 104x132dp 会让归位落点与真实卡片差一截。
            val pickedSize = localRect(state.coverRect(state.dragging.firstOrNull().orEmpty()))
                ?.let { androidx.compose.ui.geometry.Size(it.width, it.height) }
            DragStack(
                state = state,
                coverOf = coverOf,
                reduceMotion = reduceMotion,
                ghostSize = pickedSize,
            )
        }

        // 「加入我喜欢的」心形迸发：独立于归位飞行渲染，比书飞得更久
        heartBurstAt?.let { at ->
            HeartBurst(
                centerX = at.x,
                centerY = at.y,
                reduceMotion = reduceMotion,
                onDone = { if (heartBurstAt == at) heartBurstAt = null },
            )
        }

        pendingFlight?.let { flight ->
            DragFlightLayer(
                flight = flight,
                coverOf = coverOf,
                reduceMotion = reduceMotion,
                // ⚠️ 归位落点必须**每帧重读**，不能用起飞时的快照：
                // 松手后 phase 从 DRAGGING 变 SELECTING，卡片尺寸/缩放/相邻卡片布局都会变，
                // 快照算出来的落点会和书最终停的位置差一截（用户说的"完全不对齐"）。
                liveOrigin = { key -> localRect(state.coverRect(key)) },
                onDone = {
                    if (pendingFlight?.id == flight.id) {
                        // 幽灵卡此刻与真实卡片完全重合，交班：
                        // 同时移除幽灵卡 + 让真实卡片显形，中间不留空隙也不重叠。
                        state.endReturnFlight()
                        pendingFlight = null
                    }
                },
            )
        }
    }
}

/**
 * 原位的虚线占位框：归位过程中随进度淡出，暗示"位置一直给你留着"。
 *
 * ⚠️ 这里**必须**用 [Modifier.layout] 做像素级定位，不能写
 * `offset { IntOffset(px) }.size(with(density){ px.toDp() })`：
 * 后者一旦密度换算或语义理解有偏差，占位框会整体错位 + 尺寸放大
 * （实测出现过「比书卡大 3 倍、还往上偏 290px」的鬼影框）。
 * layout 里 measure/place 全部走像素，与 rect 同坐标系，零换算、零歧义。
 */
@Composable
private fun PlaceholderBox(
    rect: androidx.compose.ui.geometry.Rect,
    alpha: Float,
) {
    // 虚线轮廓的颜色：MintPrimary 是按主题取值的 @Composable 属性，
    // drawBehind 的 DrawScope 不是组合上下文，必须在外部先取出来。
    val dashColor = MintPrimary
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val w = (rect.right - rect.left).roundToInt().coerceAtLeast(0)
                val h = (rect.bottom - rect.top).roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(placeable.width, placeable.height) {
                    placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                }
            }
            .graphicsLayer { this.alpha = alpha }
            // ⚠️ 只有虚线、**没有任何填充色**。
            // 之前这里有一层 MintPrimary@0.06 的底色（书卡里那块是 surface@0.94），
            // 用户反馈"拖走后原位留下一块纯白/浅色区域，很难看"。
            // 书被拿走的位置就应该**恢复成页面背景**，只留一点轮廓暗示"位子给你留着"。
            .drawBehind {
                drawRoundRect(
                    color = dashColor.copy(alpha = 0.38f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(9.dp.toPx(), 6.dp.toPx()),
                            0f,
                        ),
                    ),
                )
            },
    )
}

/** 拿起的一摞：最多可见 3 层，扇形偏移 + 轻微旋转，右上角数量角标。 */
@Composable
private fun DragStack(
    state: ShelfSelectionState,
    coverOf: @Composable (String) -> Unit,
    reduceMotion: Boolean,
    ghostSize: androidx.compose.ui.geometry.Size? = null,
) {
    val density = LocalDensity.current
    val list = state.dragging.take(4)
    val pointer = state.pointer
    if (pointer == Offset.Unspecified) return

    // 幽灵卡尺寸 = 真实封面尺寸（随列数变化），拿不到时退回 104x132dp。
    // 必须跟归位动画用同一套尺寸，否则松手瞬间会"换一张卡"，出现明显的尺寸跳变。
    val ghostW = ghostSize?.width ?: with(density) { 104.dp.toPx() }
    val ghostH = ghostSize?.height ?: with(density) { 132.dp.toPx() }

    // 拿起：1.0 → 1.08（阻尼偏低，带一次很轻的过冲，像"吸附到手指上"）
    var lifted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lifted = true }
    val lift by animateFloatAsState(
        targetValue = if (lifted) 1.08f else 1f,
        animationSpec = if (reduceMotion) tween(80) else AppMotion.springLift,
        label = "drag_lift",
    )
    val elevation by animateFloatAsState(
        targetValue = if (lifted) 20f else 0f,
        animationSpec = if (reduceMotion) tween(80) else AppMotion.springLift,
        label = "drag_elevation",
    )

    // 惯性倾斜：横向移动越快倾角越大，停手自动回正（iOS 拖图标的手感）
    var tiltTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = state.pointer.x
        while (true) {
            delay(48)
            val cur = state.pointer.x
            val dx = cur - last
            last = cur
            tiltTarget = if (kotlin.math.abs(dx) < 0.6f) 0f else (dx * 0.5f).coerceIn(-9f, 9f)
        }
    }
    val tilt by animateFloatAsState(
        targetValue = if (reduceMotion) 0f else tiltTarget,
        animationSpec = AppMotion.springTilt,
        label = "drag_tilt",
    )
    // 把当前倾角回写给状态机：松手后的飞行动画要接住这个角度当起始值，
    // 否则幽灵卡会在起飞那一刻硬生生摆正一下。
    androidx.compose.runtime.SideEffect { state.ghostTilt = tilt }

    val visible = list.take(3)
    // 倒序绘制：被长按的那本（index 0）压在最上层
    for (index in visible.lastIndex downTo 0) {
        val key = visible[index]
        val rotation = when (index) {
            0 -> 0f
            1 -> -6f
            2 -> 6f
            else -> -3f
        } + tilt * (1f - index * 0.2f)
        // 层叠景深：越靠下的卡片越小、越淡
        val depthScale = when (index) { 0 -> 1f; 1 -> 0.96f; 2 -> 0.92f; else -> 0.9f }
        val stackOffsetX = index * with(density) { 10.dp.toPx() }
        val stackOffsetY = index * with(density) { 6.dp.toPx() }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        // 幽灵卡中心比手指高 10dp（与 DragFlightLayer 的 ghostLiftPx 一致），
                        // 封面才不会被手指挡住。
                        (pointer.x - ghostW / 2f + stackOffsetX).toInt(),
                        (pointer.y - ghostH / 2f - with(density) { 10.dp.toPx() } + stackOffsetY).toInt(),
                    )
                }
                .graphicsLayer {
                    scaleX = lift * depthScale
                    scaleY = lift * depthScale
                    rotationZ = rotation
                    alpha = if (index == 0) 1f else 0.94f
                    shadowElevation = elevation
                }
                .size(width = with(density) { ghostW.toDp() }, height = with(density) { ghostH.toDp() })
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            coverOf(key)
        }
    }
    if (list.size > 1) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (pointer.x + ghostW / 2f - with(density) { 6.dp.toPx() }).toInt(),
                        (pointer.y - ghostH / 2f - with(density) { 18.dp.toPx() }).toInt(),
                    )
                }
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF4D4F)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.dragging.size.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/**
 * 松手之后的飞行层：幽灵卡从松手点飞到目的地 —— 这是"手感"的关键一段。
 *
 * - [DragFlightKind.MOVE]：全程加速 + 缩小到 0.15 并淡出，像被 chip "吸进去"；
 *   落点的 chip 同时做一次果冻回弹（由 [DropDock] 的 pulseTarget 表现）。
 * - [DragFlightKind.RETURN] / [DragFlightKind.FAVORITE]：走一条抛物线回到原位，
 *   用 dampingRatio 0.62 的弹簧 —— 会轻轻越过原位再收住，落位时缩放/阴影/旋转一起收敛，
 *   最后与真实卡片重合后移除（视觉上无跳变），原位虚线占位框随进度淡出。
 */
@Composable
private fun DragFlightLayer(
    flight: DragFlight,
    coverOf: @Composable (String) -> Unit,
    reduceMotion: Boolean,
    /** 每帧重读的「这个条目的真实卡片当前位置」；null 表示拿不到，退回起飞时的快照 */
    liveOrigin: (String) -> androidx.compose.ui.geometry.Rect? = { null },
    onDone: () -> Unit,
) {
    val density = LocalDensity.current
    val returning = flight.kind != DragFlightKind.MOVE

    var started by remember(flight.id) { mutableStateOf(false) }
    LaunchedEffect(flight.id) {
        started = true
        // 归位等弹簧收敛完再移除（此时幽灵卡与真实卡片完全重合，移除不可见）
        delay(if (returning) AppMotion.RETURN_MS else AppMotion.DROP_MS + 80L)
        onDone()
    }

    val p by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = if (reduceMotion) {
            tween(120)
        } else if (returning) {
            AppMotion.springReturn
        } else {
            tween(AppMotion.DROP_MS, easing = AppMotion.easeSuckIn)
        },
        label = "flight_progress",
    )
    // 归位的缩放收敛：拿起时的 1.08 → 卡片真实缩放（选中态是 0.94）。
    // ⚠️ 收到 1.0 的话，落位瞬间幽灵卡比真实卡片大一圈，看着就是"没对齐"。
    val settle by animateFloatAsState(
        targetValue = if (started) flight.settleTo else 1.08f,
        animationSpec = if (reduceMotion) tween(120) else AppMotion.springSettle,
        label = "flight_scale",
    )
    // 倾角从松手瞬间的值接住，再收敛到 0
    val rotation by animateFloatAsState(
        targetValue = if (started) 0f else flight.fromRotation,
        animationSpec = if (reduceMotion) tween(120) else AppMotion.springSettle,
        label = "flight_rotation",
    )

    val pClamped = p.coerceIn(0f, 1f)
    val scale = if (returning) settle else 1.08f * (1f - 0.86f * pClamped)
    // ⚠️ 归位结束前 alpha 一直保持 1：幽灵卡必须完整飞到落点再消失。
    // 之前这里在最后阶段淡出，而真实卡片又已经提前显形，
    // 于是落位前有一小段「两个半透明的书叠在一起」，非常脏。
    val alpha = if (returning) 1f else 1f - ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
    val elevation = if (returning) 20f * (1f - pClamped) else 12f

    // 抛物线：起飞和落地都更"有曲线"，不是一条直线
    val arcPx = with(density) { (if (returning) 26.dp else 34.dp).toPx() }
    val arc = arcPx * kotlin.math.sin((Math.PI * pClamped).toFloat())

    // ── 每一本一条独立的飞行 ──
    //
    // ⚠️ 曾经整摞只画一张幽灵卡（用 items.first() 的封面 + 第一本的原位），
    // 于是"按住时是一摞、松手瞬间塌成一本"，用户明确反馈割裂感很强。
    // 现在按条目逐个画：起点沿用松手瞬间那一摞的扇形偏移（接住手上的样子），
    // 终点是**这一本自己**的原位，落位时各自的景深/旋转/透明度一起收敛回真实卡片。
    val ghosts = flight.items.take(4)
    val stackStepX = with(density) { 10.dp.toPx() }
    val stackStepY = with(density) { 6.dp.toPx() }

    // 归位途中：每个原位各画一个虚线框，随进度淡出，暗示"位置一直留着"。
    // 同样要用实时矩形，否则页面一滚，虚线框就和下落的书分家了。
    if (returning) {
        ghosts.forEach { key ->
            val t = liveOrigin(key) ?: flight.origins[key] ?: flight.origin
            if (t != null) PlaceholderBox(rect = t, alpha = 1f - pClamped)
        }
    }

    // 倒序绘制：被长按的那本（index 0）压在最上层，与 DragStack 保持一致
    for (index in ghosts.lastIndex downTo 0) {
        val key = ghosts[index]
        // 归位落点：以实时矩形为准（卡片在松手后还会因为进入选中态而改尺寸/缩放，
        // 用起飞瞬间的快照就会落在错的地方）。
        val target = if (returning) {
            liveOrigin(key) ?: flight.origins[key] ?: flight.origin
        } else null
        val toX = target?.center?.x ?: flight.to.x
        val toY = target?.center?.y ?: flight.to.y

        val fromX = flight.from.x + index * stackStepX
        val fromY = flight.from.y + index * stackStepY
        // ⚠️ 用实时落点 toX/toY，不是 flight.to 快照
        val cx = fromX + (toX - fromX) * p
        val cy = fromY + (toY - fromY) * p - arc

        // 一摞的景深与扇角：起飞时和拖拽中一模一样，落位时全部收敛回真实卡片的值，
        // 否则最后会比卡片小一圈/歪着一点，看着像"没对齐"。
        val depth = when (index) { 0 -> 1f; 1 -> 0.96f; 2 -> 0.92f; else -> 0.9f }
        val depthNow = 1f + (depth - 1f) * (1f - pClamped)
        val fanRot = when (index) { 0 -> 0f; 1 -> -6f; 2 -> 6f; else -> -3f }
        val layerAlpha = if (index == 0) 1f else 0.94f + 0.06f * pClamped

        // 与真实卡片同一套尺寸（实时矩形），落位时才严丝合缝，不会"落完瞬间换尺寸"。
        val ghostW = target?.width ?: with(density) { 104.dp.toPx() }
        val ghostH = target?.height ?: with(density) { 132.dp.toPx() }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset((cx - ghostW / 2f).toInt(), (cy - ghostH / 2f).toInt())
                }
                .graphicsLayer {
                    scaleX = scale * depthNow
                    scaleY = scale * depthNow
                    rotationZ = rotation + fanRot * (1f - pClamped)
                    this.alpha = alpha * layerAlpha
                    shadowElevation = elevation
                }
                .size(
                    width = with(density) { ghostW.toDp() },
                    height = with(density) { ghostH.toDp() },
                )
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            coverOf(key)
        }
    }

    // 「加入我喜欢的」的迸发动画不在这一层：它比书飞得更久，
    // 挂在这里会在书落位那一刻被硬生生截断。改由宿主独立渲染（HeartBurst）。
}

/**
 * 「加入我喜欢的」的迸发动画。
 *
 * 之前只是贴在幽灵卡右上角的一个圆形徽章，静态、廉价，完全没有"心动"的感觉。
 * 现在是一整套有层次的反馈：
 * - **光晕**：书被点亮的那一瞬，一圈柔和的粉色光从封面扩散开；
 * - **主心形**：沿一条三次贝塞尔曲线向右上方飘走，起飞猛、末端平，
 *   边飘边缩边淡出，还带一点回正的小旋转；
 * - **卫星心**：6 颗小心形放射状散开，角度/距离/延迟各不相同 ——
 *   整整齐齐的六等分一看就很廉价，必须打散；
 * - **残影**：主心形身后拖 4 个渐隐的小心，强化"飞出去"的速度感。
 *
 * ⚠️ 整段独立于归位飞行（1150ms vs 书的 560ms）：心要比书飞得更久，
 * 否则书一落位动画就被截断了。
 */
@Composable
private fun HeartBurst(
    centerX: Float,
    centerY: Float,
    reduceMotion: Boolean,
    onDone: () -> Unit,
) {
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        if (reduceMotion) {
            onDone()
            return@LaunchedEffect
        }
        delay(AppMotion.HEART_BURST_MS.toLong())
        onDone()
    }
    if (reduceMotion) return

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    val risePx = with(density) { 172.dp.toPx() }
    val driftPx = with(density) { 30.dp.toPx() }

    // 主心形轨迹：一条向右上飘的三次贝塞尔（起飞猛、收尾平）
    val path = remember(centerX, centerY, risePx, driftPx) {
        Path().apply {
            moveTo(centerX, centerY)
            cubicTo(
                centerX + driftPx * 0.30f, centerY - risePx * 0.34f,
                centerX + driftPx * 1.35f, centerY - risePx * 0.68f,
                centerX + driftPx, centerY - risePx,
            )
        }
    }
    val measure = remember(path) { PathMeasure().apply { setPath(path, false) } }
    val len = remember(path) { measure.length }

    val t by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(AppMotion.HEART_BURST_MS, easing = AppMotion.easeOutCubic),
        label = "heart_flight",
    )
    // 弹出：带过冲的弹簧，0 → ~1.25 → 1
    val pop by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = AppMotion.springJelly,
        label = "heart_pop",
    )

    val pink = Color(0xFFFF4D6D)
    val fade = (1f - ((t - 0.52f) / 0.48f).coerceIn(0f, 1f))

    // ① 光晕：从封面扩散开
    val haloR = with(density) { 36.dp.toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((centerX - haloR).toInt(), (centerY - haloR).toInt()) }
            .size(with(density) { (haloR * 2f).toDp() })
            .graphicsLayer {
                val s = 0.22f + 1.9f * t
                scaleX = s
                scaleY = s
                alpha = (1f - t) * 0.6f
            }
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(pink.copy(alpha = 0.5f), Color.Transparent))),
    )

    // ② 主心形：沿贝塞尔曲线飞
    val mainSize = with(density) { 34.dp.toPx() }
    @Composable
    fun drawHeart(x: Float, y: Float, scale: Float, alpha: Float, rotation: Float = 0f) {
        Box(
            modifier = Modifier
                .offset { IntOffset((x - mainSize / 2f).toInt(), (y - mainSize / 2f).toInt()) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                    this.alpha = alpha
                    shadowElevation = 10f * alpha
                }
                .size(with(density) { mainSize.toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            // 与「喜欢」按钮 / 「已加入我喜欢的」角标共用同一颗渐变心，别再各画一份
            com.example.ui.favorite.HeartArt(
                modifier = Modifier.fillMaxSize(),
                glow = 0.55f * alpha,
            )
        }
    }

    // ③ 残影：主心身后拖 4 个渐隐的小心
    for (k in 4 downTo 1) {
        val tt = (t - k * 0.045f).coerceIn(0f, 1f)
        val pp = measure.getPosition((tt * len).coerceIn(0f, len))
        drawHeart(
            x = pp.x, y = pp.y,
            scale = pop * (0.42f - k * 0.05f) * fade,
            alpha = fade * (0.30f - k * 0.045f),
        )
    }

    val head = measure.getPosition((t * len).coerceIn(0f, len))
    drawHeart(
        x = head.x, y = head.y,
        scale = pop * (0.8f + 0.2f * fade),
        alpha = fade,
        rotation = (1f - t) * 16f,
    )

    // ④ 卫星心：放射状散开（角度/距离/延迟都打散，避免六等分的廉价感）
    val petals = remember {
        val rnd = kotlin.random.Random(20260922)
        List(6) { i ->
            val ang = Math.toRadians((-104.0 + i * 56.0) + rnd.nextInt(-16, 16).toDouble())
            SatelliteHeart(
                cos = kotlin.math.cos(ang).toFloat(),
                sin = kotlin.math.sin(ang).toFloat(),
                distK = 0.72f + rnd.nextFloat() * 0.55f,
                sizeK = 0.38f + rnd.nextFloat() * 0.26f,
                delay = rnd.nextFloat() * 0.10f,
            )
        }
    }
    val spread = with(density) { 82.dp.toPx() }
    petals.forEach { s ->
        val pt = ((t - s.delay) / (1f - s.delay)).coerceIn(0f, 1f)
        val eased = 1f - (1f - pt) * (1f - pt)
        val d = spread * s.distK * eased
        val sx = centerX + s.cos * d
        // 顺带一点点上飘，别笔直地射出去
        val sy = centerY + s.sin * d - with(density) { 14.dp.toPx() } * eased
        val size = mainSize * s.sizeK
        Box(
            modifier = Modifier
                .offset { IntOffset((sx - size / 2f).toInt(), (sy - size / 2f).toInt()) }
                .graphicsLayer {
                    val sc = pop * s.sizeK * (1f - 0.35f * pt)
                    scaleX = sc
                    scaleY = sc
                    rotationZ = s.cos * 18f
                    alpha = (1f - pt) * 0.95f
                }
                .size(with(density) { size.toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            com.example.ui.favorite.HeartArt(
                modifier = Modifier.fillMaxSize(),
                glow = 0.4f * (1f - pt),
            )
        }
    }
}

private data class SatelliteHeart(
    val cos: Float,
    val sin: Float,
    val distK: Float,
    val sizeK: Float,
    val delay: Float,
)
