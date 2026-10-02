package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fi.harism.curl.CurlPage
import fi.harism.curl.CurlRenderer
import fi.harism.curl.CurlView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * harism/android-pagecurl 集成层（vendoring 自 fi.harism.curl，Apache-2.0，Copyright Harri Smatt）。
 *
 * 按用户指示"翻页效果照搬开源，不许再手写"：CURL 模式的卷页本体由 harism CurlView
 * （GLSurfaceView + OpenGL ES 圆柱投影 + 自投影/落影双阴影）渲染，本文件只做"整合"：
 *
 * - [ComicCurlView] 子类：拦截快 tap（点按区/呼出控制栏，不触发卷页）、拖拽超过 slop 后
 *   补投合成 DOWN 再透传（harism 的 onTouch 是无状态流，可安全前置拦截）、
 *   翻页落定回调（onDrawFrame 钩子在索引变化时上报）。
 * - 索引映射：harism 页 = 我们的 spread（双页 spread 组合为单张纹理，整 spread 卷动，
 *   与旧 Canvas 引擎行为一致且规避不规则 spread 的页对映射问题）。
 *   页码保持阅读顺序；RTL 镜像 GL 书本几何与触摸坐标，纹理绘制补偿镜像。
 *   因而前进卷起当前页的左缘、书脊在右侧，文字和双页合绘仍保持原方向。
 * - PageProvider：GL 线程同步回调，从 slotCache（Compose 侧预加载的 loader 位图）合成
 *   信箱式页纹理；正/背面语义 front = 本页，back = 翻页方向上将揭示/离开的相邻页。
 * - 自动阅读：合成事件流（边缘 DOWN → 步进 MOVE → 越中线 UP）驱动完整卷页动画。
 */
class ComicCurlView(context: Context, translucent: Boolean = false) : CurlView(context, translucent) {

    /** 快 tap（未超过 slop 且 <250ms）回调，坐标为视图内像素 */
    var onQuickTap: ((Float, Float) -> Unit)? = null

    /** 长按（未拖拽且 ≥500ms，长按放大关闭时）回调，坐标为视图内像素 */
    var onLongPress: ((Float, Float) -> Unit)? = null

    /** 翻页落定（harism 内部索引变化，仅在动画完成时发生） */
    var onSettledIndex: ((Int) -> Unit)? = null

    /**
     * 缩放手势触发（第 17 条）：双击 / 长按放大 / 双指捏合命中时回调，
     * 由 Compose 侧打开缩放覆盖层（本视图不再参与该手势余下事件）。
     */
    var onZoomGesture: (() -> Unit)? = null

    /** 双击放大开关（第 17 条：随设置实时更新） */
    var doubleTapZoomEnabled = false

    /** 长按放大开关（关闭时长按回落为呼出控制栏） */
    var longPressZoomEnabled = false

    /** 长按呼出控制栏开关（longPressZoom 优先） */
    var longPressPanelEnabled = false

    /** 滑动翻页开关（false = 只响应点按，拖拽不产生卷页） */
    var swipeEnabled = true

    /** GL 首末页外翻在 View 层仲裁，避免 Compose 抢走 AndroidView 的触摸流。 */
    var isAtForwardEdge: (() -> Boolean)? = null
    var isAtBackwardEdge: (() -> Boolean)? = null
    var forwardSign: Float = 1f
    var onChapterEdge: ((Boolean) -> Unit)? = null
    private var startedAtForwardEdge = false
    private var startedAtBackwardEdge = false

    /** 自动翻页合成事件流进行中：吞掉真实触摸避免互相打断 */
    @Volatile
    var autoFlipping = false

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var dragForwarded = false
    private var chapterEdgeActive = false
    private var longPressFired = false

    /** 双击窗口内上一击（第二击 DOWN 直接进入缩放，不再走点按/拖拽） */
    private var lastQuickTapAt = 0L
    private var lastQuickTapX = 0f
    private var lastQuickTapY = 0f
    private var doubleTapActive = false

    /** 双指手势进行中（吞掉余下事件直到全部抬起） */
    private var multiTouch = false

    /** 自动翻页合成拖拽进行中（绕过滑动开关、UP 不判快 tap） */
    private var syntheticDrag = false

    /** 自动翻页合成流的 downTime（真实用户触摸 downTime 不同 → 吞掉防干扰） */
    private var syntheticDownTime = 0L
    private var lastReportedIndex = -1
    private val slopPx = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapWindowMs = 320L
    private var pendingQuickTap: Runnable? = null
    override fun onDetachedFromWindow() {
        pendingQuickTap?.let { removeCallbacks(it) }
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
    private val longPressRunnable = Runnable {
        if (!dragForwarded && !autoFlipping) {
            longPressFired = true
            if (longPressZoomEnabled) onZoomGesture?.invoke()
            else if (longPressPanelEnabled) onLongPress?.invoke(downX, downY)
        }
    }

    override fun onDrawFrame() {
        super.onDrawFrame()
        val idx = currentIndex
        if (idx != lastReportedIndex) {
            lastReportedIndex = idx
            // 第 6 节：翻页落定轻触觉（GL 线程回调，View 方法 post 回 UI 线程执行）
            post {
                if (com.example.ui.feedback.HapticsGate.enabled) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            onSettledIndex?.invoke(idx)
        }
    }

    /**
     * 双页 spread 步进落定（StPageFlip 书脊模型移植的收尾）：
     * - 索引按 ±step（双页 = ±2，整 spread 推进保持配对）；
     * - 落定页"纸张变不透明"：翻页期间背面是低透明透纸观感（第 13 条），
     *   落定瞬间换成本次翻页揭示的新页正面（bright opacify）。
     * 单页（step=1）走父类原版逻辑（其 mesh 交换自身即正确）。
     * 第六轮族 D：整段 mesh 交换持 renderer monitor 原子化（GL 线程不可见中间态）。
     */
    override fun finishAnimation() {
        if (getSpreadStep() <= 1) {
            super.finishAnimation()
            return
        }
        val renderer = getCurlRenderer()
        synchronized(renderer) {
            if (getAnimationTargetEvent() == SET_CURL_TO_RIGHT) {
                // 翻动页落定到右侧：成为新右页（CURL_LEFT = 索引 -= step）
                val landed = getPageCurlMesh()
                val spare = getPageRightMesh()
                renderer.removeCurlMesh(spare)
                if (getCurlStateValue() == CURL_LEFT) {
                    setCurrentIndexSilently(currentIndex - getSpreadStep())
                }
                updatePage(landed.texturePage, currentIndex)
                landed.setRect(renderer.getPageRect(CurlRenderer.PAGE_RIGHT))
                landed.setFlipTexture(false)
                landed.reset()
                renderer.addCurlMesh(landed)
                setPageRightMesh(landed)
                setPageCurlMesh(spare)
            } else if (getAnimationTargetEvent() == SET_CURL_TO_LEFT) {
                // 翻动页落定到左侧：成为新左页（CURL_RIGHT = 索引 += step）
                val landed = getPageCurlMesh()
                val spare = getPageLeftMesh()
                renderer.removeCurlMesh(spare)
                if (getCurlStateValue() == CURL_RIGHT) {
                    setCurrentIndexSilently(currentIndex + getSpreadStep())
                }
                updatePage(landed.texturePage, currentIndex - 1)
                landed.setRect(renderer.getPageRect(CurlRenderer.PAGE_LEFT))
                landed.setFlipTexture(false)
                landed.reset()
                if (isRenderLeftPage()) renderer.addCurlMesh(landed)
                setPageLeftMesh(landed)
                setPageCurlMesh(spare)
            }
            endAnimationState()
        }
    }

    /**
     * View 层手势仲裁（第 17 条）：双指 / 双击 / 长按（按配置）在本视图内拦截并
     * 转入缩放覆盖层；单指拖拽 / 快 tap 走 harism 卷页与点按区。
     * （不能在 GL 视图上叠 Compose 仲裁层：Compose pointerInput 会截获整条
     * 触摸流，interop 的 AndroidView 一个事件都收不到——拖拽翻页完全失效，
     * 故仲裁必须与卷页同层，全部在 View 的 onTouch 内完成。）
     */
    override fun onTouch(view: View, me: MotionEvent): Boolean {
        // 自动翻页合成流进行中：合成事件（downTime 相同）直通 harism 驱动卷页；
        // 真实用户触摸吞掉，避免与合成拖拽互相打断
        if (autoFlipping) {
            return if (me.downTime == syntheticDownTime) {
                super.onTouch(view, me)
            } else {
                true
            }
        }
        // 双指手势激活后：吞掉余下事件直到全部抬起
        if (multiTouch) {
            if (me.actionMasked == MotionEvent.ACTION_UP ||
                me.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (me.pointerCount <= 1) multiTouch = false
            }
            return true
        }
        when (me.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingQuickTap?.let { view.removeCallbacks(it) }
                if (com.example.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "CURLDBG",
                        "DOWN x=${me.x} y=${me.y} ptr=${me.pointerCount} multi=$multiTouch auto=$autoFlipping",
                    )
                }
                downTime = me.downTime
                downX = me.x
                downY = me.y
                dragForwarded = false
                chapterEdgeActive = false
                startedAtForwardEdge = !isAnimating() && isAtForwardEdge?.invoke() == true
                startedAtBackwardEdge = !isAnimating() && isAtBackwardEdge?.invoke() == true
                longPressFired = false
                // 双击窗口内的第二击：直接进缩放，不再走点按/拖拽
                if (doubleTapZoomEnabled &&
                    lastQuickTapAt > 0L &&
                    SystemClock.uptimeMillis() - lastQuickTapAt < doubleTapWindowMs &&
                    hypot(me.x - lastQuickTapX, me.y - lastQuickTapY) < slopPx * 4
                ) {
                    doubleTapActive = true
                    pendingQuickTap?.let { view.removeCallbacks(it) }
                    lastQuickTapAt = 0L
                    if (com.example.BuildConfig.DEBUG) android.util.Log.d("CURLDBG", "double-tap -> zoom")
                    onZoomGesture?.invoke()
                    return true
                }
                doubleTapActive = false
                view.postDelayed(longPressRunnable, 500)
                // 拦截 DOWN：快 tap 不产生卷页；确认拖拽后补投合成 DOWN
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下：接管为缩放手势；若卷页拖拽已在进行，
                // 给 harism 补投 CANCEL 让其按当前卷曲位置落定回正
                if (me.pointerCount >= 2) {
                    if (com.example.BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "CURLDBG",
                            "POINTER_DOWN ptr=${me.pointerCount} x=${me.x} y=${me.y} dragFwd=$dragForwarded",
                        )
                    }
                    view.removeCallbacks(longPressRunnable)
                    if (dragForwarded) {
                        dispatchSynthetic(MotionEvent.ACTION_CANCEL, me.x, me.y, downTime)
                        dragForwarded = false
                    }
                    chapterEdgeActive = false
                    multiTouch = true
                    longPressFired = false
                    onZoomGesture?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (doubleTapActive) return true
                if (chapterEdgeActive) {
                    return true
                }
                if (!dragForwarded) {
                    // 长按已触发（放大/呼出控制层）后继续拖拽：不再启动卷页
                    if (longPressFired) return true
                    val moved = hypot(me.x - downX, me.y - downY)
                    if (moved < slopPx) return true
                    view.removeCallbacks(longPressRunnable)
                    val dx = me.x - downX
                    val dy = me.y - downY
                    val forward = dx * forwardSign
                    if (swipeEnabled && onChapterEdge != null && abs(dx) >= abs(dy) &&
                        (startedAtForwardEdge && forward > 0f || startedAtBackwardEdge && forward < 0f)
                    ) {
                        chapterEdgeActive = true
                        return true
                    }
                    // 禁用滑动翻页只拦真实手势；自动翻页的合成拖拽是显式用户选项，放行
                    if (!swipeEnabled && !syntheticDrag) return true
                    if (com.example.BuildConfig.DEBUG) {
                        android.util.Log.d("CURLDBG", "drag start -> forward to harism (moved=$moved anim=${isAnimating()} idx=${currentIndex})")
                    }
                    dispatchSynthetic(MotionEvent.ACTION_DOWN, downX, downY, downTime)
                    dragForwarded = true
                }
                return super.onTouch(view, me)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                if (chapterEdgeActive) {
                    chapterEdgeActive = false
                    if (me.actionMasked == MotionEvent.ACTION_UP) {
                        val target = comicChapterEdgeTarget(
                            -(me.x - downX) * forwardSign,
                            startedAtBackwardEdge, startedAtForwardEdge,
                            48f * resources.displayMetrics.density,
                        )
                        if (target != 0) onChapterEdge?.invoke(target > 0)
                    }
                    return true
                }
                if (com.example.BuildConfig.DEBUG && me.actionMasked == MotionEvent.ACTION_UP) {
                    android.util.Log.d("CURLDBG", "UP dragFwd=$dragForwarded ptr=${me.pointerCount}")
                }
                if (doubleTapActive) {
                    doubleTapActive = false
                    return true
                }
                if (!dragForwarded) {
                    val quick = me.actionMasked == MotionEvent.ACTION_UP &&
                        me.eventTime - downTime < 250
                    if (quick) {
                        lastQuickTapAt = SystemClock.uptimeMillis()
                        lastQuickTapX = me.x
                        lastQuickTapY = me.y
                        val x = me.x
                        val y = me.y
                        if (doubleTapZoomEnabled) {
                            pendingQuickTap?.let { view.removeCallbacks(it) }
                            pendingQuickTap = Runnable { onQuickTap?.invoke(x, y) }
                            view.postDelayed(pendingQuickTap!!, doubleTapWindowMs)
                        } else onQuickTap?.invoke(x, y)
                    }
                    return true
                }
                return super.onTouch(view, me)
            }
        }
        return super.onTouch(view, me)
    }

    /** 合成事件透传（补投的 DOWN / 自动翻页步进流共用） */
    private fun dispatchSynthetic(action: Int, x: Float, y: Float, downT: Long) {
        val ev = MotionEvent.obtain(downT, SystemClock.uptimeMillis(), action, x, y, 0)
        super.onTouch(this, ev)
        ev.recycle()
    }

    /**
     * 自动翻页：合成完整拖拽流。RTL 前进 = 从左缘向右拖，镜像后的几何仍使用 CURL_RIGHT；
     * LTR 前进 = 从右缘向左拖（CURL_RIGHT）。UP 落点越过中线 → harism 自身
     * 的松手动画完成翻页并触发 onSettledIndex。
     */
    fun startAutoFlip(fromLeft: Boolean, done: () -> Unit) {
        if (autoFlipping) return
        autoFlipping = true
        syntheticDrag = true
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            autoFlipping = false
            syntheticDrag = false
            syntheticDownTime = 0L
            return
        }
        val y = h * 0.5f
        val fromX = if (fromLeft) w * 0.03f else w * 0.97f
        val toX = if (fromLeft) w * 0.65f else w * 0.35f
        val t0 = SystemClock.uptimeMillis()
        syntheticDownTime = t0
        dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, fromX, y, 0))
        val steps = 12
        for (i in 1..steps) {
            val x = fromX + (toX - fromX) * i / steps
            // 事件时间必须单调不减，与真实触摸一致
            val t = max(t0 + 360L * i / steps, SystemClock.uptimeMillis())
            dispatchTouchEvent(MotionEvent.obtain(t0, t, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        val t1 = SystemClock.uptimeMillis() + 20
        dispatchTouchEvent(MotionEvent.obtain(t0, t1, MotionEvent.ACTION_UP, toX, y, 0))
        // harism 松手动画 ~300ms + 落定回调余量
        postDelayed({
            autoFlipping = false
            syntheticDrag = false
            syntheticDownTime = 0L
            done()
        }, 700)
    }
}

/** 控制器：持有 view 与预加载缓存，桥接 Compose 侧状态与 GL 线程的 PageProvider */
internal class ComicHarismController {
    var view: ComicCurlView? = null
    var layout: ComicLayout? = null
    var config: ComicReaderConfig? = null

    @Volatile
    var bookState: ComicBookState = ComicBookState()
    var reversed = false
        set(value) {
            if (field != value) lastAppliedRects = null
            field = value
        }
    var density = 1f

    /** 双页书脊模式（第 12 条）：每 harism 页 = 单张漫画页，步进 2 */
    @Volatile
    var twoPage = false
    @Volatile
    var flatUnits: List<ComicSlot?> = emptyList()

    /** 当前 spread 提示（provider 线程判定"未命中即需补纹理"用，第 4 条） */
    @Volatile
    var currentSpreadHint = -1

    /**
     * 显示代（generation）：布局/配置/目标页任一变化即递增。
     * 异步预加载与纹理重建按发起时的代提交，提交前校验代未变——
     * 快速连续翻页/跳转时旧任务的结果不会覆盖新状态（竞态闪回根治）。
     */
    @Volatile
    var displayGeneration: Long = 0L

    /**
     * 第 4 条（乐观翻页）：当前 spread 的精确纹理未命中时由 provider 线程置位，
     * 预加载 effect 收尾消费——加载完成后强制重建纹理（占位纸面→真实页图）。
     */
    @Volatile
    var pendingRetexture = false

    /** 最近一次应用的显式页面矩形（px）——未变化时跳过 setPageRect 重纹理 */
    @Volatile
    private var lastAppliedRects: Pair<RectF, RectF>? = null

    /**
     * 占位纸面/信箱底色覆盖（第六轮第 2 条）：DYNAMIC 背景下跟随当前主色，
     * 其余类型为 null（回落 [pageBgInt] 的固定映射）。单一来源：
     * 由背景 effect（applyCurlBackground 同一处）写入。
     */
    @Volatile
    var bgFillOverride: Int? = null

    fun fillInt(cfg: ComicReaderConfig): Int = bgFillOverride ?: pageBgInt(cfg)

    /**
     * 应用显式页面矩形（第 1 条：纸张=漫画本体矩形，背景是 GL 之后的静态层）。
     * 矩形来自当前 spread 槽位的已缓存位图（与 Compose 侧排版同构）；
     * 未加载（无内禀尺寸）时清除覆盖，回退整幅/半幅布局（纸面占位）。
     */
    fun applyPageRects(spreadIdx: Int, config: ComicReaderConfig, state: ComicBookState, layout: ComicLayout) {
        val v = view ?: return
        if (v.width <= 0 || v.height <= 0) return
        val spread = layout.spreads.getOrNull(spreadIdx) ?: return
        val rects = curlPageRects(
            twoPage = twoPage, spread = spread, config = config,
            containerW = v.width.toFloat(), containerH = v.height.toFloat(), density = density,
            intrinsicOf = { slot ->
                (getCache(slotCacheKey(slot, config, state)) ?: getCacheAnyVariant(slot.ref.id))
                    ?.let { Size(it.width.toFloat(), it.height.toFloat()) }
            },
        )
        val last = lastAppliedRects
        if (rects != null) {
            if (last != null && last.first == rects.first && last.second == rects.second) return
            lastAppliedRects = rects
            v.setPageRect(rects.first, rects.second)
        } else if (last != null) {
            lastAppliedRects = null
            v.setPageRect(null, null)
        }
    }

    /** LRU 预加载缓存：条数 ≤8 且字节 ≤64MB（防强引用绕过 loader LruCache 字节预算） */
    private val slotCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var cacheBytes = 0L

    @Synchronized
    fun putCache(key: String, bmp: Bitmap) {
        val old = slotCache.put(key, bmp)
        cacheBytes += bmp.byteCount.toLong() - (old?.byteCount?.toLong() ?: 0L)
        trim()
    }

    @Synchronized
    fun getCache(key: String): Bitmap? = slotCache[key]

    /**
     * 同一原始页（refId）任意管线变体的最近缓存位图。
     * 用途：目标页的精确指纹位图尚未解码完成时，避免返回纯色空白纹理
     * （空白闪帧）——同页旧变体（滤镜/旋转略旧）视觉误差远小于空白。
     * LinkedHashMap accessOrder=true：迭代序即 LRU 序，取最后一个命中。
     */
    @Synchronized
    fun getCacheAnyVariant(refId: String): Bitmap? {
        val prefix = "$refId|"
        var best: Bitmap? = null
        for ((k, v) in slotCache) if (k.startsWith(prefix)) best = v
        return best
    }

    @Synchronized
    fun clearCache() {
        slotCache.clear()
        cacheBytes = 0L
    }

    @Synchronized
    fun cacheSize(): Int = slotCache.size

    private fun trim() {
        val it = slotCache.entries.iterator()
        while (it.hasNext() && (slotCache.size > 8 || cacheBytes > 64L * 1024 * 1024)) {
            val e = it.next()
            cacheBytes -= e.value.byteCount.toLong()
            it.remove()
        }
    }

    fun toOur(h: Int): Int {
        return if (twoPage) harismToSpreadTwo(h, flatUnits.size, reversed)
        else ourIndexFor(h, layout?.spreadCount ?: 0, reversed)
    }

    fun toHarism(our: Int): Int {
        return if (twoPage) spreadToHarismTwo(our, flatUnits.size, reversed)
        else harismIndexFor(our, layout?.spreadCount ?: 0, reversed)
    }

    /**
     * 兜底：HARDWARE 位图无法绘入软件画布（抛 IAE 致命崩溃）。解码源已
     * allowHardware(false)，此处防御共享缓存中混入的硬件位图——转软件副本
     * 后绘制；转换失败则返回 null（回退纸面占位，绝不崩溃）。
     */
    private fun softenForSoftware(bmp: Bitmap): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 26 || bmp.config != Bitmap.Config.HARDWARE) return bmp
        return runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    /** GL 线程：合成 spread 纹理（信箱式 + fit 规则 + 双页拼排）。返回的位图所有权移交 harism */
    fun composeSpread(hIdx: Int, w: Int, h: Int, mirrorForRenderer: Boolean = true): Bitmap? {
        val lay = layout ?: return null
        val cfg = config ?: return null
        val spread = lay.spreads.getOrNull(toOur(hIdx)) ?: return null
        // POT 纹理内存控制：短边 ≤1024、长边 ≤1800（RGB_565）
        val scale = min(1f, min(1024f / w, 1800f / h))
        val bw = max(8, (w * scale).toInt())
        val bh = max(8, (h * scale).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(fillInt(cfg))
        if (reversed && mirrorForRenderer) canvas.scale(-1f, 1f, bw / 2f, bh / 2f)
        val slots = spread.slots.take(2).let { if (cfg.direction == ComicDirection.RTL) it.reversed() else it }
        val st = bookState
        // 精确键优先；未命中回退同页任意变体（防空白闪帧，页身份仍正确）
        var exactMiss = false
        val resolved = slots.mapIndexed { i, slot ->
            val exact = getCache(slotCacheKey(slot, cfg, st))
            if (exact == null) {
                exactMiss = true
                if (com.example.BuildConfig.DEBUG) {
                    android.util.Log.d("CURLDBG", "composeSpread MISS exact idx=$hIdx our=${toOur(hIdx)} key=${slotCacheKey(slot, cfg, st)} anyVariant=${getCacheAnyVariant(slot.ref.id) != null} cacheSize=${cacheSize()}")
                }
            }
            val bmpSrc = (exact ?: getCacheAnyVariant(slot.ref.id))?.let { softenForSoftware(it) }
            i to bmpSrc
        }
        val srcs = resolved.mapNotNull { it.second }
        // 第 4 条：当前 spread 纹理未就绪（慢网络）→ 标记待补纹理（占位→真实）
        if (exactMiss && toOur(hIdx) == currentSpreadHint) pendingRetexture = true
        // 未加载槽位画纯色纸面即可：加载指示统一由 Compose 层动画 ChasingDots
        // 承接（此处不再画 GL 静态圈，避免与动画圈叠加出现双圈）
        if (srcs.isEmpty()) {
            if (com.example.BuildConfig.DEBUG) android.util.Log.d("CURLDBG", "composeSpread EMPTY srcs idx=$hIdx our=${toOur(hIdx)} spread=${toOur(hIdx)} cacheSize=${cacheSize()}")
            return bmp
        }
        val gap = if (srcs.size == 2) cfg.doubleGapDp * density else 0f
        // 位置修正（第 24 条）：与 Compose 双页渲染同源——平移量不乘缩放，
        // 直接作用于合成坐标系（density 换算 px），Y 修正平移整体排版基线
        val shiftXPx = cfg.doubleShiftXDp * density
        val shiftYPx = cfg.doubleShiftYDp * density
        // 自定义缩放档（第 26 条）：基础档 + 额外系数（STRETCH 拉伸档不乘系数）
        var effFit = cfg.fit
        var fitExtra = 1f
        if (cfg.fit == ComicFit.CUSTOM) {
            effFit = if (cfg.customFitBase == ComicFit.CUSTOM) ComicFit.FIT_PAGE else cfg.customFitBase
            fitExtra = cfg.customFitScale
        }
        val paint = Paint().apply { isFilterBitmap = true }
        drawSpreadFit(canvas, srcs, bw.toFloat(), bh.toFloat(), gap, effFit, cfg.doubleAlign, paint, shiftXPx, shiftYPx, fitExtra)
        return bmp
    }

    /**
     * 双页背面（第 2 条，StPageFlip 数据模型：flipping page 的 front/back 是
     * 两张不同的页图）：背面 = 被翻纸张物理上的另一面 = 真正相邻的那一页
     * （[adjacentBackFlat]，flat 奇偶定方向，±1 相邻语义）。
     * 越界（封面/封底外）返回 null，调用方回退"透纸"观感。
     */
    fun composeAdjacentUnit(hIdx: Int, w: Int, h: Int): Bitmap? {
        if (!twoPage) return null
        val flatCount = flatUnits.size
        if (flatCount == 0) return null
        val flat = flatUnitIndexFor(hIdx, flatCount, reversed)
        val backFlat = adjacentBackFlat(flat, flatCount) ?: return null
        val bmp = composeUnit(backFlat, w, h) ?: return null
        // harism 折叠几何将背面纹理水平镜像显示（v.mPosX 取负）——预镜像一次，
        // 翻页过程中背面页码/内容正向可读（"清晰看到即将出现的那一页"）
        val m = android.graphics.Matrix().apply { setScale(-1f, 1f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * 双页书脊模式单页纹理（第 12 条）：一个 harism 页 = 一张漫画页，
     * 半屏画布信箱式适配（drawSpreadFit 单源路径）。
     */
    fun composeUnit(hIdx: Int, w: Int, h: Int, mirrorForRenderer: Boolean = true): Bitmap? {
        val cfg = config ?: return null
        // Logical indices stay in reading order; geometry reflects the physical sides.
        val slot = flatUnits.getOrNull(flatUnitIndexFor(hIdx, flatUnits.size, reversed)) ?: return null
        val scale = min(1f, min(1024f / w, 1800f / h))
        val bw = max(8, (w * scale).toInt())
        val bh = max(8, (h * scale).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(fillInt(cfg))
        if (reversed && mirrorForRenderer) canvas.scale(-1f, 1f, bw / 2f, bh / 2f)
        val exact = getCache(slotCacheKey(slot, cfg, bookState))
        if (exact == null) {
            // 第 4 条：当前 spread 纹理未就绪（慢网络）→ 标记待补纹理
            if (harismToSpreadTwo(hIdx, flatUnits.size, reversed) == currentSpreadHint) {
                pendingRetexture = true
            }
        }
        val src = (exact ?: getCacheAnyVariant(slot.ref.id))
            ?.let { softenForSoftware(it) }
        if (src == null) {
            // 加载指示统一由 Compose 层动画 ChasingDots 承接（此处曾画 GL 静态圈，
            // 与动画圈叠加出现双圈，已删除）
            return bmp
        }
        val paint = Paint().apply { isFilterBitmap = true }
        var effFit = cfg.fit
        var fitExtra = 1f
        if (cfg.fit == ComicFit.CUSTOM) {
            effFit = if (cfg.customFitBase == ComicFit.CUSTOM) ComicFit.FIT_PAGE else cfg.customFitBase
            fitExtra = cfg.customFitScale
        }
        drawSpreadFit(canvas, listOf(src), bw.toFloat(), bh.toFloat(), 0f, effFit, cfg.doubleAlign, paint, 0f, 0f, fitExtra)
        return bmp
    }

    /**
     * 背面纹理（第 13 条"浅浅反面"）：单面印刷物理模型——纸的背面透出本页
     * 正面的镜像墨迹：低不透明度（20%）+ 降采样轻模糊 + 纸底色预合成。
     * harism 折叠几何自动做屏幕镜像，此处直接复用正面位图即可。
     */
    fun composeBackTexture(front: Bitmap?, w: Int, h: Int): Bitmap {
        val cfg = config
        val paper = if (cfg != null) (bgFillOverride ?: pagePaperInt(cfg)) else 0xFF1D1D21.toInt()
        val scale = min(1f, min(1024f / w, 1800f / h))
        val bw = max(8, (w * scale).toInt())
        val bh = max(8, (h * scale).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(paper)
        if (front != null) {
            // 1/6 降采样再放大 = 轻模糊（透纸的柔化观感），避免逐像素高斯开销
            val small = Bitmap.createScaledBitmap(front, max(2, bw / 6), max(2, bh / 6), true)
            val soft = Bitmap.createScaledBitmap(small, bw, bh, true)
            val paint = Paint().apply {
                isFilterBitmap = true
                alpha = (0.20f * 255).toInt()   // 20%：透过纸张隐约可见
                isDither = true
            }
            canvas.drawBitmap(soft, 0f, 0f, paint)
        }
        return bmp
    }

    private fun drawSpreadFit(
        canvas: Canvas,
        srcs: List<Bitmap>,
        bw: Float,
        bh: Float,
        gap: Float,
        fit: ComicFit,
        align: ComicDoubleAlign,
        paint: Paint,
        shiftX: Float = 0f,
        shiftY: Float = 0f,
        fitExtra: Float = 1f,
    ) {
        val totalW = srcs.sumOf { it.width }.toFloat()
        val maxH = srcs.maxOf { it.height }.toFloat()
        val s = when (fit) {
            ComicFit.FIT_WIDTH -> (bw - gap) / totalW
            ComicFit.FILL -> max((bw - gap) / totalW, bh / maxH)
            ComicFit.STRETCH -> {
                // 拉伸：每槽各占一半（去间距）
                val half = (bw - gap) / srcs.size
                var x = if (srcs.size == 2) 0f else (bw - half) / 2f
                srcs.forEach {
                    canvas.drawBitmap(it, null, RectF(x + shiftX, shiftY, x + half + shiftX, bh + shiftY), paint)
                    x += half + gap
                }
                return
            }
            else -> min((bw - gap) / totalW, bh / maxH) // FIT_PAGE/FIT_HEIGHT/ORIGINAL：contain
        } * fitExtra
        val totalDrawW = totalW * s
        val drawH = maxH * s
        var x = (bw - totalDrawW) / 2f
        val y = when (align) {
            ComicDoubleAlign.TOP -> 0f
            ComicDoubleAlign.BOTTOM -> bh - drawH
            else -> (bh - drawH) / 2f
        }
        srcs.forEach {
            val rw = it.width * s
            val rh = it.height * s
            // 双页不等高时按 align 基线对齐各自顶点
            val ry = when (align) {
                ComicDoubleAlign.TOP -> y
                ComicDoubleAlign.BOTTOM -> y + drawH - rh
                else -> y + (drawH - rh) / 2f
            }
            canvas.drawBitmap(it, null, RectF(x + shiftX, ry + shiftY, x + rw + shiftX, ry + rh + shiftY), paint)
            x += rw + gap
        }
    }
}

/** 缓存键与预加载侧（rememberPageBitmap 同构）：含单页旋转（修复"旋转本页后 CURL 页空白"） */
internal fun slotCacheKey(slot: ComicSlot, config: ComicReaderConfig, bookState: ComicBookState): String {
    val rot = ((config.bookRotation + (bookState.pageRotations[slot.ref.id] ?: 0)) % 360 + 360) % 360
    return "${slot.ref.id}|${slot.half}|${config.imagePipelineFingerprint()}|r$rot"
}

internal fun pageBgInt(config: ComicReaderConfig): Int = when (config.bgType) {
    ComicBgType.WHITE -> 0xFFFAFAF7.toInt()
    ComicBgType.PAPER -> 0xFFF2EEE6.toInt()
    else -> 0xFF101014.toInt()
}

/**
 * CURL 引擎的背景应用（第六轮第 2 条根治）。
 *
 * 根因：GLSurfaceView 位于窗口之后，一旦出帧，窗口在其边界内被透明打孔，
 * Compose 绘制的背景层（纸张纹理/纯色）永远合成不上来——透明 GL 像素处
 * 露出的是纯黑；而页面矩形未就绪时回退的整幅纸面又把背景色卷进纹理。
 * "背景变黑 / 背景跟着翻 / 假装修好"三种混乱状态皆源于此。
 *
 * 根治：背景进入 GL 场景内部——
 * - 纯色（BLACK/WHITE/GRAY）：不透明 clear color，无 mesh；
 * - 纸张纹理：永不卷曲的全幅背景 mesh（与 Compose 层同一份纹理源平铺）；
 * - 沉浸动态：clear color 跟随主色逐拍更新。
 * 背景从此只有这一条渲染路径，任何翻页/模式/矩形状态都不再影响它。
 *
 * @param dynamicColorArgb 仅 DYNAMIC 时有效（当前主色）
 */
internal fun applyCurlBackground(
    view: CurlView,
    config: ComicReaderConfig,
    dynamicColorArgb: Int?,
    width: Int,
    height: Int,
) {
    when (config.bgType) {
        ComicBgType.BLACK -> { view.setBackgroundColor(0xFF000000.toInt()); view.setBackgroundBitmap(null) }
        ComicBgType.WHITE -> { view.setBackgroundColor(0xFFF7F7F5.toInt()); view.setBackgroundBitmap(null) }
        ComicBgType.GRAY -> { view.setBackgroundColor(0xFF232326.toInt()); view.setBackgroundBitmap(null) }
        ComicBgType.DYNAMIC -> {
            view.setBackgroundColor(0xFF000000.toInt() or (dynamicColorArgb ?: 0xFF101014.toInt()))
            view.setBackgroundBitmap(null)
        }
        ComicBgType.PAPER -> {
            view.setBackgroundColor(0xFFF3ECDF.toInt())   // 纹理之下的兜底底色
            if (width > 0 && height > 0) {
                // 与 Compose 层同一纹理源（单一数据源），平铺到屏幕尺寸；
                // 位图所有权移交 CurlPage（下次推送时被 recycle），必须独立副本，
                // 因此不做缓存（推送仅发生在背景类型/强度/尺寸变化时）
                val src = ComicReaderBackgrounds.paperTextureRaw(config.paperIntensity)
                val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                val c = Canvas(out)
                val p = Paint().apply { isFilterBitmap = false }
                var y = 0
                while (y < height) {
                    var x = 0
                    while (x < width) {
                        c.drawBitmap(src, x.toFloat(), y.toFloat(), p)
                        x += src.width
                    }
                    y += src.height
                }
                view.setBackgroundBitmap(out)
            } else {
                view.setBackgroundBitmap(null)
            }
        }
    }
}

/** Logical page order is unchanged; RTL is handled by the renderer's geometry. */
@Suppress("UNUSED_PARAMETER")
internal fun ourIndexFor(harismIdx: Int, spreadCount: Int, reversed: Boolean): Int =
    harismIdx

/** Units stay in reading order in both directions. */
@Suppress("UNUSED_PARAMETER")
internal fun flatUnitIndexFor(harismIdx: Int, flatCount: Int, reversed: Boolean): Int =
    harismIdx

/**
 * 双页背面目标 flat（纯函数可单测，第六轮第 3 条修正）：
 * 物理书模型——spread [2k,2k+1] 中被翻动的页与其背面是**同一张纸**：
 * 前进侧（flat 为奇，第二槽位）翻起时，纸背面 = 下一 spread 的第一页
 * （flat+1）；回退侧（flat 为偶，第一槽位）翻起时，背面 = 上一 spread
 * 的第二页（flat-1）。旧版 ±2 取的是"目标 spread 同槽位"（p2 背面显示
 * p4），翻页过程先错、落定 finishAnimation 重纹理才纠正——用户实测
 * "背面短暂显示第 4 页、结束闪一下变第 3 页"即此。越界（封面/封底外）
 * 返回 null（回退透纸观感）。
 */
internal fun adjacentBackFlat(flat: Int, flatCount: Int): Int? {
    val back = flat + if (flat % 2 == 1) 1 else -1
    return if (back in 0 until flatCount) back else null
}

/**
 * CURL 页面矩形（第 1 条核心，纯函数可单测）：纸张不再是整幅屏幕，而是
 * 漫画本体在容器中的适配矩形——与 Compose 侧排版同构：
 * - 双页：每页 fitOne（半宽可用+整高 contain）按 Row(gap, align) 排布，
 *   RTL 首读页在右（displaySlots 语义）；
 * - 单页：整 spread 并排 union 走 [fittedSize]（fit 语义与 Compose 单页一致，
 *   FIT_WIDTH 等溢出档超出容器、视口裁切）。
 * 背景（纯色/纸纹/沉浸主色）是 GL 表面之后的静态 Compose 层，永不参与卷曲。
 * 返回 (leftRect, rightRect)（px，屏幕坐标系）；内禀缺失（未加载）返回 null
 * （调用方回退整幅/半幅布局的纸面占位）。
 */
internal fun curlPageRects(
    twoPage: Boolean,
    spread: ComicSpread?,
    config: ComicReaderConfig,
    containerW: Float,
    containerH: Float,
    density: Float,
    intrinsicOf: (ComicSlot) -> Size?,
): Pair<RectF, RectF>? {
    if (containerW <= 0f || containerH <= 0f || spread == null || spread.slots.isEmpty()) return null
    val slots = spread.slots
    return if (twoPage) {
        val rtl = config.direction == ComicDirection.RTL
        val gapPx = config.doubleGapDp * density
        val availW = (containerW - gapPx) / 2f
        fun fitOne(sz: Size?): Size? {
            if (sz == null || sz.width <= 0f || sz.height <= 0f) return null
            val s = min(availW / sz.width, containerH / sz.height)
            return Size(sz.width * s, sz.height * s)
        }
        // 展示位（与 DoubleSpreadContent 同构）：LTR [s0 左, s1 右]；RTL [s1 左, s0 右]
        val leftSlot = (if (rtl) slots.getOrNull(1) else slots.getOrNull(0))
        val rightSlot = (if (rtl) slots.getOrNull(0) else slots.getOrNull(1))
        val szL = fitOne(leftSlot?.let(intrinsicOf))
        val szR = fitOne(rightSlot?.let(intrinsicOf))
        if (szL == null && szR == null) return null
        // 单槽 spread：缺失侧用伙伴同尺寸（同版面骨架的纸面占位）
        val finalL: Size = szL ?: szR!!
        val finalR: Size = szR ?: finalL
        val totalW = finalL.width + finalR.width + gapPx
        val x0 = (containerW - totalW) / 2f
        val shiftX = config.doubleShiftXDp * density
        val shiftY = config.doubleShiftYDp * density
        fun topOf(sz: Size): Float = when (config.doubleAlign) {
            ComicDoubleAlign.TOP -> 0f
            ComicDoubleAlign.BOTTOM -> containerH - sz.height
            else -> (containerH - sz.height) / 2f
        }
        val leftRect = RectF(
            x0 + shiftX, topOf(finalL) + shiftY,
            x0 + finalL.width + shiftX, topOf(finalL) + finalL.height + shiftY,
        )
        val rightRect = RectF(
            x0 + finalL.width + gapPx + shiftX, topOf(finalR) + shiftY,
            x0 + totalW + shiftX, topOf(finalR) + finalR.height + shiftY,
        )
        leftRect to rightRect
    } else {
        val srcs = slots.take(2).mapNotNull { intrinsicOf(it) }
        if (srcs.isEmpty()) return null
        val gap = if (srcs.size == 2) config.doubleGapDp * density else 0f
        val totalW = srcs.fold(0f) { acc, s -> acc + s.width }
        val maxH = srcs.maxOf { it.height }
        // 与 drawSpreadFit contain 同构：可用宽先扣 gap，适配后再把 gap 加回总跨度
        val fitted = if (config.fit == ComicFit.STRETCH) {
            Size(containerW, containerH)
        } else {
            fittedSize(
                Size(totalW, maxH), Size((containerW - gap).coerceAtLeast(1f), containerH),
                config.fit, config.customFitScale, config.customFitBase,
            ).let { Size(it.width + gap, it.height) }
        }
        if (fitted.width <= 0f || fitted.height <= 0f) return null
        val x = (containerW - fitted.width) / 2f
        val y = (containerH - fitted.height) / 2f
        val right = RectF(x, y, x + fitted.width, y + fitted.height)
        val left = RectF(right).apply { offset(-fitted.width, 0f) }
        left to right
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun harismIndexFor(ourIdx: Int, spreadCount: Int, reversed: Boolean): Int = ourIdx

/** 外部页码同步策略（纯函数，可单测） */
enum class CurlSyncPlan { IMMEDIATE_PRELOAD, DEBOUNCE_MERGE, NOOP }

/**
 * 外部 currentSpread 变化 → 视图同步策略（第 1/15/22 条核心逻辑）：
 * - NOOP：目标即当前（用户拖拽落定已先行上报）；
 * - IMMEDIATE_PRELOAD：大跨度跳转（目录/进度条）——先同步解码目标页再 setCurrentIndex，
 *   杜绝"空白一帧再纠正"两段式闪帧；同步等待上限 CURL_IMMEDIATE_WAIT_MS（第 1 条
 *   200ms 预算内），超时先切换（中性纸底），异步预载代校验补齐；
 * - DEBOUNCE_MERGE：相邻步进（快速连续翻页/滑条逐格）——120ms 防抖合并纹理重建。
 */
internal val CURL_IMMEDIATE_WAIT_MS = 170

internal fun curlSyncPlan(targetSpread: Int, lastSyncedSpread: Int, viewCurrentIndex: Int, viewTargetIndex: Int): CurlSyncPlan =
    if (viewCurrentIndex == viewTargetIndex) CurlSyncPlan.NOOP
    else if (lastSyncedSpread >= 0 && abs(targetSpread - lastSyncedSpread) > 2) CurlSyncPlan.IMMEDIATE_PRELOAD
    else CurlSyncPlan.DEBOUNCE_MERGE

/* ═══════════ 第 12 条：双页书脊翻页（StPageFlip 模型移植） ═══════════ */

/**
 * 双页 CURL 扁平单元：每 spread 的槽位按阅读顺序展开为逐页序列；
 * 单槽 spread 补 null 空位保持偶数对齐（书脊两侧一一对应）。
 * 每个 harism 页 = 一张漫画页（不再合成整版纹理），书脊 = 屏幕中线。
 */
internal fun buildCurlFlatUnits(layout: ComicLayout): List<ComicSlot?> {
    val out = mutableListOf<ComicSlot?>()
    layout.spreads.forEach { s ->
        when {
            s.slots.isEmpty() -> { out += null; out += null }
            s.slots.size == 1 -> { out += s.slots[0]; out += null }
            else -> { out += s.slots[0]; out += s.slots[1] }
        }
    }
    return out
}

/** The second reading slot is native PAGE_RIGHT; RTL reflects it onto the left. */
@Suppress("UNUSED_PARAMETER")
internal fun spreadToHarismTwo(spread: Int, flatCount: Int, reversed: Boolean): Int {
    val maxH = (flatCount - 1).coerceAtLeast(1)
    val h = spread * 2 + 1
    return h.coerceIn(1, maxH)
}

/** 两页模式 harism 右页索引 → spread（纯函数） */
@Suppress("UNUSED_PARAMETER")
internal fun harismToSpreadTwo(h: Int, flatCount: Int, reversed: Boolean): Int {
    val k = (h - 1) / 2
    val lastSpread = ((flatCount + 1) / 2 - 1).coerceAtLeast(0)
    return k.coerceIn(0, lastSpread)
}

private fun pagePaperInt(config: ComicReaderConfig): Int = when (config.bgType) {
    ComicBgType.WHITE -> 0xFFEFEDE6.toInt()
    ComicBgType.PAPER -> 0xFFE8E4DC.toInt()
    else -> 0xFF1D1D21.toInt()
}

/**
 * harism GL 卷页阅读器（CURL 模式新本体）。对外签名与旧 ComicCurlReader 一致，
 * 由 ComicPagedReader 在 pageAnim == CURL 时调用。
 */
@Composable
internal fun ComicHarismCurlReader(
    layout: ComicLayout,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    currentSpread: Int,
    onSpreadChanged: (Int) -> Unit,
    gestureCallbacks: ComicGestureCallbacks,
    onBitmapShown: (Bitmap) -> Unit,
    autoRead: Boolean,
    autoIntervalSec: Float,
    goNext: () -> Unit,
    /** 沉浸式主色（第六轮第 2 条：GL 内背景跟随，与 Compose 层同源） */
    dynamicBgColor: Color? = null,
    goPrev: () -> Unit,
) {
    val rtl = config.direction == ComicDirection.RTL
    val n = layout.spreadCount
    // 第 12 条：双页模式 = 书脊翻页模型（每 harism 页一张漫画页，步进 2）；
    // 单页/其余模式 = 整 spread 纹理（步进 1，原 harism 行为）
    val twoPageMode = config.mode == ComicMode.DOUBLE
    val flatUnits = remember(layout, twoPageMode) {
        if (twoPageMode) buildCurlFlatUnits(layout) else emptyList()
    }
    val controller = remember { ComicHarismController() }
    // 第 17 条：缩放覆盖层（双击/长按/双指触发）
    var zoomOverlay by remember { mutableStateOf(false) }
    // 当前 spread 纹理未就绪（慢网络）：GL 纸面只有静止点环（harism 无逐帧重绘通道），
    // Compose 层叠加与书库搜索同款的动画 ChasingDots —— 纹理就绪即刻撤下
    var curlPageLoading by remember { mutableStateOf(false) }
    var curlPageFailed by remember { mutableStateOf(false) }
    var pageRetry by remember { mutableIntStateOf(0) }
    val latestCurrent by rememberUpdatedState(currentSpread)
    val latestOnSpread by rememberUpdatedState(onSpreadChanged)
    val latestGoNext by rememberUpdatedState(goNext)
    val latestGoPrev by rememberUpdatedState(goPrev)
    val latestCallbacks by rememberUpdatedState(gestureCallbacks)
    val latestConfig by rememberUpdatedState(config)
    val latestLayout by rememberUpdatedState(layout)
    val latestBookState by rememberUpdatedState(bookState)

    /* ── 布局/配置变化：更新 controller 并标脏（重载由预加载 effect 完成后触发，
          避免用旧/空缓存立即重建纹理导致"配置变更后当前页空白直至翻页"） ── */
    LaunchedEffect(layout, config.imagePipelineFingerprint(), config.fit, config.doubleAlign, config.doubleGapDp, config.bgType, config.mode, config.direction) {
        controller.layout = layout
        controller.config = config
        controller.reversed = rtl
        controller.twoPage = twoPageMode
        controller.flatUnits = flatUnits
        controller.displayGeneration++   // 旧代预加载/重建结果全部作废
        if (com.example.BuildConfig.DEBUG) android.util.Log.d("CURLDBG", "ctrl-update reversed=$rtl twoPage=$twoPageMode viewIdx=${controller.view?.currentIndex}")
        controller.view?.apply {
            // 先把索引拨到新映射再 setViewMode：否则 setViewMode 内部的 updatePages
            // 会用旧映射的镜像索引查询未加载页（~135ms 深色纸暗帧）。reversed 已更新，
            // toHarism 即正确目标，且缓存键与方向无关、当前页纹理必命中
            setCurrentIndex(controller.toHarism(currentSpread))
            setRightToLeft(rtl)
            // 书脊模式接线：双页 = 两页并排 + 步进2 + 封面封底刚体（StPageFlip hard 页）
            setSpreadStep(if (twoPageMode) 2 else 1)
            setViewMode(if (twoPageMode) CurlView.SHOW_TWO_PAGES else CurlView.SHOW_ONE_PAGE)
        }
        controller.applyPageRects(currentSpread, config, bookState, layout)
    }

    /* ── 第六轮第 2 条：GL 内背景（唯一背景路径）。
          纯色=不透明 clear；纸张=永不卷曲的全幅背景 mesh（与 Compose 层同一纹理源）；
          沉浸动态=clear 跟随主色逐拍更新（Compose 侧 400ms tween 同源驱动）。
          背景从此不依赖窗口合成、不参与任何纹理回退分支 ── */
    var curlViewW by remember { mutableIntStateOf(0) }
    var curlViewH by remember { mutableIntStateOf(0) }
    val latestDynamicBg by rememberUpdatedState(dynamicBgColor)
    LaunchedEffect(config.bgType, config.paperIntensity, dynamicBgColor, curlViewW, curlViewH) {
        val v = controller.view ?: return@LaunchedEffect
        val dynArgb = latestDynamicBg?.let { c ->
            (0xFF shl 24) or
                ((c.red * 255).toInt().coerceIn(0, 255) shl 16) or
                ((c.green * 255).toInt().coerceIn(0, 255) shl 8) or
                (c.blue * 255).toInt().coerceIn(0, 255)
        }
        withContext(Dispatchers.Default) {
            applyCurlBackground(v, config, dynArgb, curlViewW, curlViewH)
        }
        // 占位纸面/信箱底色跟随（DYNAMIC 下与背景同色，不再露固定深色）
        controller.bgFillOverride = if (config.bgType == ComicBgType.DYNAMIC) {
            0xFF000000.toInt() or (dynArgb ?: 0xFF101014.toInt())
        } else null
    }

    /* ── 外部页码变化（目录跳转/底栏按钮/滑条/章节切换）同步到视图：
          用户拖拽落定时视图索引已等于目标（onSettledIndex 先行上报），此处为 no-op；
          仅外部直改 currentSpread 时触发 setCurrentIndex 直跳。
          · 相邻小步（连续快速翻页）：collectLatest + 120ms 防抖合并纹理重建；
          · 大跨度跳转（目录/进度条，|Δ|>2）：先同步解码目标页（等待 ≤ CURL_IMMEDIATE_WAIT_MS，
            约第 1 条 200ms 预算的 85%），纹理就绪后再 setCurrentIndex——杜绝"先空白后正确"
            的两段式闪帧；超时先切中性纸底由异步预载代校验补齐。
          · generation 校验：提交前代未变才执行，旧流的新值不覆盖新状态 ── */
    LaunchedEffect(Unit) {
        var lastSynced = -1
        snapshotFlow { currentSpread }.collectLatest { sp ->
            val gen = controller.displayGeneration
            val target = controller.toHarism(sp)
            val v = controller.view
            when (curlSyncPlan(sp, lastSynced, v?.currentIndex ?: target, target)) {
                CurlSyncPlan.NOOP -> { lastSynced = sp; return@collectLatest }
                CurlSyncPlan.IMMEDIATE_PRELOAD -> {
                    // 大跨度跳转：目标 spread 纹理尽量就绪后再切换，但等待上限 ~170ms
                    // （第 1 条 200ms 预算内）——冷缓存慢解码（如增强引擎开启）时超时也
                    // 先切换：短暂中性纸底后由相邻预载 effect 代校验补齐纹理，与 Pager
                    // 跳转的 Loading 占位语义一致，既不超预算也不会闪错误内容。
                    // 注：CancellationException 必须穿透 runCatching，否则超时取消被吞、
                    // 等待上限失效。
                    withTimeoutOrNull(CURL_IMMEDIATE_WAIT_MS.toLong()) {
                        withContext(Dispatchers.Default) {
                            layout.spreads.getOrNull(sp)?.slots?.forEach { slot ->
                                val key = slotCacheKey(slot, config, bookState)
                                if (controller.getCache(key) == null) {
                                    val rotation = ((config.bookRotation + (bookState.pageRotations[slot.ref.id] ?: 0)) % 360 + 360) % 360
                                    try {
                                        loader.load(
                                            ref = slot.ref,
                                            cacheKey = key,
                                            geo = ComicImagePipeline.Geometry(
                                                half = slot.half,
                                                splitPosition = effectiveSplitPosition(config, loader.sizes.value[slot.ref.id]?.gutterPos),
                                                rotationDeg = rotation, cropMode = config.cropMode, manualCrop = config.manualCrop,
                                            ),
                                            tone = toneOf(config),
                                        )?.let { controller.putCache(key, it.bitmap) }
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        }
                    }
                }
                CurlSyncPlan.DEBOUNCE_MERGE -> delay(120)
            }
            if (gen == controller.displayGeneration) {
                controller.view?.let { vv ->
                    if (com.example.BuildConfig.DEBUG) android.util.Log.d("CURLDBG", "sync commit sp=$sp target=$target viewIdxBefore=${vv.currentIndex}")
                    if (vv.currentIndex != target) vv.setCurrentIndex(target)
                }
                lastSynced = sp
            }
        }
    }

    /* ── 相邻页预加载（当前页优先，±1 跟进），完成后按需刷新纹理。
          key 覆盖全部纹理形态输入：页码/布局/管线指纹/排版（fit/align/gap 影响合成排版）/旋转；
          direction/mode 必须在键里（与上方 controller 更新 effect 的键对齐）——否则
          方向/模式经 displayConfig 防抖落值时 controller 重启置了 textureDirty，而本
          effect（textureDirty 的唯一消费者）不重启，正确的 setCurrentIndex 永不执行：
          视图停留在旧映射镜像索引上查询未加载页 → 深色纸黑屏直到下一次翻页
          （第四轮终审 CURL 活切方向实测发现）。 */
    // 漫画翻译（第十五轮）：烘焙代数入键——译文位图替换后重推纹理
    val translationEpoch = LocalComicTranslationEpoch.current
    LaunchedEffect(
        currentSpread, layout, config.imagePipelineFingerprint(), bookState,
        config.fit, config.doubleAlign, config.doubleGapDp, config.bgType,
        config.direction, config.mode, translationEpoch, pageRetry,
    ) {
        controller.bookState = bookState
        controller.currentSpreadHint = currentSpread
        val gen = ++controller.displayGeneration
        curlPageFailed = false
        // 加载圈判定与 composeSpread 纹理解析同源：精确键或任意变体命中即视为已就绪
        curlPageLoading = layout.spreads.getOrNull(currentSpread)?.slots?.any { slot ->
            controller.getCache(slotCacheKey(slot, config, bookState)) == null &&
                controller.getCacheAnyVariant(slot.ref.id) == null
        } == true
        val targets = listOf(currentSpread, currentSpread + 1, currentSpread - 1)
            .filter { it in layout.spreads.indices }
        var currentShown = false
        // Enhancement previews are display-only and are replaced by the exact final bitmap.
        val previews = launch {
            loader.previewEpoch.collect {
                if (gen != controller.displayGeneration) return@collect
                var changed = false
                layout.spreads.getOrNull(currentSpread)?.slots?.forEach { slot ->
                    val key = slotCacheKey(slot, config, bookState)
                    if (loader.peekProcessed(key) == null) loader.peekReadingPreview(key)?.let {
                        if (controller.getCache(key) !== it) {
                            controller.putCache(key, it)
                            changed = true
                        }
                    }
                }
                if (changed) {
                    controller.applyPageRects(currentSpread, config, bookState, layout)
                    controller.view?.setCurrentIndex(controller.toHarism(currentSpread))
                    curlPageLoading = false
                }
            }
        }
        try {
        withContext(Dispatchers.Default) {
            targets.forEach { si ->
                var failed = false
                layout.spreads[si].slots.forEach { slot ->
                    val key = slotCacheKey(slot, config, bookState)
                    val rotation = ((config.bookRotation + (bookState.pageRotations[slot.ref.id] ?: 0)) % 360 + 360) % 360
                    try {
                        val result = loader.loadForDisplay(
                            ref = slot.ref,
                            cacheKey = key,
                            geo = ComicImagePipeline.Geometry(
                                half = slot.half,
                                splitPosition = effectiveSplitPosition(config, loader.sizes.value[slot.ref.id]?.gutterPos),
                                rotationDeg = rotation, cropMode = config.cropMode, manualCrop = config.manualCrop,
                            ),
                            tone = toneOf(config),
                            visible = si == currentSpread,
                        )
                        controller.putCache(key, result.bitmap)
                        if (si == currentSpread) currentShown = true
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        failed = true
                    }
                }
                // Submit the current page BEFORE waiting for any neighboring network requests.
                if (si == currentSpread) withContext(Dispatchers.Main) {
                    if (gen == controller.displayGeneration) {
                        controller.applyPageRects(currentSpread, config, bookState, layout)
                        if (currentShown) controller.view?.setCurrentIndex(controller.toHarism(currentSpread))
                        layout.spreads.getOrNull(currentSpread)?.slots?.firstOrNull()?.let { slot ->
                            controller.getCache(slotCacheKey(slot, config, bookState))?.let { onBitmapShown(it) }
                        }
                        controller.pendingRetexture = false
                        curlPageFailed = failed
                        curlPageLoading = false
                    }
                }
            }
        }
        } finally {
            previews.cancel()
        }
        // Neighbors only fill caches. Never rebuild the visible texture after waiting
        // for their network requests: a user may already be dragging the next page.
    }

    /* ── 自动翻页（while 循环驱动：翻页成功不改 key 也能继续下一轮，避免冻结） ── */
    if (autoRead && n > 0) {
        LaunchedEffect(autoRead, autoIntervalSec) {
            while (isActive) {
                delay((autoIntervalSec * 1000).toLong())
                if (latestCurrent < n - 1) {
                    val v = controller.view ?: continue
                    v.post { v.startAutoFlip(fromLeft = rtl) { } }
                } else {
                    latestGoNext()
                }
            }
        }
    }

    /* ── GL 生命周期随宿主 ── */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> controller.view?.onPause()
                Lifecycle.Event.ON_RESUME -> controller.view?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            controller.view?.onPause()
            controller.clearCache()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ComicCurlView(ctx, translucent = true).apply {
                    controller.view = this
                    controller.density = resources.displayMetrics.density
                    controller.layout = layout
                    controller.config = latestConfig
                    controller.bookState = bookState
                    controller.reversed = rtl
                    controller.twoPage = twoPageMode
                    controller.flatUnits = flatUnits
                    setRightToLeft(rtl)
                    setAllowLastPageCurl(false)
                    setViewMode(if (twoPageMode) CurlView.SHOW_TWO_PAGES else CurlView.SHOW_ONE_PAGE)
                    setSpreadStep(if (twoPageMode) 2 else 1)
                    // StPageFlip 硬页模型：首/末页（封面封底）刚体翻转（无弯折）
                    setRigidPageDecider(CurlView.RigidPageDecider { idx ->
                        val count = if (controller.twoPage) controller.flatUnits.size else layout.spreadCount
                        idx == 0 || idx == count - 1
                    })
                    setMargins(0f, 0f, 0f, 0f)
                    // 第六轮第 2 条：背景在 GL 场景内部（背景 effect 推送，见上），
                    // clear color 不再透明——透明清屏在窗口打孔下露出的是纯黑
                    // 视口（尺寸/旋转）变化后重推页面矩形（px→view 坐标换算失效）
                    // 与纸张背景平铺尺寸（第六轮）
                    setSizeChangedObserver { w, h ->
                        curlViewW = w
                        curlViewH = h
                        controller.applyPageRects(
                            latestCurrent, latestConfig, latestBookState, latestLayout,
                        )
                    }
                    setPageProvider(object : CurlView.PageProvider {
                        // 动态计数：布局/章节/模式变化后 provider 不会被重建（factory 只跑一次），
                        // 闭包捕获的 n 会过期导致 setCurrentIndex 钳制到旧页数
                        override fun getPageCount(): Int =
                            if (controller.twoPage) controller.flatUnits.size
                            else controller.layout?.spreadCount ?: n

                        override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int) {
                            val front = if (controller.twoPage) {
                                controller.composeUnit(index, width, height)
                            } else {
                                controller.composeSpread(index, width, height)
                            }
                            if (front != null) {
                                page.setTexture(front, CurlPage.SIDE_FRONT)
                                // 背面：双页 = 目标 spread 同槽位真实页图（第 2 条，
                                // StPageFlip 模型，flat 奇偶定方向）；单页 = 透纸观感（第 13 条）
                                val back = if (controller.twoPage) {
                                    controller.composeAdjacentUnit(index, width, height)
                                } else {
                                    null
                                }
                                page.setTexture(
                                    back ?: controller.composeBackTexture(front, width, height),
                                    CurlPage.SIDE_BACK,
                                )
                            } else {
                                page.setColor(pagePaperInt(controller.config ?: latestConfig), CurlPage.SIDE_BOTH)
                            }
                        }
                    })
                    setCurrentIndex(controller.toHarism(currentSpread))
                    onQuickTap = { x, y ->
                        latestCallbacks.onTapZone(Offset(x, y), Size(width.toFloat(), height.toFloat()))
                    }
                    onLongPress = { x, y -> latestCallbacks.onLongPress(Offset(x, y)) }
                    onZoomGesture = { zoomOverlay = true }
                    onSettledIndex = { h ->
                        val our = controller.toOur(h)
                        if (com.example.BuildConfig.DEBUG) android.util.Log.d("CURLDBG", "settled h=$h -> our=$our (twoPage=${controller.twoPage} reversed=${controller.reversed})")
                        if (our in 0 until n && our != latestCurrent) latestOnSpread(our)
                    }
                }
            },
            update = { v ->
                v.swipeEnabled = latestConfig.gestureSwipe
                v.doubleTapZoomEnabled = latestConfig.doubleTapZoom
                v.longPressZoomEnabled = latestConfig.longPressZoom
                v.longPressPanelEnabled = latestConfig.gestureLongPressPanel
                v.setSpreadStep(if (twoPageMode) 2 else 1)
                v.forwardSign = if (rtl) 1f else -1f
                v.isAtForwardEdge = { latestCurrent >= latestLayout.spreadCount - 1 }
                v.isAtBackwardEdge = { latestCurrent == 0 }
                v.onChapterEdge = { next -> if (next) latestGoNext() else latestGoPrev() }
            },
        )

        /* ── 第 17 条 手势仲裁在 ComicCurlView.onTouch（View 层）完成：
              双指/双击/长按 → onZoomGesture 打开下方缩放覆盖层；
              单指拖拽/快 tap 由 GL 卷页原生处理。
              （不能在 GL 视图上叠 Compose 仲裁层：Compose pointerInput 会截获
              整条触摸流，interop AndroidView 收不到任何事件，拖拽翻页失效） ── */
        // 页面纹理加载中：动画加载圈（书库搜索同款 ChasingDots）盖在 GL 纸面之上。
        // 纯展示层无 pointerInput —— 不参与命中测试，触摸穿透回 GL 卷页视图
        if (curlPageLoading && !zoomOverlay) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.94f },
                contentAlignment = Alignment.Center,
            ) {
                ComicLoadingFeedback(layout.spreads.getOrNull(currentSpread)?.slots?.firstOrNull()?.ref) { pageRetry++ }
            }
        }
        if (curlPageFailed && !zoomOverlay) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本页加载失败", color = Color(0xFFFF9A9A))
                    TextButton(onClick = { pageRetry++ }) { Text("点击重试", color = Color.White) }
                }
            }
        }
        if (zoomOverlay) {
            val v = controller.view
            val bmp = remember(zoomOverlay, currentSpread, layout) {
                if (v != null && v.width > 0) {
                    val hIdx = controller.toHarism(currentSpread)
                    controller.composeSpread(hIdx, v.width, v.height, mirrorForRenderer = false)
                } else null
            }
            ComicCurlZoomOverlay(
                bitmap = bmp,
                config = config,
                onDismiss = { zoomOverlay = false },
            )
        }
    }
}

/**
 * 缩放覆盖层（第 17 条）：仿真翻页模式下双击/长按/双指进入，
 * 复用 ComicZoomState 全套手势（双指缩放、平移、双击档位、惯性、回弹）。
 * 单击（未放大时）或捏合收拢退出，回到卷页模式。
 */
@Composable
private fun ComicCurlZoomOverlay(
    bitmap: Bitmap?,
    config: ComicReaderConfig,
    onDismiss: () -> Unit,
) {
    val zoomState = remember { ComicZoomState() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val container = Size(maxWidth.value * density.density, maxHeight.value * density.density)
        LaunchedEffect(bitmap, container, config.fit, config.customFitScale, config.customFitBase) {
            if (bitmap != null) {
                val intrinsic = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                zoomState.containerSize = container
                zoomState.contentSize = fittedSize(intrinsic, container, config.fit, config.customFitScale, config.customFitBase)
                zoomState.intrinsicSize = intrinsic
            }
        }
        val callbacks = ComicGestureCallbacks(
            onTapZone = { _, _ -> if (!zoomState.isZoomed) onDismiss() },
            onLongPress = { },
            onPinchClose = { onDismiss() },
            onEdgeBack = { onDismiss() },
        )
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { zoomState.containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .comicZoomable(state = zoomState, config = config, callbacks = callbacks)
        ) {
            bitmap?.let {
                ZoomableImageLayer(zoomState, config.fit) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "放大查看",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.example.ui.components.ChasingDots(
                    size = 52.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Text(
            "双指缩放 · 双击切换档位 · 单击退出",
            color = Color(0xAAFFFFFF),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )
    }
}
