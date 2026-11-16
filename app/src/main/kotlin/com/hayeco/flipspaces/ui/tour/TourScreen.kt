package com.hayeco.flipspaces.ui.tour

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.AddonSetup
import com.hayeco.flipspaces.core.Look
import com.hayeco.flipspaces.core.ShizukuStatus
import com.hayeco.flipspaces.core.pointer.IntroPages
import com.hayeco.flipspaces.core.spatial.GlassesModel
import com.hayeco.flipspaces.core.tutorial.Tour

/** What the tour knows about this phone now. Everything in it is read live; nothing is assumed. */
data class TourState(
    val glassesConnected: Boolean = false,
    /** The connected glasses' name as Android gives it, when no model is known. */
    val glassesName: String? = null,
    /** The glasses plugged in, else the last ones used; null when none are known. */
    val model: GlassesModel? = null,
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    /** This copy's Space is up on the glasses now. */
    val spaceUp: Boolean = false,
    val addon: AddonSetup.Step? = null,
    /** The phone can aim (it has the rotation sensor), so Spatial Mouse is offered. */
    val spatialMouse: Boolean = true,
    /** Android's "Remove animations" is on: stills and plain fades. */
    val reducedMotion: Boolean = false,
)

/**
 * 1.5.0: the tour (docs/TUTORIAL.md). A picture over a few words, one idea per step, with Skip, Back and Next on every
 * step. The steps and what is said for these glasses come from core's [Tour]; this draws them.
 */
@Composable
fun TourScreen(
    step: Tour.Step,
    state: TourState,
    onStep: (Tour.Step) -> Unit,
    onClose: () -> Unit,
    onShizuku: (Tour.ShizukuAction) -> Unit,
    onAddon: (AddonSetup.Action) -> Unit,
    onOpenSpace: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    fun tick() = haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    BoxWithConstraints(Modifier.fillMaxSize().background(Page).systemBarsPadding()) {
        // A short or sideways screen: the picture beside the words, not over them.
        val wide = maxWidth > maxHeight || maxHeight < 520.dp
        val wordsMax = maxHeight * if (step == Tour.Step.SETUP) 0.7f else 0.6f
        val slidePx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
        Column(Modifier.fillMaxSize()) {
            TopBar(step, onSkip = onClose)
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    val px = slidePx
                    (fadeIn(tween(QUICK_MS, easing = EnterEasing)) + slideInHorizontally(tween(ENTER_MS, easing = EnterEasing)) { dir * px })
                        .togetherWith(fadeOut(tween(EXIT_MS, easing = ExitEasing)) + slideOutHorizontally(tween(EXIT_MS, easing = ExitEasing)) { -dir * px })
                        .using(SizeTransform(clip = false))
                },
                label = "tour step",
            ) { shown ->
                val seconds = rememberSceneSeconds(shown, state.reducedMotion, scenePoster(shown))
                val practice = remember { PracticeState() }
                val link by animateFloatAsState(if (state.glassesConnected) 1f else 0f, tween(600, easing = EnterEasing), label = "link")
                val complete = Tour.remaining(state.glassesConnected, state.shizuku, state.spaceUp).isEmpty()
                val phase by remember(shown, state.spatialMouse) { derivedStateOf { scenePhase(shown, seconds.floatValue, state.spatialMouse) } }
                val picture = pictureText(shown, state, complete)
                val stage: @Composable (Modifier) -> Unit = { m ->
                    Canvas(m.arrive(0, state.reducedMotion).clearAndSetSemantics { contentDescription = picture; role = Role.Image }) {
                        drawScene(shown, seconds.floatValue, SceneInput(link, practice, complete, state.spatialMouse))
                    }
                }
                val words: @Composable (Modifier) -> Unit = { m ->
                    val scroll = rememberScrollState()
                    // More below: the words fade out at the bottom edge until they are scrolled to their end.
                    val more by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, tween(QUICK_MS), label = "more below")
                    Column(
                        m.drawWithContent {
                            drawContent()
                            if (more > 0.01f) {
                                val h = 36.dp.toPx()
                                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Page), size.height - h, size.height), Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h), alpha = more)
                            }
                        }.verticalScroll(scroll).padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp),
                    ) {
                        // The theme's body style spaces its letters out; the tour's words sit closer, as the trackpad's do.
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp)) {
                            StepWords(shown, state, if (state.reducedMotion) -1 else phase, practice, { seconds.floatValue }, onStep, onShizuku, onAddon, onOpenSpace)
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        stage(Modifier.weight(1f).fillMaxHeight())
                        words(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        stage(Modifier.weight(1f).fillMaxWidth().heightIn(min = 150.dp))
                        words(Modifier.fillMaxWidth().heightIn(max = wordsMax))
                    }
                }
            }
            BottomBar(
                step,
                onBack = { tick(); onStep(Tour.move(step, -1)) },
                onNext = { tick(); if (Tour.isLast(step)) onClose() else onStep(Tour.move(step, 1)) },
            )
        }
    }
}

/** Fades in and rises 12 dp, [order] times 60 ms after the step came up: the picture, the title, the lines, the buttons. */
@Composable
private fun Modifier.arrive(order: Int, reduced: Boolean): Modifier {
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(ENTER_MS, delayMillis = 60 * order, easing = EnterEasing)) }
    return graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 12.dp.toPx()
    }
}

@Composable
private fun TopBar(step: Tour.Step, onSkip: () -> Unit) {
    val n = Tour.STEPS.size
    val spoken = stringResource(R.string.tour_step_of, step.ordinal + 1, n)
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (i in 0 until n) {
                val fill by animateFloatAsState(if (i <= step.ordinal) 1f else 0f, tween(ENTER_MS, easing = EnterEasing), label = "progress")
                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Rim)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(CircleShape).background(Accent))
                }
            }
        }
        // The last step ends with Done; Skip would only say the same.
        TextButton(onClick = onSkip, enabled = !Tour.isLast(step), modifier = Modifier.padding(start = 8.dp).graphicsLayer { alpha = if (Tour.isLast(step)) 0f else 1f }) {
            Text(stringResource(R.string.tour_skip), color = Ink2, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BottomBar(step: Tour.Step, onBack: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!Tour.isFirst(step)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(26.dp), border = androidx.compose.foundation.BorderStroke(1.dp, RimFocus)) {
                Text(stringResource(R.string.tour_back), color = Ink)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            modifier = Modifier.height(52.dp).defaultMinSize(minWidth = 148.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
        ) {
            Text(stringResource(if (Tour.isLast(step)) R.string.pad_done else R.string.pad_next), fontWeight = FontWeight.Medium, fontSize = 16.sp)
        }
    }
}

@Composable
private fun pictureText(step: Tour.Step, state: TourState, complete: Boolean): String = stringResource(
    when (step) {
        Tour.Step.WELCOME -> R.string.tour_welcome_picture
        Tour.Step.SETUP -> if (state.glassesConnected) R.string.tour_setup_picture_connected else R.string.tour_setup_picture_unplugged
        Tour.Step.POINT -> R.string.tour_point_picture
        Tour.Step.THREE_FINGERS -> R.string.tour_three_picture
        Tour.Step.WINDOWS -> R.string.tour_windows_picture
        Tour.Step.LOOK -> R.string.tour_look_picture
        Tour.Step.DEPTH -> R.string.tour_depth_picture
        Tour.Step.MORE -> R.string.tour_more_picture
        Tour.Step.DONE -> if (complete) R.string.tour_done_picture_ready else R.string.tour_done_picture_open
    },
)

// ---- the words ----

@Composable
private fun StepWords(
    step: Tour.Step,
    state: TourState,
    phase: Int,
    practice: PracticeState,
    seconds: () -> Float,
    onStep: (Tour.Step) -> Unit,
    onShizuku: (Tour.ShizukuAction) -> Unit,
    onAddon: (AddonSetup.Action) -> Unit,
    onOpenSpace: () -> Unit,
) {
    val reduced = state.reducedMotion
    val name = state.model?.takeIf { it != GlassesModel.UNKNOWN }?.label ?: state.glassesName
    when (step) {
        Tour.Step.WELCOME -> {
            Title(step, stringResource(R.string.tour_welcome_title), reduced)
            Body(stringResource(R.string.tour_welcome_body), 2, reduced)
            Note(stringResource(R.string.tour_welcome_note), 3, reduced)
        }
        Tour.Step.SETUP -> {
            Title(step, stringResource(R.string.tour_setup_title), reduced)
            Body(stringResource(R.string.tour_setup_body), 2, reduced)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.arrive(3, reduced).fillMaxWidth().clip(RoundedCornerShape(Look.RADIUS_L.dp)).background(Card)
                    .border(1.dp, Rim, RoundedCornerShape(Look.RADIUS_L.dp)),
            ) {
                SetupRow(
                    done = state.glassesConnected,
                    title = stringResource(R.string.tour_glasses),
                    line = when {
                        state.glassesConnected -> stringResource(R.string.tour_glasses_connected, name ?: stringResource(R.string.tour_glasses))
                        name != null -> stringResource(R.string.tour_glasses_last_used, name)
                        else -> stringResource(R.string.tour_glasses_unplugged)
                    },
                    detail = supportLine(state.model, name),
                    reduced = reduced,
                )
                RowRule()
                val action = Tour.shizukuAction(state.shizuku)
                SetupRow(
                    done = state.shizuku == ShizukuStatus.READY,
                    title = stringResource(R.string.tour_shizuku),
                    line = stringResource(
                        when (state.shizuku) {
                            ShizukuStatus.NOT_INSTALLED -> R.string.tour_shizuku_not_installed
                            ShizukuStatus.NOT_RUNNING -> R.string.tour_shizuku_not_running
                            ShizukuStatus.NO_PERMISSION -> R.string.tour_shizuku_no_permission
                            ShizukuStatus.READY -> R.string.tour_shizuku_ready
                        },
                    ),
                    detail = if (state.shizuku == ShizukuStatus.READY) null else stringResource(R.string.tour_shizuku_what),
                    reduced = reduced,
                    buttons = when (action) {
                        Tour.ShizukuAction.GET -> listOf(stringResource(R.string.tour_shizuku_get) to { onShizuku(action) })
                        Tour.ShizukuAction.OPEN -> listOf(stringResource(R.string.tour_shizuku_open) to { onShizuku(action) })
                        Tour.ShizukuAction.ALLOW -> listOf(stringResource(R.string.tour_shizuku_allow) to { onShizuku(action) })
                        Tour.ShizukuAction.NONE -> emptyList()
                    },
                )
                state.addon?.let { addon ->
                    RowRule()
                    // Before anything was asked of it, the row says what the add-on is and offers Install (which asks for
                    // "Install unknown apps" first where that is needed). Once something is going on, the installer's own
                    // line and its one main button.
                    val idle = addon.state == AddonSetup.State.NOT_INSTALLED || addon.state == AddonSetup.State.NEEDS_PERMISSION
                    val main = addon.buttons.firstOrNull { it.primary }
                    SetupRow(
                        done = addon.done,
                        optional = true,
                        title = stringResource(R.string.tour_addon),
                        line = if (idle) AddonSetup.WHAT else addon.line,
                        detail = null,
                        reduced = reduced,
                        buttons = when {
                            addon.state == AddonSetup.State.ACCESS_ON -> emptyList()
                            idle -> listOf(AddonSetup.INSTALL to { onAddon(AddonSetup.Action.INSTALL) })
                            main != null -> listOf(main.label to { onAddon(main.action) })
                            else -> emptyList()
                        },
                    )
                }
            }
        }
        Tour.Step.POINT -> {
            Title(step, IntroPages.GESTURES[0].title, reduced)
            Body(stringResource(R.string.tour_point_body), 2, reduced)
            val haptics = LocalHapticFeedback.current
            LaunchedEffect(practice.moved, practice.tapped, practice.scrolled) {
                if (practice.moved || practice.tapped || practice.scrolled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            Column(Modifier.arrive(3, reduced).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Line(1, IntroPages.GESTURES[0].items[0], lit = false, done = practice.moved)
                Line(2, IntroPages.GESTURES[0].items[1], lit = false, done = practice.tapped)
                Line(3, IntroPages.GESTURES[1].items[1], lit = false, done = practice.scrolled)
            }
            PracticePad(practice, seconds, reduced, Modifier.arrive(4, reduced).padding(top = 12.dp).fillMaxWidth().height(150.dp))
            Note(if (practice.allDone) stringResource(R.string.tour_point_done) else IntroPages.GESTURES[0].items[2], 5, reduced, live = true)
        }
        Tour.Step.THREE_FINGERS -> {
            val page = IntroPages.GESTURES[2]
            Title(step, page.title, reduced)
            Body(stringResource(R.string.tour_three_body), 2, reduced)
            Column(Modifier.arrive(3, reduced).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                page.items.forEachIndexed { i, item -> Line(i + 1, item, lit = phase == i) }
            }
            Note(IntroPages.NO_THREE_FINGERS_LINE + ".", 4, reduced)
        }
        Tour.Step.WINDOWS -> {
            Title(step, stringResource(R.string.tour_windows_title), reduced)
            Column(Modifier.arrive(2, reduced).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Line(1, stringResource(R.string.tour_windows_move), lit = phase == 0)
                Line(2, IntroPages.RESIZE_LINE, lit = phase == 1)
            }
            Note(stringResource(R.string.tour_windows_keys), 3, reduced)
        }
        Tour.Step.LOOK -> {
            Title(step, stringResource(R.string.tour_look_title), reduced)
            Body(stringResource(R.string.tour_look_body), 2, reduced)
            Body(stringResource(R.string.tour_look_recentre), 3, reduced)
            // The One family's own setting that head tracking depends on, where it applies.
            state.model?.setupHint?.let { Note(it, 4, reduced) }
        }
        Tour.Step.DEPTH -> {
            Title(step, stringResource(R.string.tour_depth_title), reduced)
            Column(Modifier.arrive(2, reduced).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Topic(
                    stringResource(R.string.tour_depth_3d),
                    stringResource(R.string.tour_depth_3d_line),
                    stringResource(
                        when (Tour.depth(state.model)) {
                            Tour.Depth.BY_ITSELF -> R.string.tour_depth_by_itself
                            Tour.Depth.GLASSES_MENU -> R.string.tour_depth_glasses_menu
                            Tour.Depth.NOT_YET -> R.string.tour_depth_not_yet
                        },
                    ),
                    lit = phase == 0,
                )
                Topic(
                    stringResource(R.string.pad_steady_mode),
                    stringResource(R.string.tour_steady_line),
                    when (Tour.steady(state.model)) {
                        Tour.Steady.SAY_WHICH -> stringResource(R.string.tour_steady_which)
                        Tour.Steady.HERE_TESTED -> stringResource(R.string.tour_steady_here, name.orEmpty())
                        Tour.Steady.HERE_UNTESTED -> stringResource(R.string.tour_steady_here_untested, name.orEmpty())
                        Tour.Steady.NOT_HERE -> stringResource(R.string.tour_steady_not_here, name ?: stringResource(R.string.tour_glasses))
                    },
                    lit = phase == 1,
                )
            }
        }
        Tour.Step.MORE -> {
            Title(step, stringResource(R.string.tour_more_title), reduced)
            Column(Modifier.arrive(2, reduced).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.spatialMouse) Topic(stringResource(R.string.pad_spatial_mouse), stringResource(R.string.tour_more_mouse_line), null, lit = phase == 0)
                Topic(stringResource(R.string.pad_hand_tracking), stringResource(R.string.tour_more_hands_line), null, lit = phase == 1)
                Topic(stringResource(R.string.manifest_pc_vr), stringResource(R.string.tour_more_pcvr_line), null, lit = phase == 2)
            }
        }
        Tour.Step.DONE -> {
            val open = Tour.remaining(state.glassesConnected, state.shizuku, state.spaceUp)
            Title(step, stringResource(if (open.isEmpty()) R.string.tour_done_title else R.string.tour_done_title_open), reduced)
            if (open.isEmpty()) {
                Body(stringResource(R.string.tour_done_body_ready), 2, reduced)
            } else {
                Body(stringResource(R.string.tour_done_body_open), 2, reduced)
                Column(Modifier.arrive(3, reduced).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    open.forEachIndexed { i, r ->
                        Line(
                            i + 1,
                            stringResource(
                                when (r) {
                                    Tour.Remaining.SHIZUKU -> R.string.tour_done_shizuku
                                    Tour.Remaining.PLUG_IN_GLASSES -> R.string.tour_done_plug_in
                                    Tour.Remaining.OPEN_SPACE -> R.string.tour_done_open_space
                                },
                            ),
                            lit = false,
                        )
                    }
                }
                Row(Modifier.arrive(3, reduced).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The Space can be opened from here; the step turns to "You're set" by itself once it is up.
                    if (Tour.Remaining.OPEN_SPACE in open) PillButton(stringResource(R.string.tour_done_open_space_button), primary = true, onClick = onOpenSpace)
                    if (Tour.Remaining.SHIZUKU in open) PillButton(stringResource(R.string.tour_done_to_setup), primary = false) { onStep(Tour.Step.SETUP) }
                }
            }
            Note(stringResource(R.string.tour_done_check), 4, reduced)
            Note(stringResource(R.string.tour_done_again) + " " + IntroPages.EXIT_NOTE, 5, reduced)
        }
    }
}

@Composable
private fun supportLine(model: GlassesModel?, name: String?): String? = when (Tour.support(model)) {
    null -> null
    Tour.Support.TESTED -> stringResource(R.string.tour_support_tested, name.orEmpty())
    Tour.Support.COMMUNITY -> stringResource(R.string.tour_support_community, name.orEmpty())
    Tour.Support.AIR_FAMILY -> stringResource(R.string.tour_support_air)
    Tour.Support.VITURE_FAMILY -> stringResource(R.string.tour_support_viture)
    Tour.Support.EARLY -> stringResource(R.string.tour_support_early, name.orEmpty())
    Tour.Support.UNLISTED -> stringResource(R.string.tour_support_unlisted)
}

/** A text size that stays the same whatever the phone's font size: for a number inside a badge of a fixed size. */
@Composable
private fun fixedSp(dp: Float) = with(androidx.compose.ui.platform.LocalDensity.current) { dp.dp.toSp() }

/** The step's number in the accent, as in the demo video, and its title: a heading for screen readers. */
@Composable
private fun Title(step: Tour.Step, text: String, reduced: Boolean) {
    Row(Modifier.arrive(1, reduced).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(Look.RADIUS_S.dp)).background(Accent).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text("${step.ordinal + 1}", color = Color.White, fontSize = fixedSp(16f), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            color = Ink,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Body(text: String, order: Int, reduced: Boolean) {
    Text(text, color = Ink2, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.arrive(order, reduced).padding(top = 10.dp))
}

@Composable
private fun Note(text: String, order: Int, reduced: Boolean, live: Boolean = false) {
    Text(
        text,
        color = Ink3,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.arrive(order, reduced).padding(top = 12.dp).then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    )
}

/** A numbered line. [lit] while the picture is showing it; [done] once the user has done it on the practice pad. */
@Composable
private fun Line(number: Int, text: String, lit: Boolean, done: Boolean = false) {
    val on by animateFloatAsState(if (lit || done) 1f else 0f, tween(if (lit || done) ENTER_MS else EXIT_MS, easing = EnterEasing), label = "line")
    val tick by animateFloatAsState(if (done) 1f else 0f, tween(ENTER_MS, easing = EnterEasing), label = "line tick")
    val doneWord = stringResource(R.string.tour_setup_done)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { if (done) stateDescription = doneWord }, verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp).size(22.dp).clip(CircleShape).background(mix(Color(Look.GLASS_HOVER), Accent, on)), contentAlignment = Alignment.Center) {
            if (tick > 0.01f) {
                Canvas(Modifier.size(22.dp)) { tickRing(Offset(size.width / 2f, size.height / 2f), size.width * 0.36f, 0f, tick, Color.White, widthDp = 2f) }
            } else {
                Text("$number", color = mix(Ink2, Color.White, on), fontSize = fixedSp(12f), lineHeight = fixedSp(14f), fontWeight = FontWeight.Medium, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = mix(Ink2, Ink, on), fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/** A named thing with a line about it and, quieter, what holds for these glasses. [lit] while the picture shows it. */
@Composable
private fun Topic(name: String, line: String, note: String?, lit: Boolean) {
    val on by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) ENTER_MS else EXIT_MS, easing = EnterEasing), label = "topic")
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        // A bar in the accent marks the one the picture is showing.
        Box(Modifier.padding(top = 3.dp).width(3.dp).height(18.dp).clip(CircleShape).background(mix(Rim, Accent, on)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, color = mix(Ink2, Ink, 0.5f + 0.5f * on), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Text(line, color = Ink2, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp))
            if (note != null) Text(note, color = Ink3, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RowRule() = Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Rim))

/**
 * One thing to set up: where it stands now, and the button for the next thing to do. The ring fills and the tick draws
 * when it really is done, and the state is read out as it changes.
 */
@Composable
private fun SetupRow(
    done: Boolean,
    title: String,
    line: String,
    detail: String?,
    reduced: Boolean,
    optional: Boolean = false,
    buttons: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val ring = remember { Animatable(if (done) 1f else 0f) }
    val tick = remember { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(done) {
        if (done) {
            ring.animateTo(1f, tween(if (reduced) 0 else ENTER_MS, easing = InOutEasing))
            tick.animateTo(1f, tween(if (reduced) 0 else ENTER_MS, easing = EnterEasing))
        } else {
            tick.snapTo(0f)
            ring.snapTo(0f)
        }
    }
    val stateWord = stringResource(if (done) R.string.tour_setup_done else if (optional) R.string.tour_setup_optional else R.string.tour_setup_to_do)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) { stateDescription = stateWord; liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
            Canvas(Modifier.padding(top = 1.dp).size(24.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.width / 2f - 2.dp.toPx()
                drawCircle(RimFocus, r, c, style = Stroke(2.dp.toPx()))
                tickRing(c, r, ring.value, tick.value, widthDp = 2f)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                    if (optional && !done) {
                        Text(
                            stringResource(R.string.tour_setup_optional),
                            color = Ink2,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(Color(Look.GLASS_HOVER)).padding(horizontal = 8.dp, vertical = 2.dp).clearAndSetSemantics { },
                        )
                    }
                }
                Text(line, color = if (done) Accent else Ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
                if (detail != null) Text(detail, color = Ink3, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (buttons.isNotEmpty()) {
            Row(Modifier.padding(start = 36.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.forEachIndexed { i, (label, onClick) -> PillButton(label, primary = i == 0, onClick = onClick) }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val click = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
    if (primary) {
        Button(onClick = click, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
            Text(label, textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(onClick = click, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, RimFocus)) {
            Text(label, color = Ink, textAlign = TextAlign.Center)
        }
    }
}
