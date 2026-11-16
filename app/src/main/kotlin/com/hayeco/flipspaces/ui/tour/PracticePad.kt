package com.hayeco.flipspaces.ui.tour

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.Look
import kotlin.math.abs
import kotlin.math.sin

/**
 * 1.5.0: the Point and click step's practice. The user's own finger on [PracticePad] moves the dot in the picture above
 * it, a tap clicks, two fingers scroll, as on the real trackpad. Nothing here is required: Next is always there.
 */
internal class PracticeState {
    /** The dot, on the practice window (0,0 its top left, 1,1 its bottom right). */
    var u by mutableFloatStateOf(0.3f)
    var v by mutableFloatStateOf(0.45f)

    /** How far the window's page is scrolled. */
    var scroll by mutableFloatStateOf(0f)

    /** The window's button, off or on, and how lit it is drawn (eased by the pad). */
    var buttonOn by mutableStateOf(false)
    var buttonLit by mutableFloatStateOf(0f)

    var moved by mutableStateOf(false)
    var tapped by mutableStateOf(false)
    var scrolled by mutableStateOf(false)

    /** The pad was touched: the ghost finger that shows what to do goes away for good. */
    var touched by mutableStateOf(false)

    /** The fingers on the pad now, in its own pixels. */
    var fingers by mutableStateOf(emptyList<Offset>())

    /** When the last click was, in the picture's seconds; far back for none. */
    var clickAt by mutableFloatStateOf(-100f)

    val allDone: Boolean get() = moved && tapped && scrolled

    fun moveBy(du: Float, dv: Float) {
        u = (u + du).coerceIn(0.03f, 0.97f)
        v = (v + dv).coerceIn(0.05f, 0.95f)
    }

    fun scrollBy(d: Float) {
        scroll = (scroll + d).coerceIn(0f, 0.85f)
    }

    fun click(now: Float?) {
        tapped = true
        if (now != null) clickAt = now
        if (PRACTICE_BUTTON.contains(Offset(u, v))) buttonOn = !buttonOn
    }

    /** Before the first touch, where the ghost finger has pushed the dot: a slow loop, so the pad reads as a pad. */
    fun ghost(t: Float): Offset = if (touched) Offset.Zero else Offset(0.13f * sin(t * 1.1f), 0.08f * sin(t * 0.8f + 1f)) * span(t, 0.8f, 1.6f)

    fun cursorOn(win: SpaceWin, cam: Cam, t: Float): Offset? = ghost(t).let { win.at(cam, u + it.x, v + it.y) }

    fun clickAgeSeconds(t: Float): Float = t - clickAt
}

@Composable
internal fun PracticePad(state: PracticeState, seconds: () -> Float, reduced: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Look.RADIUS_L.dp)
    val slop = LocalViewConfiguration.current.touchSlop
    val description = stringResource(R.string.tour_point_pad_description)
    val lit by animateFloatAsState(if (state.buttonOn) 1f else 0f, tween(ENTER_MS, easing = EnterEasing), label = "practice button")
    LaunchedEffect(lit) { state.buttonLit = lit }
    Box(
        modifier
            .clip(shape)
            .background(Pad)
            .border(1.dp, RimFocus, shape)
            .clearAndSetSemantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.touched = true
                    state.fingers = listOf(down.position)
                    var most = 1
                    var travel = 0f
                    var scrollTravel = 0f
                    val began = down.uptimeMillis
                    var ended = began
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        most = maxOf(most, pressed.size)
                        state.fingers = pressed.map { it.position }
                        if (pressed.size == 1 && most == 1) {
                            val d = pressed[0].positionChange()
                            travel += d.getDistance()
                            state.moveBy(d.x / size.width * 1.15f, d.y / size.height * 1.15f)
                            if (travel > slop * 3f) state.moved = true
                        } else if (pressed.size >= 2) {
                            val dy = pressed.map { it.positionChange().y }.average().toFloat()
                            scrollTravel += abs(dy)
                            state.scrollBy(-dy / size.height * 0.9f)
                            if (scrollTravel > slop * 3f) state.scrolled = true
                        }
                        event.changes.forEach { it.consume() }
                        ended = event.changes.maxOf { it.uptimeMillis }
                        if (pressed.isEmpty()) break
                    }
                    state.fingers = emptyList()
                    if (most == 1 && travel < slop && ended - began < 400L) state.click(if (reduced) null else seconds())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = seconds()
            // The pad answers each finger with a soft glow, as the real one does.
            for (f in state.fingers) finger(f, 1f, sizeDp = 11f)
            if (!state.touched && !reduced) {
                val g = state.ghost(t)
                val a = span(t, 0.8f, 1.6f)
                finger(Offset(size.width * (0.5f + g.x * 1.6f), size.height * (0.56f + g.y * 2.2f)), 1f, alpha = 0.75f * a, sizeDp = 10f)
            }
        }
        if (!state.touched) {
            Text(
                stringResource(R.string.tour_point_pad),
                style = MaterialTheme.typography.labelLarge,
                color = Ink3,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }
    }
}
