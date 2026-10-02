package com.example.ui.shelf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.example.ui.feedback.HapticKind
import kotlinx.coroutines.withTimeout

/**
 * 书架手势状态机（设计稿 §3.1）：
 *
 * ```
 * 浏览态 --长按350ms--> 多选态 --点击--> 切换选中
 *    |                    |--长按未选中--> 选中并继续滑动连选
 *    |                    |--长按已选中250ms--> 拖拽态
 * 拖拽态 --松手有效--> 放置动画 + 执行 + 撤销 Snackbar
 *       --松手无效--> 弹簧回弹，保持选中
 * ```
 * 纯状态、无 Android 依赖，可单测；动画与业务逻辑分离（UI 只读状态）。
 */
enum class ShelfPhase { BROWSE, SELECTING, DRAGGING }

/**
 * 本次多选名单属于哪个板块。
 *
 * 用户明确要求：「我的书架」和「我喜欢的」是**完全独立**的两个栏目，
 * 多选名单绝不能混在一起（原来顶部「已选择 N 本」会把两边相加，
 * 底部操作栏还得写一堆"混选"分支来兜）。
 *
 * 进入多选时由**第一个被长按的条目**决定本次归属；之后所有会改动名单的手势，
 * 都必须先过 [ShelfSelectionState.accepts]，另一板块的卡片一律不受理。
 */
enum class ShelfSelectionScope { BOOK, FAV }

class ShelfSelectionState {
    /** 当前阶段 */
    var phase: ShelfPhase by mutableStateOf(ShelfPhase.BROWSE)
        private set

    /**
     * 本次多选名单的归属板块；`null` = 还没进入多选（浏览态）。
     *
     * 由 [enterSelection] 依据第一个被长按的 key 的前缀设定，[exitSelection] 清空。
     * 定下来之后名单就"锁"在单一板块里，见 [accepts]。
     */
    var scope: ShelfSelectionScope? by mutableStateOf(null)
        private set

    /** 由 key 前缀判定它属于哪个板块（`fav::` → 收藏，其余 → 书架）。 */
    private fun scopeOf(key: String): ShelfSelectionScope =
        if (isFavShelfKey(key)) ShelfSelectionScope.FAV else ShelfSelectionScope.BOOK

    /**
     * 这个 key 能不能进本次名单。
     *
     * 浏览态（[scope] == null）一律放行 —— 还没定板块；
     * 一旦定了板块，就只收本板块的 key。这就是「两个栏目完全独立」的落点：
     * 在书架里长按进入多选后，点/划到「我喜欢的」的卡片会被挡在这里，
     * 名单里永远只有一种 key。
     */
    fun accepts(key: String): Boolean = when (scope) {
        null -> true
        ShelfSelectionScope.FAV -> isFavShelfKey(key)
        ShelfSelectionScope.BOOK -> !isFavShelfKey(key)
    }

    /** 已选中的条目 key */
    var selected: Set<String> by mutableStateOf(emptySet())
        private set

    /** 正在被拖拽的 key（被长按的那本在最上层） */
    var dragging: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * 正在「飞回原位」的 key。
     *
     * ⚠️ 松手那一瞬间 `dragging` 就清空了，而幽灵卡还要飞 ~560ms 才落位。
     * 只按 dragging 判定可见性的话，真实卡片会在松手瞬间就浮现出来 ——
     * 于是屏幕上同时出现「原位已经站好的书」和「还在半空飞的书」，
     * 而且两者位置不一样，看着像有两个副本。
     * 所以归位飞行期间真实卡片必须继续藏着，等幽灵卡落位、两者完全重合再显示。
     */
    var flyingHome: Set<String> by mutableStateOf(emptySet())
        private set

    /** 松手瞬间幽灵卡的倾角，交给飞行动画做起始值（否则起飞会"啪"地摆正一下） */
    var ghostTilt: Float by mutableStateOf(0f)
        internal set

    /** 手指位置（宿主局部坐标） */
    var pointer: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /** 当前悬停的放置目标 id */
    var hoverTarget: String? by mutableStateOf(null)
        private set

    /** 边缘自动滚动速度（px / 帧；由宿主用 LaunchedEffect 驱动 scrollBy） */
    var edgeScroll: Float by mutableStateOf(0f)
        private set

    /**
     * 上一次命中的落点（迟滞用）。
     *
     * 手指停在两个胶囊的缝隙或边缘上时，逐事件的微小抖动会让高亮来回闪。
     * 已经命中的目标给它一点"粘性"：要移出矩形外 [HYSTERESIS] 才算真的离开。
     */
    private var lastHoverId: String? = null

    /** 上一次的手指横坐标（算跟手倾角的速度用） */
    private var lastDragX: Float = 0f

    /** 清掉所有拖拽期的临时判定状态 */
    private fun resetDragHitState() {
        lastHoverId = null
    }

    private companion object {
        /** 落点迟滞（dp）：已高亮的胶囊要移出这么多才肯让位 */
        const val HYSTERESIS = 12f

        /** 边缘自动滚动的感应带宽度（dp） */
        const val EDGE_BAND_DP = 96f
    }

    /**
     * 落点命中（root 坐标）。
     *
     * ⚠️ 除了"手指在矩形里"，还必须要求这个胶囊**完整可见**
     * （[Rect.top] 不低于宿主顶、[Rect.bottom] 不超出宿主底）。
     * 只校验中心点的话，滚到一半、只从顶/底露出半截的胶囊也会被命中 ——
     * 手指明明停在空白处，却触发了分类（用户：「有时候拖动会触发页面上下滑动 /
     * 手指不是刚好在分类之上也触发了」）。
     */
    private fun pickTarget(root: Offset, hostTop: Float, hostBottom: Float): String? {
        val hitRect = targetRects.values.firstOrNull {
            it.contains(root) && it.top >= hostTop && it.bottom <= hostBottom
        } ?: return null
        return targetRects.entries.firstOrNull { it.value == hitRect }?.key
    }

    /**
     * 手指正按在哪一条上（仅用于按压视觉）。
     * 手势已全部收归宿主：条目自己不再挂 pointerInput，
     * 由宿主在 down 时命中、抬手/取消时清空。
     */
    var pressedKey: String? by mutableStateOf(null)
        private set

    /** 条目几何（宿主局部坐标） */
    private val itemRects = HashMap<String, Rect>()
    /**
     * **封面**几何（宿主局部坐标）。
     *
     * 归位动画必须用这个而不是 [itemRects]：itemRects 登记的是整张卡（封面 + 间距 + 书名），
     * 它的中心比封面中心低；而跟手的幽灵卡画的是封面，两者差半张封面左右 ——
     * 表现就是"归位落点明显偏低，动画结束瞬间书从下面瞬移到上面"。
     *
     * 另外幽灵卡尺寸也必须取自这里（真实封面尺寸随列数变化），
     * 写死 104x132dp 在 4/5/6 列机型上会与真实封面差一圈。
     */
    private val coverRects = HashMap<String, Rect>()
    /** 放置目标几何（分类 Chip / ♡） */
    private val targetRects = HashMap<String, Rect>()

    /** 滑动连选已"划过"的条目（滑回可取消） */
    private val slideVisited = LinkedHashSet<String>()

    /**
     * 本次多选的**锚点**（长按选中的那一本）。
     *
     * 为什么需要它：长按选中 A 后手指仍按住，只要微动一下跟踪循环就会再次命中 A，
     * 而 A 已在 slideVisited 里 → slideAcross 判定"滑回来了" → 把 A **取消选中**。
     * 用户看到的就是"长按明明选中了，下一瞬又没了"，而且时有时无（取决于手指动没动）。
     * 锚点不允许被滑回取消 —— 要取消就抬手再点，或者点 ✕ 退出。
     */
    private var anchorKey: String? = null

    /**
     * 滑动连选**上一次命中**的条目（边缘触发用）。
     *
     * 锚点保护只救得了第一本：手指划到第二本 B 后停在它上面，轻微抖动的每个
     * move 事件都会再次命中 B —— 电平触发的 slideAcross 会把 B 在"选中/取消"
     * 之间来回切（用户报的"第二本一直在选中和未选中之间跳跃闪烁"）。
     * 连选必须只在命中条目**变化**时切换（见宿主跟踪循环），这个字段就是
     * 上一次命中的记录：进入多选时置为锚点，退出时清空。
     */
    internal var lastSlideKey: String? = null
        internal set

    /** 最近一次由长按自动选中的时刻（条目自身的 onClick 要忽略紧随其后的 up，避免二次反选） */
    private var lastAutoSelectAt: Long = 0L

    fun markAutoSelect() {
        lastAutoSelectAt = android.os.SystemClock.uptimeMillis()
    }

    fun isRecentAutoSelect(): Boolean =
        android.os.SystemClock.uptimeMillis() - lastAutoSelectAt < 500L

    /**
     * 登记（或补登记）一张卡片的矩形。
     *
     * ⚠️ 为什么需要"补登记"：`onGloballyPositioned` 只在**布置**时回调，
     * 而 LazyGrid 会**复用槽位** —— 切换分类时，0 号槽位原来放的是 A 书，
     * 现在放 B 书，位置**一模一样** ⇒ 没有布置变化 ⇒ 回调**根本不触发**
     * ⇒ B 的矩形永远没登记 ⇒ 点它没反应、长按也选不中（用户报的「打不开书籍、
     * 而且选中不了」就是这条）。所以每次组合都要用上一次布置得到的坐标补登一次。
     */
    fun registerItem(key: String, c: androidx.compose.ui.layout.LayoutCoordinates?) {
        if (c == null) return
        val p = c.positionInRoot()
        itemRects[key] = Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
    }

    fun itemBoundsModifier(key: String): Modifier = Modifier.onGloballyPositioned { c ->
        registerItem(key, c)
    }

    /** 封面专用几何登记（归位动画的落点依据） */
    fun coverBoundsModifier(key: String): Modifier = Modifier.onGloballyPositioned { c ->
        val p = c.positionInRoot()
        coverRects[key] = Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
    }

    fun dropTargetModifier(id: String): Modifier = Modifier.onGloballyPositioned { c ->
        val p = c.positionInRoot()
        targetRects[id] = Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
    }

    /**
     * 按"当前**真实存在**的条目 / 落点"裁剪三张几何表。
     *
     * ⚠️ 这是必须的：`onGloballyPositioned` 只有布置回调、**没有** dispose 回调，
     * 条目从列表里消失（比如被移动到别的分类）后，它的矩形会**永久留在表里**。
     * 于是那块区域变成一个"幽灵热区"：
     *  - 点它 → [hitTestItem] 返回一个已经不存在的 key → 业务层找不到这本书
     *    → 点了完全没反应（用户：「移动后无法点进去」）；
     *  - 列表重排后后面的书填补上来，真实矩形与幽灵矩形重叠，而 [hitTestItem] 用
     *    `firstOrNull` 按 HashMap 的**不确定**迭代顺序取 → 时中时不中
     *    （用户：「无法长按其他书后点击它选中」）；
     *  - 更糟的是落点表：删掉的分类/改名的分类矩形还在，手指划过那块**空白**会命中
     *    一个不存在的分类名 → 书被写上这个分类 → 在**任何**分类里都看不到
     *    （用户：「一些书莫名其妙找不到」）。
     *
     * 所以每次数据变化后都要按真实集合裁剪一遍。滚出屏幕的条目不受影响：
     * 它的 key 仍在 aliveItems 里，滚回来时 onGloballyPositioned 会重新登记。
     */
    fun pruneGeometry(aliveItems: Set<String>, aliveTargets: Set<String>) {
        itemRects.keys.retainAll(aliveItems)
        coverRects.keys.retainAll(aliveItems)
        targetRects.keys.retainAll(aliveTargets)
    }

    /**
     * 当前**真实存在**的条目 key 名单（由宿主按数据注入）。
     *
     * 命中测试在这里**当场**过滤，而不是只靠后台裁剪：
     * 裁剪是 LaunchedEffect，总要等到下一帧才跑；在那一帧里手指点下去，
     * 命中的就还是那个已经不存在的旧 key —— 表现就是「点不动、选不中」。
     * 名单为空（还没注入）时不拦截，退回旧行为，避免把整页手势打死。
     */
    var aliveKeys: Set<String> by mutableStateOf(emptySet())

    fun hitTestItem(root: Offset): String? {
        val alive = aliveKeys
        val cached = itemRects.entries.firstOrNull { (k, r) ->
            (alive.isEmpty() || k in alive) && r.contains(root)
        }?.key
        if (cached != null) return cached
        // 缓存没命中（卡片换了 key、坐标回调还没补上）→ 直接问列表要答案
        val fb = fallbackHit?.invoke(root) ?: return null
        return if (alive.isEmpty() || fb in alive) fb else null
    }

    /** 命中兜底：由页面按列表的真实布局信息给出（见 HomeScreen.booksFallbackHit） */
    var fallbackHit: ((Offset) -> String?)? by mutableStateOf(null)

    fun targetRect(id: String): Rect? = targetRects[id]
    fun itemRect(key: String): Rect? = itemRects[key]
    /** 封面矩形（root 坐标）；没有登记过就退回整卡矩形 */
    fun coverRect(key: String): Rect? = coverRects[key] ?: itemRects[key]

    /* ─── 状态迁移 ─── */

    fun select(key: String) {
        // 最后一道闸：另一板块的 key 一律不收。
        // 手势层（宿主的点击/长按/滑动连选）已各自判过一遍，这里是兜底 ——
        // 以后新增任何手势路径也不会把两个栏目的名单混起来。
        if (!accepts(key)) return
        selected = selected + key
        slideVisited.add(key)
    }

    fun unselect(key: String) {
        // 取消选中不加闸门：无论如何都要能把自己已选的东西摘掉
        selected = selected - key
        slideVisited.remove(key)
    }

    fun toggle(key: String) {
        if (key in selected) unselect(key) else select(key)
    }

    /** 长按第一本：进入多选态，它同时是本次的锚点（不允许被滑回取消，见 [anchorKey]） */
    fun enterSelection(key: String) {
        phase = ShelfPhase.SELECTING
        // ⚠️ 这里是「BROWSE → SELECTING」的唯一入口，也是**唯一**给本次多选定板块的地方：
        // 在书架里长按 → 本次只选书；在「我喜欢的」里长按 → 本次只选收藏。
        // 之后 [accepts] 就按这个 scope 把另一板块的卡片全部挡在名单外。
        scope = scopeOf(key)
        selected = setOf(key)
        slideVisited.clear()
        slideVisited.add(key)
        anchorKey = key
        lastSlideKey = key
        pressedKey = null
    }

    /** 「全选」：把传进来的这一批都选上（书架/收藏各自调用，互不干涉） */
    fun selectAll(keys: List<String>) {
        if (phase == ShelfPhase.BROWSE) phase = ShelfPhase.SELECTING
        // 保险：理论上到不了（顶栏只在多选态可见，那时 scope 已定），
        // 但万一被别处调用，就用这批 key 补定一个板块，而不是放行混选。
        if (scope == null) scope = keys.firstOrNull()?.let { scopeOf(it) }
        // 按板块过滤后再并入：即使调用方把两边的 key 混着传进来，名单也只会收本板块的。
        val accepted = keys.filter { accepts(it) }
        selected = selected + accepted
        slideVisited.addAll(accepted)
        if (anchorKey == null) anchorKey = accepted.firstOrNull()
    }

    /** 滑动连选：划过就选中，滑回来就取消；锚点不参与 */
    fun slideAcross(key: String) {
        if (key == anchorKey) return
        if (key in slideVisited) unselect(key) else select(key)
    }

    fun exitSelection() {
        phase = ShelfPhase.BROWSE
        selected = emptySet()
        dragging = emptyList()
        slideVisited.clear()
        anchorKey = null
        lastSlideKey = null
        hoverTarget = null
        edgeScroll = 0f
        resetDragHitState()
        flyingHome = emptySet()
        pointer = Offset.Unspecified
        pressedKey = null
        // 退出多选 → 板块归属一并清空，下次长按重新按第一个条目定
        scope = null
    }

    /** 拿起：被长按的那本放最上层，其余选中的跟着一起飞 */
    fun beginDrag(picked: String, at: Offset) {
        // 被长按的那本放最上层
        val rest = selected.filter { it != picked }
        dragging = listOf(picked) + rest
        pointer = at
        resetDragHitState()
        phase = ShelfPhase.DRAGGING
    }

    fun moveDrag(
        at: Offset,
        hostTop: Float,
        hostBottom: Float,
        hostOffset: Offset = Offset.Zero,
        density: Float = 1f,
    ) {
        pointer = at
        // 一切判定统一到 root 坐标：
        // targetRects 是用 positionInRoot() 登记的，hostTop/hostBottom 也是 root 的，
        // 而手指的 at 是宿主局部坐标 —— 三者混算会整体偏一个宿主位移量。
        val root = at + hostOffset

        // 落点命中（带迟滞：已经高亮的那个，要移出它外扩 HYSTERESIS 的范围才肯让位，
        // 否则手指停在两个胶囊的缝隙上会来回闪）
        val hit = pickTarget(root, hostTop, hostBottom)
        val kept = if (hit == null && lastHoverId != null) {
            val r = targetRects[lastHoverId]
            val pad = HYSTERESIS * density
            if (r != null && root.x >= r.left - pad && root.x <= r.right + pad &&
                root.y >= r.top - pad && root.y <= r.bottom + pad
            ) {
                lastHoverId
            } else {
                null
            }
        } else {
            null
        }
        val now = hit ?: kept
        if (now != lastHoverId) lastHoverId = now
        hoverTarget = now

        // 边缘自动滚动：靠近上下边缘时给一个 px/帧 的速度，由宿主的 LaunchedEffect 驱动
        val edge = EDGE_BAND_DP * density
        val speed = 16f * density
        val rootY = root.y
        edgeScroll = when {
            rootY < hostTop + edge -> -speed
            rootY > hostBottom - edge -> speed
            else -> 0f
        }

        // 跟手倾角：横向移动越快歪得越多，松手时作为飞行动画的起始值
        val dx = at.x - lastDragX
        lastDragX = at.x
        ghostTilt = (ghostTilt * 0.7f + (dx / (18f * density)) * 0.3f).coerceIn(-14f, 14f)
    }

    /** 松手：交出（命中的落点, 被拖起的条目）并回到多选态（选中保留） */
    fun endDrag(): Pair<String?, List<String>> {
        val target = hoverTarget
        val items = dragging
        dragging = emptyList()
        hoverTarget = null
        lastHoverId = null
        edgeScroll = 0f
        ghostTilt = 0f
        lastDragX = 0f
        phase = ShelfPhase.SELECTING
        pointer = Offset.Unspecified
        return target to items
    }

    /** 取消拖拽（返回键 / 手势被抢）：不落到任何地方，由调用方决定怎么归位 */
    fun cancelDrag() {
        dragging = emptyList()
        hoverTarget = null
        lastHoverId = null
        edgeScroll = 0f
        ghostTilt = 0f
        lastDragX = 0f
        phase = ShelfPhase.SELECTING
        pointer = Offset.Unspecified
    }

    /** 归位飞行开始：这些条目在落位前必须保持隐藏 */
    fun beginReturnFlight(items: List<String>) {
        flyingHome = items.toSet()
    }

    /** 归位飞行结束：幽灵卡与真实卡片此刻完全重合，可以交班了 */
    fun endReturnFlight() {
        flyingHome = emptySet()
    }

    /** 宿主在 down/up 时维护按压态（条目自身不再处理手势） */
    internal fun setPressed(key: String?) {
        pressedKey = key
    }
}

@Composable
fun rememberShelfSelectionState(): ShelfSelectionState = remember { ShelfSelectionState() }

/**
 * 放置结果的落点水槽：Modifier 内的 pointerInput 不是可组合作用域，
 * 用这个稳定对象把「松手时的业务回调」从组合层带进手势协程（并随重组更新）。
 */
/**
 * 手势回调水槽。
 *
 * ⚠️ `Modifier.pointerInput` 的协程**只在 key 变化时重启**，而它捕获的 lambda 是
 * 组合时那一刻的闭包 —— 不这样"每次组合把最新回调换进来"，手势循环会一直用
 * 第一次组合那一版闭包。后果（用户报的「移动到新分类后就打不开书、也选不中」）：
 * onTap 里持有的 sortedBooks 还是**旧分类**的书单，命中到的书在旧书单里找不到
 * ⇒ 点了完全没反应；长按选中的也是一个空壳 key。
 * 和 [DropSink] 一个套路：Modifier 内不是可组合作用域，靠这个稳定对象带进去。
 */
class ShelfGestureSink {
    var hostOffset: () -> Offset = { Offset.Zero }
    var hostTop: () -> Float = { 0f }
    var hostBottom: () -> Float = { 0f }
    var enabled: () -> Boolean = { true }
    var haptic: (HapticKind) -> Unit = {}
    var onTap: (String) -> Unit = {}
}

class DropSink {
    /**
     * @param target 命中的放置目标（null = 落在无效区域，要回弹归位）
     * @param items 被拖起的条目
     * @param release 松手瞬间手指在宿主内的坐标（动画从这一点起飞）
     */
    internal var onDrop: ((String?, List<String>, Offset) -> Unit)? = null
    internal fun emit(target: String?, items: List<String>, release: Offset) {
        onDrop?.invoke(target, items, release)
    }
}

/**
 * 宿主的指针输入：**手势的唯一主人**——点击、长按、滑动连选、拿起拖拽全在这里。
 *
 * 关键点：条目（书卡）自己**不能**再挂 `detectTapGestures` / `clickable`。
 * 之前条目在 down 的瞬间就把事件消费掉，宿主的等待循环一旦看到
 * `isConsumed` 就退出，长按永远等不到超时（表现为「长按图书没反应」）。
 * 现在条目只负责被命中（注册几何），手势判定全在宿主，
 * 命中到的条目由 [onTap] 回调出去，按压视觉由 [ShelfSelectionState.pressedKey] 驱动。
 *
 * ⚠️ 2026/09/22 模拟器定位到的第三个（也是真正的）根因：
 * `withTimeout` 到时抛出的**不一定**是 `kotlinx.coroutines.TimeoutCancellationException`，
 * release + R8 后实际是 Compose 自己的 `CancellationException` 子类。原先只 catch 具体类型，
 * 接不住 ⇒ 异常被 `awaitEachGesture` 吞掉、手势循环静默重启 ⇒ 「长按完全没反应，点击却正常」
 *（点击之所以正常，是因为抬手事件 1ms 就到，循环能正常 break）。
 * 修法：宽判 `CancellationException`，再用宿主 Job 的 isActive 区分「真·销毁」与「长按到时」。
 *
 * @param hostOffset 宿主在窗口中的位置（把局部坐标换算成 root 坐标做命中测试）
 * @param onTap 短按（未超时、未移动超 slop）且命中条目时回调
 */
internal fun Modifier.shelfGestures(
    state: ShelfSelectionState,
    touchSlop: Float,
    sink: ShelfGestureSink,
    dropSink: DropSink,
    density: Float = 1f,
): Modifier = this.pointerInput(state) {
    val slop = touchSlop
    // ⚠️⚠️ 关键：`pointerInput(state)` 的协程**只在 state 变化时重启**，
    // 而这里捕获的 onTap / hostOffset 等 lambda 是**组合时的闭包** —— 不包
    // rememberUpdatedState 的话，手势循环会一直用**第一次组合时那一版**闭包。
    // 后果（用户报的「移动到新分类后就打不开书、也选不中」）：
    // onTap 里持有的 sortedBooks 还是**旧分类**的书单，命中到的书在新书单里找不到
    // ⇒ `tapped == null` ⇒ 点了完全没反应；长按选中的也是一个空壳 key。
    // AwaitPointerEventScope 是 @RestrictsSuspension 的，里面拿不到协程上下文，
    // 所以在宿主协程（pointerInput 作用域）里先把 Job 取出来备用。
    val hostJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
    awaitEachGesture {
        if (!sink.enabled()) return@awaitEachGesture
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        // down 的瞬间就做命中：按压视觉要用，短按/长按都复用这个 key
        val rootStart = start + sink.hostOffset()
        val key = state.hitTestItem(rootStart)
        var timedOut = false
        var aborted = false
        var moved = false

        state.setPressed(key)

        try {
            withTimeout(
                if (state.phase == ShelfPhase.SELECTING) com.example.ui.feedback.AppMotion.PICK_UP_MS
                else com.example.ui.feedback.AppMotion.LONG_PRESS_MS
            ) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: break
                    // 让位条件只有两个：手指抬起（→ 点击）或移动超过 slop（→ 滚动）。
                    // 不再看 isConsumed：条目已经不抢手势了，
                    // 而网格滚动的 consume 也不能打断长按计时。
                    if (!change.pressed) {
                        aborted = true
                        break
                    }
                    if ((change.position - start).getDistance() > slop) {
                        aborted = true
                        moved = true
                        break
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ⚠️ 关键：withTimeout 到时抛出的**不一定**是 kotlinx 的
            // TimeoutCancellationException。真机实测（release + R8）这里是 Compose
            // 自己的 CancellationException 子类，类型判断接不住就会被 awaitEachGesture
            // 吞掉、直接重启手势循环 —— 表现就是「长按完全没反应，但点击正常」。
            // 所以这里按「取消」统一判定：宿主协程还活着 ⇒ 就是长按等到时了。
            if (hostJob != null && !hostJob.isActive) throw e
            timedOut = true
        }
        state.setPressed(null)

        // ① 短按 → 点击（打开书 / 多选态切换选中）
        if (!timedOut && key != null && !moved) {
            sink.onTap(key)
            return@awaitEachGesture
        }
        // ② 超时前就抬手但已滑动（滚动）或没命中任何条目 → 什么都不做
        if (!timedOut || aborted || key == null) return@awaitEachGesture

        // ③ 长按
        val alreadySelected = key in state.selected

        when {
            state.phase == ShelfPhase.BROWSE -> {
                sink.haptic(HapticKind.MEDIUM)
                state.enterSelection(key)
                state.markAutoSelect()
            }
            alreadySelected -> {
                sink.haptic(HapticKind.HEAVY)
                state.beginDrag(key, start)
                state.markAutoSelect()
            }
            // 多选名单按板块独立：长按到**另一板块**的卡片时什么都不做
            // —— 不改名单、不给触觉，直接结束这次手势。
            !state.accepts(key) -> {
                return@awaitEachGesture
            }
            else -> {
                sink.haptic(HapticKind.SELECTION)
                state.select(key)
                state.markAutoSelect()
            }
        }
        // 连选起点 = 长按的这一本：不初始化的话，进入跟踪循环后的第一个抖动帧
        // 会再次命中它并 slideAcross 把它划掉（select() 已把它记进 slideVisited）
        state.lastSlideKey = key

        // 继续跟踪：滑动连选 或 拖拽
        // ⚠️ 「滑动连选」必须等手指真的离开长按点才开始：
        // 长按的判定点是 start，抬手前手指哪怕抖 2px 也会再命中同一张卡，
        // 于是 slideAcross 立刻把刚选中的那本判成"滑回来了"又取消掉。
        // 这是「长按有时选中、有时像没反应」的直接来源。
        val slideSlop = slop * 1.5f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            if (state.phase == ShelfPhase.DRAGGING) {
                state.moveDrag(change.position, sink.hostTop(), sink.hostBottom(), sink.hostOffset(), density)
            } else {
                state.moveDrag(change.position, sink.hostTop(), sink.hostBottom(), sink.hostOffset(), density)
                if ((change.position - start).getDistance() > slideSlop) {
                    val root = change.position + sink.hostOffset()
                    val hit = state.hitTestItem(root)
                    // ⚠️ 连选必须**边缘触发**：只在命中条目与上一次不同时才切换。
                    // 手指停在第二本上轻微抖动时，每个 move 事件都会命中同一本 ——
                    // 电平触发的 slideAcross 会把它在"选中/取消"之间来回切
                    //（用户报的"第二本一直闪"；第一本有锚点保护所以不闪）。
                    // 划过新的 → 选中；划回已划过的 → 取消，语义不变。
                    // 滑动连选同样按板块独立：手指从书架的书滑到「我喜欢的」卡片上时
                    // 不能把它也划进名单（这正是用户说的"两个栏目混在一起"）。
                    if (hit != state.lastSlideKey) {
                        state.lastSlideKey = hit
                        if (hit != null && state.accepts(hit)) state.slideAcross(hit)
                    }
                }
            }
            event.changes.forEach { it.consume() }
        }

        if (state.phase == ShelfPhase.DRAGGING) {
            // 先取松手位置：endDrag 会把 pointer 清空，而飞行动画要从这一点起飞
            val release = state.pointer
            val (target, items) = state.endDrag()
            dropSink.emit(target, items, if (release == Offset.Unspecified) Offset.Zero else release)
        }
    }
}
