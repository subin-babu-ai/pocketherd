package dev.pocketherd.feature.pane

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

private const val HOLD_MS = 600

/**
 * A control that only fires after a deliberate hold, with the bar filling as you hold.
 *
 * Answering a blocked agent runs a command on a real machine, and this app is used
 * one-handed while walking. A tap is too easy to do by accident for something that cannot
 * be undone, so the gesture is made to take 600ms and to show its own progress.
 *
 * Releasing early cancels and reports why, rather than silently doing nothing.
 */
@Composable
fun holdToConfirm(
    key: Any?,
    fillColor: Color,
    onHeldLongEnough: () -> Unit,
    onReleasedEarly: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fill = remember(key) { Animatable(0f) }

    return Modifier
        .drawBehind {
            if (fill.value > 0f) {
                drawRect(
                    color = fillColor,
                    size = Size(size.width * fill.value, size.height),
                )
            }
        }
        .pointerInput(key) {
            detectTapGestures(
                onPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val job = scope.launch {
                        fill.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHeldLongEnough()
                    }
                    val released = tryAwaitRelease()
                    if (fill.value < 1f) {
                        job.cancel()
                        scope.launch { fill.animateTo(0f, tween(150)) }
                        if (released) onReleasedEarly()
                    }
                },
            )
        }
}
