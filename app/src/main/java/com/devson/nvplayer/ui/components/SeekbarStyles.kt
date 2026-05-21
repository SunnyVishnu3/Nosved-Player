package com.devson.nvplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SquigglySeekbar(
    position: Float,
    duration: Float,
    isPaused: Boolean,
    isScrubbing: Boolean,
    useWavySeekbar: Boolean,
    isCircularThumb: Boolean = false,
    enableBouncy: Boolean = true,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)

    var isPressed by remember { mutableStateOf(false) }
    var isDragged by remember { mutableStateOf(false) }
    val isInteracting = isPressed || isDragged || isScrubbing

    val squeezeAnim = remember { Animatable(1f) }
    LaunchedEffect(isInteracting, enableBouncy) {
        if (!enableBouncy) { squeezeAnim.snapTo(1f); return@LaunchedEffect }
        if (isInteracting) {
            squeezeAnim.animateTo(0.8f, spring(stiffness = Spring.StiffnessMediumLow))
        } else {
            squeezeAnim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        }
    }

    var phaseOffset by remember { mutableFloatStateOf(0f) }
    var heightFraction by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(isPaused, isScrubbing, useWavySeekbar) {
        if (!useWavySeekbar) { heightFraction = 0f; return@LaunchedEffect }
        val target = if (isPaused || isScrubbing) 0f else 1f
        Animatable(heightFraction).animateTo(target, tween(600)) { heightFraction = value }
    }

    LaunchedEffect(isPaused, useWavySeekbar) {
        if (isPaused || !useWavySeekbar) return@LaunchedEffect
        while (isActive) {
            withFrameMillis { phaseOffset = (phaseOffset + 0.5f) % 80f }
        }
    }

    var localPos by remember { mutableFloatStateOf(position) }
    LaunchedEffect(position) { if (!isInteracting) localPos = position }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer { scaleY = squeezeAnim.value }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { isPressed = true; tryAwaitRelease(); isPressed = false },
                    onTap = { offset ->
                        localPos = (offset.x / size.width) * duration
                        onSeek(localPos)
                        onSeekFinished()
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragged = true },
                    onDragEnd = { isDragged = false; onSeekFinished() },
                    onDragCancel = { isDragged = false; onSeekFinished() },
                    onDrag = { change, _ ->
                        change.consume()
                        localPos = (change.position.x / size.width).coerceIn(0f, 1f) * duration
                        onSeek(localPos)
                    }
                )
            }
    ) {
        val sw = 5.dp.toPx()
        val prog = if (duration > 0) (localPos / duration).coerceIn(0f, 1f) else 0f
        val w = size.width
        val px = w * prog
        val cy = size.height / 2f
        val amp = if (useWavySeekbar) 6f * heightFraction else 0f
        val wl = 80f

        val path = Path()
        path.moveTo(-phaseOffset, cy)
        var currX = -phaseOffset
        var sign = 1f
        while (currX < w + wl) {
            val nextX = currX + wl / 2f
            path.cubicTo(currX + wl / 4f, cy + sign * amp, currX + wl * 0.75f / 2f, cy - sign * amp, nextX, cy)
            currX = nextX
            sign *= -1f
        }

        clipRect(right = px) { drawPath(path, primaryColor, style = Stroke(sw, cap = StrokeCap.Round)) }
        clipRect(left = px) { drawPath(path, inactiveColor, style = Stroke(sw, cap = StrokeCap.Round)) }

        if (isCircularThumb) {
            drawCircle(primaryColor, 10.dp.toPx(), Offset(px, cy))
        } else {
            val barH = amp + sw
            if (barH > 1f) drawLine(primaryColor, Offset(px, cy - barH), Offset(px, cy + barH), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThickStandardSeekbar(
    position: Float,
    duration: Float,
    isThick: Boolean = true,
    enableBouncy: Boolean = true,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    var isDragged by remember { mutableStateOf(false) }
    var localPos by remember { mutableFloatStateOf(position) }
    LaunchedEffect(position) { if (!isDragged) localPos = position }

    val squeezeAnim = remember { Animatable(1f) }
    LaunchedEffect(isDragged, enableBouncy) {
        if (!enableBouncy) { squeezeAnim.snapTo(1f); return@LaunchedEffect }
        squeezeAnim.animateTo(if (isDragged) 0.85f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer { scaleY = squeezeAnim.value }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    localPos = (offset.x / size.width) * duration
                    onSeek(localPos)
                    onSeekFinished()
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragged = true },
                    onDragEnd = { isDragged = false; onSeekFinished() },
                    onDragCancel = { isDragged = false; onSeekFinished() },
                    onDrag = { change, _ ->
                        change.consume()
                        localPos = (change.position.x / size.width).coerceIn(0f, 1f) * duration
                        onSeek(localPos)
                    }
                )
            }
    ) {
        val trackH = if (isThick) 16.dp else 8.dp
        val thumbW = 6.dp
        val thumbH = if (isThick) 16.dp else 24.dp
        val thumbS = if (isThick) RoundedCornerShape(thumbW / 2) else CircleShape

        Slider(
            value = localPos,
            onValueChange = {},
            valueRange = 0f..duration.coerceAtLeast(0.1f),
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
            track = { sliderState ->
                Canvas(Modifier.fillMaxWidth().height(trackH)) {
                    val p = (sliderState.value / sliderState.valueRange.endInclusive).coerceIn(0f, 1f)
                    val px = size.width * p
                    val th = size.height
                    val r = th / 2f
                    drawRoundRect(inactiveColor, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                    drawRoundRect(primaryColor, size = size.copy(width = px), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                }
            },
            thumb = { Box(Modifier.size(thumbW, thumbH).background(primaryColor, thumbS)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val isDragging by interaction.collectIsDraggedAsState()
    val density = LocalDensity.current
    val offsetHeight by animateFloatAsState(
        targetValue = with(density) { if (isDragging) 36.dp.toPx() else 0.dp.toPx() },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy), label = "offsetAnimation"
    )
    val animatedValue by animateFloatAsState(
        targetValue = value,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy), label = "animatedValue"
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        interactionSource = interaction,
        thumb = {},
        track = { sliderState ->
            val fraction by remember {
                derivedStateOf { (animatedValue - sliderState.valueRange.start) / (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.001f) }
            }
            var width by remember { mutableIntStateOf(0) }
            Box(Modifier.clearAndSetSemantics {}.height(64.dp).fillMaxWidth().onSizeChanged { width = it.width }) {
                Box(Modifier.zIndex(10f).align(Alignment.CenterStart).offset { IntOffset(lerp(-32.dp.toPx(), width.toFloat() - 32.dp.toPx(), fraction).roundToInt(), -offsetHeight.roundToInt()) }
                    .size(64.dp).padding(10.dp).shadow(10.dp, CircleShape).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {}
                
                val strokeColor = MaterialTheme.colorScheme.primary
                val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
                Box(Modifier.align(Alignment.Center).fillMaxWidth().drawWithCache {
                    onDrawBehind {
                        scale(scaleY = 1f, scaleX = if (isLtr) 1f else -1f) {
                            drawSliderPath(fraction = fraction, offsetHeight = offsetHeight, color = strokeColor, steps = sliderState.steps)
                        }
                    }
                })
            }
        }
    )
}

fun DrawScope.drawSliderPath(fraction: Float, offsetHeight: Float, color: Color, steps: Int) {
    val path = Path()
    val activeWidth = size.width * fraction
    val midPointHeight = size.height / 2
    val curveHeight = midPointHeight - offsetHeight
    val beyondBounds = size.width * 2
    val ramp = 72.dp.toPx()

    path.moveTo(beyondBounds, midPointHeight)
    path.lineTo(activeWidth + ramp, midPointHeight)
    path.cubicTo(activeWidth + (ramp / 2), midPointHeight, activeWidth + (ramp / 2), curveHeight, activeWidth, curveHeight)
    path.cubicTo(activeWidth - (ramp / 2), curveHeight, activeWidth - (ramp / 2), midPointHeight, activeWidth - ramp, midPointHeight)
    path.lineTo(-beyondBounds, midPointHeight)
    val variation = .1f
    path.lineTo(-beyondBounds, midPointHeight + variation)
    path.lineTo(activeWidth - ramp, midPointHeight + variation)
    path.cubicTo(activeWidth - (ramp / 2), midPointHeight + variation, activeWidth - (ramp / 2), curveHeight + variation, activeWidth, curveHeight + variation)
    path.cubicTo(activeWidth + (ramp / 2), curveHeight + variation, activeWidth + (ramp / 2), midPointHeight + variation, activeWidth + ramp, midPointHeight + variation)
    path.lineTo(beyondBounds, midPointHeight + variation)

    val exclude = Path().apply { addRect(Rect(-beyondBounds, -beyondBounds, 0f, beyondBounds)); addRect(Rect(size.width, -beyondBounds, beyondBounds, beyondBounds)) }
    val trimmedPath = Path()
    trimmedPath.op(path, exclude, PathOperation.Difference)

    val pathMeasure = PathMeasure()
    pathMeasure.setPath(trimmedPath, false)
    val graduations = steps + 1
    for (i in 0..graduations) {
        val pos = pathMeasure.getPosition((i / graduations.toFloat()) * pathMeasure.length / 2)
        if (pos.x >= 0f && pos.x <= size.width) {
            if (i == 0 || i == graduations) drawCircle(color, 10f, pos)
            else drawLine(color, pos + Offset(0f, 10f), pos + Offset(0f, -10f), if (pos.x < activeWidth) 4f else 2f)
        }
    }
    clipRect(left = 0f, right = activeWidth) { drawTrimmedPath(trimmedPath, color) }
    clipRect(left = activeWidth, right = size.width) { drawTrimmedPath(trimmedPath, color.copy(alpha = 0.24f)) }
}

fun DrawScope.drawTrimmedPath(path: Path, color: Color) {
    drawPath(path = path, color = color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun lerp(start: Float, end: Float, t: Float) = start + t * (end - start)
