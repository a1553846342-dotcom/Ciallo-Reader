package com.example.ui.comic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 正值回退、负值前进；只允许从起手时已停住的首末页跨章。 */
internal fun comicChapterEdgeTarget(drag: Float, atStart: Boolean, atEnd: Boolean, threshold: Float): Int = when {
    atStart && drag >= threshold -> -1
    atEnd && drag <= -threshold -> 1
    else -> 0
}

/** 首末页单指外翻。缩放、跨轴拖动和取消事件让位，不把落到末页的同一次滑动当作跨章。 */
internal fun Modifier.comicChapterEdgeSwipe(
    enabled: Boolean,
    direction: ComicDirection,
    atStart: () -> Boolean,
    atEnd: () -> Boolean,
    zoomed: () -> Boolean = { false },
    bounce: Boolean = false,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = if (!enabled) this else composed {
    val latestStart by rememberUpdatedState(atStart)
    val latestEnd by rememberUpdatedState(atEnd)
    val latestZoomed by rememberUpdatedState(zoomed)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestNext by rememberUpdatedState(onNext)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val vertical = direction == ComicDirection.TTB
    val sign = if (direction == ComicDirection.RTL) -1f else 1f
    pointerInput(direction, bounce) {
        val threshold = 48.dp.toPx()
        val slop = viewConfiguration.touchSlop
        val span = (if (vertical) size.height else size.width).toFloat()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed || latestZoomed()) return@awaitEachGesture
            val start = latestStart()
            val end = latestEnd()
            if (!start && !end) return@awaitEachGesture
            var active = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1 || latestZoomed()) {
                        return@awaitEachGesture
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (change.isConsumed) {
                        return@awaitEachGesture
                    }
                    val delta = change.position - down.position
                    val axis = (if (vertical) delta.y else delta.x) * sign
                    val cross = if (vertical) delta.x else delta.y
                    if (!active) {
                        if (abs(cross) > slop && abs(cross) > abs(axis)) return@awaitEachGesture
                        if (abs(axis) <= slop) {
                            if (!change.pressed) return@awaitEachGesture
                            continue
                        }
                        if (!(start && axis > 0f || end && axis < 0f)) return@awaitEachGesture
                        active = true
                    }
                    change.consume()
                    if (!change.pressed) {
                        if (change.previousPressed) {
                            when (comicChapterEdgeTarget(axis, start, end, threshold)) {
                                -1 -> latestPrevious()
                                1 -> latestNext()
                            }
                        }
                        break
                    }
                    if (bounce) {
                        val outward = when {
                            start && axis > 0f -> axis
                            end && axis < 0f -> axis
                            else -> 0f
                        }
                        val damped = abs(outward) / (1f + abs(outward) / span.coerceAtLeast(1f))
                        scope.launch { offset.snapTo(if (outward < 0f) -damped * sign else damped * sign) }
                    }
                }
            } finally {
                if (active && bounce) scope.launch {
                    offset.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 380f))
                }
            }
        }
    }.graphicsLayer {
        if (vertical) translationY = offset.value else translationX = offset.value
    }
}
