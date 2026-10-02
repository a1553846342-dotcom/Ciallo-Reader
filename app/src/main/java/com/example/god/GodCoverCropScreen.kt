package com.example.god

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** 裁剪框最小尺寸（舞台归一化） */
private const val MIN_CROP = 0.1f

/**
 * 神回封面裁剪（全屏）。
 *
 * - 双指缩放 / 拖动 / 90° 旋转；手势带惯性与边界回弹（Animatable + spring）；
 * - 自由比例裁剪框：四角 + 四边可拖动，三分网格线，框外半透明遮罩；
 * - 裁剪框始终被夹在「图片当前显示区域」内，因此不会出现透明边；
 * - 确认时把舞台坐标换算回（旋转后）原图的归一化矩形存进 [CropParams]
 *   —— 与视口无关，换设备 / 重进编辑都不会错位。
 */
@Composable
fun GodCoverCropScreen(
    source: Bitmap?,
    initialCrop: CropParams,
    onCancel: () -> Unit,
    onConfirm: (CropParams) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    /** 旋转后的显示位图（旋转在后台线程算，UI 线程不卡） */
    var rotation by remember { mutableFloatStateOf(initialCrop.rotationDeg) }
    var display by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, rotation) {
        display = withContext(Dispatchers.Default) {
            source?.let { GodCoverEngine.rotate(it, rotation) }
        }
    }

    // 视图变换（编辑态还原用）
    val scale = remember { Animatable(initialCrop.scale.coerceIn(1f, 5f)) }
    val offX = remember { Animatable(initialCrop.offsetX) }
    val offY = remember { Animatable(initialCrop.offsetY) }

    var stageW by remember { mutableFloatStateOf(0f) }
    var stageH by remember { mutableFloatStateOf(0f) }

    // 图片在舞台中的「未变换」矩形（contain 适配 + 居中）
    val fit = remember(display, stageW, stageH) {
        val bmp = display
        if (bmp == null || stageW <= 0f || stageH <= 0f) null
        else {
            val s = min(stageW / bmp.width.toFloat(), stageH / bmp.height.toFloat())
            val w = bmp.width * s
            val h = bmp.height * s
            Rect((stageW - w) / 2f, (stageH - h) / 2f, (stageW - w) / 2f + w, (stageH - h) / 2f + h)
        }
    }

    /** 图片当前显示矩形（含用户缩放/平移），裁剪框的可动边界 */
    val bounds: Rect? = remember(fit, scale.value, offX.value, offY.value, stageW, stageH) {
        val f = fit ?: return@remember null
        if (stageW <= 0f || stageH <= 0f) return@remember null
        val cx = stageW / 2f
        val cy = stageH / 2f
        val s = scale.value
        val l = cx + (f.left - cx) * s + offX.value
        val t = cy + (f.top - cy) * s + offY.value
        Rect(l, t, l + f.width * s, t + f.height * s)
    }

    // 裁剪框（舞台归一化 0..1）
    var rect by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(bounds, stageW, stageH) {
        val b = bounds ?: return@LaunchedEffect
        if (stageW <= 0f || stageH <= 0f) return@LaunchedEffect
        val norm = Rect(b.left / stageW, b.top / stageH, b.right / stageW, b.bottom / stageH)
        if (rect == null) {
            rect = norm
        } else {
            // 图片被缩放/平移后，把裁剪框夹回图片显示区内
            val r = rect!!
            val l = r.left.coerceIn(norm.left, norm.right - MIN_CROP)
            val t = r.top.coerceIn(norm.top, norm.bottom - MIN_CROP)
            rect = Rect(
                l, t,
                r.right.coerceIn(l + MIN_CROP, norm.right),
                r.bottom.coerceIn(t + MIN_CROP, norm.bottom),
            )
        }
    }
    var dragging by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        if (source == null || display == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (source == null) "封面加载失败，请返回重试" else "正在准备图片…",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp, bottom = 84.dp, start = 12.dp, end = 12.dp)
                    .onSizeChanged { stageW = it.width.toFloat(); stageH = it.height.toFloat() }
                    .pointerInput(fit) {
                        // 平移边界 = 缩放后图片超出舞台的部分的一半（未超出则禁止平移）
                        fun maxOffX() = max(0f, ((fit?.width ?: 0f) * scale.value - stageW) / 2f)
                        fun maxOffY() = max(0f, ((fit?.height ?: 0f) * scale.value - stageH) / 2f)
                        detectTransformGestures { _, pan, zoom, _ ->
                            scope.launch {
                                scale.snapTo((scale.value * zoom).coerceIn(1f, 5f))
                                offX.snapTo((offX.value + pan.x).coerceIn(-maxOffX(), maxOffX()))
                                offY.snapTo((offY.value + pan.y).coerceIn(-maxOffY(), maxOffY()))
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = display!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offX.value
                            translationY = offY.value
                        },
                )

                // ⚠️ 覆盖层必须挂在**舞台 Box 内部**：rect 是舞台归一化坐标，
                // 挂到根 Box 的话画布/手柄会按全屏尺寸换算，框和手柄整体错开
                // 舞台内边距（顶部 56dp / 左右 12dp），手柄根本不在框角上。
                rect?.let { r ->
                    CropOverlay(
                        rect = r,
                        stageW = stageW,
                        stageH = stageH,
                        bounds = bounds,
                        showGrid = dragging,
                        onRectChange = { rect = it },
                        onDragStateChange = { dragging = it },
                    )
                }
            }
        }

        /* ── 顶栏 ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0xFF0B0C0E).copy(alpha = 0.88f))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "取消", tint = Color.White)
            }
            Text(
                "裁剪封面",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                rotation = (rotation + 90f) % 360f
                scope.launch {
                    scale.animateTo(1f, GodMotion.springMain())
                    offX.animateTo(0f, GodMotion.springMain())
                    offY.animateTo(0f, GodMotion.springMain())
                }
            }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "旋转 90°",
                    tint = Color.White,
                )
            }
        }

        /* ── 底栏 ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF0B0C0E).copy(alpha = 0.92f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("取消", color = Color.White.copy(alpha = 0.75f))
            }
            TextButton(
                onClick = {
                    val f = fit
                    val r = rect
                    if (f == null || r == null || stageW <= 0f || stageH <= 0f) {
                        onConfirm(
                            CropParams(
                                scale.value, offX.value, offY.value, rotation,
                                0f, 0f, 1f, 1f,
                            ),
                        )
                        return@TextButton
                    }
                    val cx = stageW / 2f
                    val cy = stageH / 2f
                    val s = scale.value.coerceAtLeast(0.0001f)
                    // 舞台坐标 → 未变换显示坐标 → 原图归一化坐标
                    fun mapX(v: Float): Float {
                        val p = v * stageW
                        val p0 = cx + (p - cx - offX.value) / s
                        return ((p0 - f.left) / f.width).coerceIn(0f, 1f)
                    }
                    fun mapY(v: Float): Float {
                        val p = v * stageH
                        val p0 = cy + (p - cy - offY.value) / s
                        return ((p0 - f.top) / f.height).coerceIn(0f, 1f)
                    }
                    onConfirm(
                        CropParams(
                            scale = scale.value,
                            offsetX = offX.value,
                            offsetY = offY.value,
                            rotationDeg = rotation,
                            cropL = mapX(r.left),
                            cropT = mapY(r.top),
                            cropR = mapX(r.right),
                            cropB = mapY(r.bottom),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("完成", color = Color(0xFFF5B942), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 裁剪框覆盖层：框外半透明遮罩 + 三分网格 + 四角四边手柄。
 * 手柄是独立的小 Box，各自消费拖拽手势（位于图片手势之上）。
 */
@Composable
private fun CropOverlay(
    rect: Rect,
    stageW: Float,
    stageH: Float,
    bounds: Rect?,
    showGrid: Boolean,
    onRectChange: (Rect) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    /** 触摸目标 44dp（视觉手柄 ≈24dp 画在正中）：24dp 的命中区在手机上太难捏住 */
    val touchPx = with(density) { 44.dp.toPx() }
    /** 视觉手柄在触摸目标里的占比（24dp / 44dp），画太大会显得笨重 */
    val visualSpan = 0.545f

    // ⚠️ 手柄手势的 lambda 会被 pointerInput 冻结（key=kind 不随重组重启），
    // 直接捕获 rect 参数的话，新手势永远从第一次组合的框算起 —— 第二次拖动
    // 直接弹回初始位置（"裁剪框改不了尺寸"的真凶）。必须走 rememberUpdatedState。
    val currentRect by androidx.compose.runtime.rememberUpdatedState(rect)

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (stageW <= 0f || stageH <= 0f) return@Canvas
        val l = rect.left * size.width
        val t = rect.top * size.height
        val r = rect.right * size.width
        val b = rect.bottom * size.height
        val dim = Color(0xB3000000.toInt())
        drawRect(dim, Offset(0f, 0f), Size(size.width, t))
        drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(dim, Offset(0f, t), Size(l, b - t))
        drawRect(dim, Offset(r, t), Size(size.width - r, b - t))

        drawRect(
            Color.White,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            style = Stroke(width = with(density) { 1.5.dp.toPx() }),
        )
        if (showGrid) {
            val g = Color.White.copy(alpha = 0.42f)
            val sw = with(density) { 1.dp.toPx() }
            for (i in 1..2) {
                val x = l + (r - l) * i / 3f
                drawLine(g, Offset(x, t), Offset(x, b), sw)
                val y = t + (b - t) * i / 3f
                drawLine(g, Offset(l, y), Offset(r, y), sw)
            }
        }
    }

    val normBounds = if (bounds != null && stageW > 0f && stageH > 0f) {
        Rect(bounds.left / stageW, bounds.top / stageH, bounds.right / stageW, bounds.bottom / stageH)
    } else Rect(0f, 0f, 1f, 1f)

    data class HandleSpec(val x: Float, val y: Float, val kind: Int)
    val specs = listOf(
        HandleSpec(rect.left, rect.top, 0),
        HandleSpec(rect.right, rect.top, 1),
        HandleSpec(rect.left, rect.bottom, 2),
        HandleSpec(rect.right, rect.bottom, 3),
        HandleSpec((rect.left + rect.right) / 2, rect.top, 4),
        HandleSpec((rect.left + rect.right) / 2, rect.bottom, 5),
        HandleSpec(rect.left, (rect.top + rect.bottom) / 2, 6),
        HandleSpec(rect.right, (rect.top + rect.bottom) / 2, 7),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        specs.forEach { spec ->
            val isCorner = spec.kind <= 3
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (spec.x * stageW - touchPx / 2f).toDp() },
                        y = with(density) { (spec.y * stageH - touchPx / 2f).toDp() },
                    )
                    .size(with(density) { touchPx.toDp() })
                    .pointerInput(spec.kind) {
                        detectDragGestures(
                            onDragStart = { onDragStateChange(true) },
                            onDragEnd = { onDragStateChange(false) },
                            onDragCancel = { onDragStateChange(false) },
                        ) { _, drag ->
                            // 只读 currentRect（rememberUpdatedState），别读捕获的 rect
                            val cur = currentRect
                            val dx = drag.x / stageW
                            val dy = drag.y / stageH
                            var l = cur.left
                            var t = cur.top
                            var r = cur.right
                            var b = cur.bottom
                            when (spec.kind) {
                                0 -> { l += dx; t += dy }
                                1 -> { r += dx; t += dy }
                                2 -> { l += dx; b += dy }
                                3 -> { r += dx; b += dy }
                                4 -> t += dy
                                5 -> b += dy
                                6 -> l += dx
                                7 -> r += dx
                            }
                            l = l.coerceIn(normBounds.left, r - MIN_CROP)
                            r = r.coerceIn(l + MIN_CROP, normBounds.right)
                            t = t.coerceIn(normBounds.top, b - MIN_CROP)
                            b = b.coerceIn(t + MIN_CROP, normBounds.bottom)
                            onRectChange(Rect(l, t, r, b))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val white = Color.White
                    val sw = with(density) { 2.5f.dp.toPx() }
                    val inset = (1f - visualSpan) / 2f
                    if (isCorner) {
                        drawLine(white, Offset(size.width * inset, size.height * 0.5f),
                            Offset(size.width * (1f - inset), size.height * 0.5f), sw)
                        drawLine(white, Offset(size.width * 0.5f, size.height * inset),
                            Offset(size.width * 0.5f, size.height * (1f - inset)), sw)
                    } else {
                        drawRoundRect(
                            white.copy(alpha = 0.92f),
                            topLeft = Offset(size.width * 0.40f, size.height * 0.40f),
                            size = Size(size.width * 0.20f, size.height * 0.20f),
                            cornerRadius = CornerRadius(with(density) { 3.dp.toPx() }),
                        )
                    }
                }
            }
        }
    }
}
