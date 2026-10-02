package com.example.ui.mascot

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import androidx.lifecycle.compose.collectAsStateWithLifecycle

sealed class MascotEvent {
    object DeleteBook : MascotEvent()
    object AddBookmark : MascotEvent()
    object MoveBook : MascotEvent()
    object BookComplete : MascotEvent()
    /** 清理缓存成功（B6：原先只有 Toast + 打勾，缺吉祥物反馈）。 */
    object CleanComplete : MascotEvent()
}

/**
 * Event wrapper with a unique ID for interruption tracking.
 */
data class MascotEventInstance(
    val event: MascotEvent,
    val id: Long = idGenerator.incrementAndGet()
) {
    companion object {
        private val idGenerator = AtomicLong(0)
    }
}

/**
 * Data class representing 3-phase motion transformations (Anticipation -> Action -> Recovery).
 */
data class MotionTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationOffset: Float,
    val rotationDeg: Float
)

/**
 * Mascot Animation Controller.
 *
 * 连续触发策略选择：【方案 A：打断重播策略 (Interruption Strategy)】
 * 选型原因：
 * 当用户快速连续触发操作（例如 3 秒内连续添加 5 次书签或连续点击移动）时，如果使用队列策略 (Queue)，
 * 动画会无限排队延迟播放，导致界面操作体验显得滞后黏性。
 * 采用【打断重播策略】，每次新事件触发时会发出一个新的 MascotEventInstance（带唯一 ID）。
 * 界面层收到新事件时，会直接打断当前未播完的旧动画，并重置入场动画从 t=0 开始为新事件播放。
 * 这确保了用户的每一次快速点击都能得到 100% 实时、即时、生动的反馈，没有任何事件丢失。
 */
object MascotAnimationController {
    private val _events = MutableSharedFlow<MascotEventInstance>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun play(event: MascotEvent) {
        _events.tryEmit(MascotEventInstance(event))
    }
}

/**
 * Screen Center Duolingo-Style Floating Mascot Feedback Overlay.
 * Appears in the center of the screen with non-blocking interaction.
 */
@Composable
fun MascotOverlay(modifier: Modifier = Modifier) {
    val latestEventInstance by MascotAnimationController.events.collectAsStateWithLifecycle(initialValue = null)
    var currentInstance by remember { mutableStateOf<MascotEventInstance?>(null) }

    // Interruption Strategy A: When a new event instance arrives, immediately interrupt current and start anew
    LaunchedEffect(latestEventInstance?.id) {
        if (latestEventInstance != null) {
            currentInstance = latestEventInstance
        }
    }

    if (currentInstance != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            // Key using instance ID to force complete composable reset when interrupted
            key(currentInstance?.id) {
                when (currentInstance?.event) {
                    is MascotEvent.DeleteBook -> DeleteSadAnimation(onComplete = { currentInstance = null })
                    is MascotEvent.AddBookmark -> BookmarkHappyAnimation(onComplete = { currentInstance = null })
                    is MascotEvent.MoveBook -> MoveBookAnimation(onComplete = { currentInstance = null })
                    is MascotEvent.BookComplete -> BookCompleteAnimation(onComplete = { currentInstance = null })
                    is MascotEvent.CleanComplete -> BookCompleteAnimation(onComplete = { currentInstance = null })
                    null -> {}
                }
            }
        }
    }
}
