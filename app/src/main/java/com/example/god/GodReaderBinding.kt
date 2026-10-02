package com.example.god

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import kotlinx.coroutines.flow.first

/**
 * 阅读器 ↔ 神回的绑定（本地 / 在线漫画阅读页共用）。
 *
 * 职责：
 * - 从工具栏打开当前话的神回窗口；
 * - 渲染神回窗口（该话已有神回时自动进入编辑态）。
 */
class GodMomentBinding internal constructor() {
    /** 当前页入口；null = 该场景未启用神回。 */
    var onOpenCurrent: (() -> Unit)? by mutableStateOf(null)
        internal set

    /** 当前打开的神回请求（null = 窗口关闭） */
    var request: GodMomentRequest? by mutableStateOf(null)
        internal set

    val enabled: Boolean get() = onOpenCurrent != null

    /**
     * 打开神回窗口（编辑：existing 非空时自动预填全部数据）。
     * 做成成员函数而不是扩展函数 —— 跨包调用点不必再单独 import 扩展。
     */
    fun openEdit(existing: GodMomentEntity, pages: List<GodPageRef> = emptyList()) {
        request = GodMomentRequest(
            contentType = existing.contentTypeEnum,
            bookId = existing.bookId,
            chapterId = existing.chapterId,
            bookTitle = existing.bookTitle,
            chapterTitle = existing.chapterTitle,
            chapterNumber = existing.chapterNumber,
            pages = pages,
            initialPageIndex = (existing.source as? CoverSource.ComicPage)?.pageIndex ?: 0,
        )
    }
}

/**
 * 在阅读器组合里创建绑定。
 *
 * @param pages 本话全部页（页面选择器用）
 * @param currentPage 用户当前读到的页（封面默认选中它）
 */
@Composable
fun rememberGodMomentBinding(
    ctx: GodMomentContext?,
    pages: List<GodPageRef>,
    currentPage: () -> Int,
): GodMomentBinding {
    val binding = remember(ctx) { GodMomentBinding() }

    if (ctx != null) {
        val latestPages by rememberUpdatedState(pages)
        val latestPage by rememberUpdatedState(currentPage)
        val latestCtx by rememberUpdatedState(ctx)
        binding.onOpenCurrent = remember(binding) { open@{
            val c = latestCtx ?: return@open
            val idx = latestPage().coerceIn(0, (latestPages.size - 1).coerceAtLeast(0))
            binding.request = GodMomentRequest(
                contentType = GodContentType.COMIC,
                bookId = c.bookId,
                chapterId = c.chapterId,
                bookTitle = c.bookTitle,
                chapterTitle = c.chapterTitle,
                chapterNumber = c.chapterNumber,
                pages = latestPages,
                initialPageIndex = idx,
            )
        } }
    }
    return binding
}

/**
 * 渲染神回窗口（编辑已有神回时自动预填）。
 * 必须在阅读器内容的**上层**调用。
 */
@Composable
fun GodMomentHost(
    binding: GodMomentBinding,
    ctx: GodMomentContext?,
    onSaved: (GodMomentEntity) -> Unit,
) {
    val request = binding.request ?: return
    val context = ctx ?: return
    val vm: GodMomentViewModel = viewModel()
    // 与 GodMomentEditHost 同款：首帧前一次性查好已有神回，别让表单按空壳初始化
    var existing by remember(request.bookId, request.chapterId) { mutableStateOf<GodMomentEntity?>(null) }
    var loaded by remember(request.bookId, request.chapterId) { mutableStateOf(false) }
    LaunchedEffect(request.bookId, request.chapterId) {
        existing = vm.repository.observeChapter(request.bookId, request.chapterId).first()
        loaded = true
    }
    if (!loaded) return

    // ⚠️ 返回键拦截：编辑窗内按返回应关闭窗口回到当前页，而不是把整个路由弹掉
    //（详情页长按编辑时实测：返回直接退出了整个漫画详情页）。
    androidx.activity.compose.BackHandler { binding.request = null }

    GodMomentSheet(
        request = request,
        existing = existing,
        viewModel = vm,
        remoteLoader = context.remoteLoader,
        onDismiss = { binding.request = null },
        onSaved = { entity ->
            binding.request = null
            onSaved(entity)
        },
    )
}

/** 备用：在线阅读页把 ImageLoader 传进 ctx 时用（保持签名简单）。 */
fun godContextWithLoader(ctx: GodMomentContext, loader: ImageLoader?): GodMomentContext =
    ctx.copy(remoteLoader = loader)

/**
 * 非阅读器场景（书籍详情页 / 排行榜）打开神回编辑窗口。
 */
@Composable
fun rememberGodEditBinding(): GodMomentBinding {
    return remember { GodMomentBinding() }
}

/**
 * 非阅读器场景的神回窗口宿主。
 * @param pages 页列表（详情页编辑时通常为空 → 窗口自动回退到已合成封面）
 */
@Composable
fun GodMomentEditHost(
    binding: GodMomentBinding,
    pages: List<GodPageRef> = emptyList(),
    onSaved: (GodMomentEntity) -> Unit,
) {
    val request = binding.request ?: return
    val vm: GodMomentViewModel = viewModel()
    // ⚠️ 已有神回必须在窗口首帧组合**之前**查好：表单各字段用 rememberSaveable
    // 只在首次组合时取 existing 初始化。之前用 Flow + null 初始值 —— 首帧时
    // Room 查询必然还没返回，实体到了也不会回填 → 再次编辑永远显示默认文字
    //（用户实测）。改成一次性查询 + 加载门，加载完成前不渲染窗口。
    var existing by remember(request.bookId, request.chapterId) { mutableStateOf<GodMomentEntity?>(null) }
    var loaded by remember(request.bookId, request.chapterId) { mutableStateOf(false) }
    LaunchedEffect(request.bookId, request.chapterId) {
        existing = vm.repository.observeChapter(request.bookId, request.chapterId).first()
        loaded = true
    }
    if (!loaded) return

    // ⚠️ 返回键拦截：编辑窗内按返回应关闭窗口回到当前页，而不是把整个路由弹掉。
    androidx.activity.compose.BackHandler { binding.request = null }

    // ⚠️ 远程页必须走漫画专用加载器（UA/Cookie/重试/磁盘缓存全套），不能用
    // 全局默认 Coil loader —— 没有源的定制链路，缩略图会"加载不出来/特别慢"。
    // 本地书没有远程页，按需构建，不为纯本地场景拉起整套 OkHttp/Coil。
    val context = androidx.compose.ui.platform.LocalContext.current
    val remoteLoader = remember(request.pages) {
        if (request.pages.any { it.remote }) com.example.ui.comicImageLoader(context) else null
    }

    GodMomentSheet(
        request = request,
        existing = existing,
        viewModel = vm,
        remoteLoader = remoteLoader,
        onDismiss = { binding.request = null },
        onSaved = { entity ->
            binding.request = null
            onSaved(entity)
        },
    )
}
