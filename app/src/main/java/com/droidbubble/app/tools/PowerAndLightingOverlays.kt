package com.droidbubble.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Shader
import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Hardware-accelerated View that renders a strict 1-physical-pixel (1px) black grid across the screen
 * using a repeating BitmapShader so subpixels on OLED/AMOLED screens turn completely off to save power.
 */
class PixelGridOverlayView(context: Context) : View(context) {

    private val gridPaint = AndroidPaint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    private var currentSpacing: Int = 0

    init {
        setWillNotDraw(false)
        updateSpacing(2)
    }

    fun updateSpacing(spacingMode: Int) {
        val clamped = spacingMode.coerceIn(2, 4)
        if (currentSpacing == clamped && gridPaint.shader != null) return
        currentSpacing = clamped

        val bitmap: Bitmap = when (clamped) {
            // Mode 2: 2x2 tile with 1 single physical black pixel (25% pixels off)
            2 -> Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.TRANSPARENT)
                setPixel(0, 0, AndroidColor.BLACK)
            }
            // Mode 3: 3x3 tile with 1 single physical black pixel (~11% pixels off, ultra subtle)
            3 -> Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.TRANSPARENT)
                setPixel(0, 0, AndroidColor.BLACK)
            }
            // Mode 4: 2x2 checkerboard of 1-physical-pixel black dots (50% pixels off, max saving)
            else -> Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.TRANSPARENT)
                setPixel(0, 0, AndroidColor.BLACK)
                setPixel(1, 1, AndroidColor.BLACK)
            }
        }

        gridPaint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        invalidate()
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gridPaint)
    }
}

/**
 * Full-screen or preview Composable that renders any of the 10 customizable Notification Lighting styles.
 */
@Composable
fun NotificationLightingEffectCanvas(
    style: NotificationLightingStyle,
    colorArgb: Long,
    isRainbow: Boolean,
    cornerRadiusDp: Int,
    thicknessDp: Float,
    animationSpeed: Float,
    modifier: Modifier = Modifier.fillMaxSize(),
    masterAlpha: Float = 1f
) {
    val speedMs = (2400f / animationSpeed.coerceIn(0.4f, 2.5f)).toInt().coerceAtLeast(500)
    val infiniteTransition = rememberInfiniteTransition(label = "notifLighting")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = speedMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lightingPhase"
    )

    // Smooth harmonic sine breathing instead of linear sawtooth
    val pulse = remember(phase) {
        val wave = (0.5f - 0.5f * cos(phase * 2.0 * PI).toFloat())
        // Apply smoothstep for organic dwell at peaks and troughs
        val smooth = wave * wave * (3f - 2f * wave)
        0.32f + 0.68f * smooth
    }

    val baseColor = remember(colorArgb, isRainbow, phase) {
        if (isRainbow) {
            hsvToColor((phase * 360f) % 360f, 0.82f, 1f)
        } else {
            Color(colorArgb.toInt())
        }
    }
    val secondaryColor = remember(colorArgb, isRainbow, phase) {
        if (isRainbow) {
            hsvToColor(((phase * 360f) + 72f) % 360f, 0.80f, 1f)
        } else {
            // Analogous luminous shift towards cyan/violet highlight for natural optical bloom
            Color(
                red = (baseColor.red * 0.68f + 0.32f).coerceIn(0f, 1f),
                green = (baseColor.green * 0.78f + 0.22f).coerceIn(0f, 1f),
                blue = (baseColor.blue * 0.85f + 0.15f).coerceIn(0f, 1f),
                alpha = 1f
            )
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas

        val cornerPx = cornerRadiusDp.dp.toPx().coerceAtMost(min(w, h) / 2f)
        val strokePx = thicknessDp.dp.toPx().coerceAtLeast(2.dp.toPx())

        val clipRoundedPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    cornerRadius = CornerRadius(cornerPx, cornerPx)
                )
            )
        }

        clipPath(clipRoundedPath) {
            when (style) {
                NotificationLightingStyle.SIMPLE_EDGE_GLOW -> {
                    drawSimpleEdgeGlow(w, h, cornerPx, strokePx, baseColor, secondaryColor, phase, pulse * masterAlpha)
                }
                NotificationLightingStyle.OUTLINE -> {
                    drawCrispOutline(w, h, cornerPx, strokePx, baseColor, secondaryColor, phase, pulse * masterAlpha)
                }
                NotificationLightingStyle.TOP_FULL_PULSE -> {
                    drawTopFullPulse(w, h, strokePx, baseColor, secondaryColor, phase, masterAlpha)
                }
                NotificationLightingStyle.DROPLET -> {
                    drawDropletEffect(w, h, cornerPx, strokePx, baseColor, secondaryColor, phase, masterAlpha)
                }
                NotificationLightingStyle.CORNER_BEAMS -> {
                    drawCornerBeams(w, h, cornerPx, strokePx, baseColor, secondaryColor, pulse, phase, masterAlpha)
                }
                NotificationLightingStyle.NEON_CHASE -> {
                    drawNeonChase(w, h, cornerPx, strokePx, baseColor, secondaryColor, phase, masterAlpha)
                }
                NotificationLightingStyle.DUAL_SIDE_WAVE -> {
                    drawDualSideWave(w, h, strokePx, baseColor, secondaryColor, phase, masterAlpha)
                }
                NotificationLightingStyle.STARBURST_PARTICLES -> {
                    drawStarburstParticles(w, h, cornerPx, strokePx, baseColor, secondaryColor, phase, masterAlpha)
                }
                NotificationLightingStyle.HORIZON_RIPPLE -> {
                    drawHorizonRipple(w, h, strokePx, baseColor, secondaryColor, phase, masterAlpha)
                }
                NotificationLightingStyle.LASER_SCAN -> {
                    drawLaserScan(w, h, cornerPx, strokePx, baseColor, secondaryColor, phase, masterAlpha)
                }
            }
        }
    }
}

@Composable
fun ActiveNotificationLightingOverlay(
    event: ActiveLightingEvent?
) {
    var visible by remember { mutableStateOf(false) }
    var lastEvent by remember { mutableStateOf<ActiveLightingEvent?>(null) }

    LaunchedEffect(event?.id) {
        if (event != null) {
            lastEvent = event
            visible = true
            delay((event.durationMs - 450L).coerceAtLeast(450L))
            visible = false
        } else {
            visible = false
        }
    }

    val fadeAlpha by animateFloatAsState(
        targetValue = if (visible && event != null) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "lightingOverlayFade"
    )

    val active = event ?: lastEvent
    if (active != null && fadeAlpha > 0.01f) {
        NotificationLightingEffectCanvas(
            style = active.style,
            colorArgb = active.colorArgb,
            isRainbow = active.isRainbow,
            cornerRadiusDp = active.cornerRadiusDp,
            thicknessDp = active.thicknessDp,
            animationSpeed = active.animationSpeed,
            masterAlpha = fadeAlpha
        )
    }
}

/**
 * Multi-layered concentric rounded-rect bloom helper that follows curved screen corners naturally
 * with a Gaussian-like exponential falloff and a hot white-tinted core.
 */
private fun DrawScope.drawOrganicPerimeterBloom(
    w: Float,
    h: Float,
    cornerPx: Float,
    baseThickness: Float,
    brush: Brush,
    alpha: Float,
    coreColor: Color,
    layers: Int = 6
) {
    for (i in layers downTo 1) {
        val ratio = i.toFloat() / layers.toFloat()
        // Exponential spread for soft optical bloom
        val spread = baseThickness * (1f + (ratio * ratio * 3.8f))
        val layerAlpha = (alpha * (1f - ratio * 0.78f).pow(1.8f) * 0.26f).coerceIn(0f, 1f)
        val inset = spread * 0.35f
        val r = (cornerPx - inset * 0.5f).coerceAtLeast(2f)
        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = Size((w - inset * 2f).coerceAtLeast(1f), (h - inset * 2f).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(r, r),
            alpha = layerAlpha,
            style = Stroke(width = spread)
        )
    }

    // Crisp luminous inner filament
    val coreInset = baseThickness * 0.35f
    val coreCorner = (cornerPx - coreInset * 0.5f).coerceAtLeast(2f)
    drawRoundRect(
        brush = brush,
        topLeft = Offset(coreInset, coreInset),
        size = Size((w - coreInset * 2f).coerceAtLeast(1f), (h - coreInset * 2f).coerceAtLeast(1f)),
        cornerRadius = CornerRadius(coreCorner, coreCorner),
        alpha = (0.92f * alpha).coerceIn(0f, 1f),
        style = Stroke(width = baseThickness * 0.85f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = (0.55f * alpha).coerceIn(0f, 1f)),
        topLeft = Offset(coreInset, coreInset),
        size = Size((w - coreInset * 2f).coerceAtLeast(1f), (h - coreInset * 2f).coerceAtLeast(1f)),
        cornerRadius = CornerRadius(coreCorner, coreCorner),
        style = Stroke(width = (baseThickness * 0.28f).coerceAtLeast(1.5f))
    )
}

private fun DrawScope.drawSimpleEdgeGlow(
    w: Float,
    h: Float,
    cornerPx: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    alpha: Float
) {
    val angle = phase * 2f * PI.toFloat()
    val cx = w / 2f + cos(angle) * (w * 0.28f)
    val cy = h / 2f + sin(angle) * (h * 0.28f)
    val ox = w - cx
    val oy = h - cy

    val mainBrush = Brush.linearGradient(
        colors = listOf(color, secondary, color),
        start = Offset(cx, cy),
        end = Offset(ox, oy)
    )

    // Soft ambient edge wash that curves around the corners
    drawOrganicPerimeterBloom(
        w = w,
        h = h,
        cornerPx = cornerPx,
        baseThickness = strokePx * 1.15f,
        brush = mainBrush,
        alpha = alpha,
        coreColor = color,
        layers = 7
    )
}

private fun DrawScope.drawCrispOutline(
    w: Float,
    h: Float,
    cornerPx: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    alpha: Float
) {
    val angle = phase * 2f * PI.toFloat()
    val gx = w / 2f + cos(angle) * (w * 0.45f)
    val gy = h / 2f + sin(angle) * (h * 0.45f)

    val gradientBrush = Brush.linearGradient(
        colors = listOf(color, secondary, Color.White.copy(alpha = 0.9f), secondary, color),
        start = Offset(gx, gy),
        end = Offset(w - gx, h - gy)
    )

    drawOrganicPerimeterBloom(
        w = w,
        h = h,
        cornerPx = cornerPx,
        baseThickness = strokePx * 0.9f,
        brush = gradientBrush,
        alpha = alpha,
        coreColor = color,
        layers = 4
    )
}

private fun DrawScope.drawTopFullPulse(
    w: Float,
    h: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    masterAlpha: Float
) {
    // Smooth cubic ease-out expansion from top
    val easeOut = 1f - (1f - phase).pow(2.6f)
    val fadeEnvelope = sin(phase * PI).toFloat().pow(0.75f) * masterAlpha
    val waveY = (easeOut * h * 0.78f).coerceAtLeast(8f)
    val crestDepth = (strokePx * 8.5f).coerceAtLeast(64.dp.toPx())

    // 1. Ambient radial crown light at the top camera cutout
    val crownRadius = min(w * 0.85f, h * 0.55f) * (0.45f + 0.55f * easeOut)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = (0.55f * fadeEnvelope).coerceIn(0f, 1f)),
                color.copy(alpha = (0.65f * fadeEnvelope).coerceIn(0f, 1f)),
                secondary.copy(alpha = (0.28f * fadeEnvelope).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            center = Offset(w / 2f, 0f),
            radius = crownRadius.coerceAtLeast(10f)
        ),
        radius = crownRadius.coerceAtLeast(10f),
        center = Offset(w / 2f, 0f)
    )

    // 2. Smooth multi-stop aurora curtain washing down
    val endWashY = (waveY + crestDepth).coerceAtMost(h)
    drawRect(
        brush = Brush.verticalGradient(
            0.0f to color.copy(alpha = (0.52f * fadeEnvelope).coerceIn(0f, 1f)),
            0.45f to secondary.copy(alpha = (0.32f * fadeEnvelope).coerceIn(0f, 1f)),
            0.78f to color.copy(alpha = (0.68f * fadeEnvelope).coerceIn(0f, 1f)),
            1.0f to Color.Transparent,
            startY = 0f,
            endY = endWashY
        ),
        topLeft = Offset(0f, 0f),
        size = Size(w, endWashY)
    )

    // 3. Softly curved organic wavefront with outer halo and bright core
    val arcDrop = crestDepth * 0.38f * (0.6f + 0.4f * easeOut)
    val wavePath = Path().apply {
        moveTo(0f, waveY * 0.72f)
        cubicTo(
            w * 0.25f, waveY + arcDrop,
            w * 0.75f, waveY + arcDrop,
            w, waveY * 0.72f
        )
    }
    drawPath(
        path = wavePath,
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                secondary.copy(alpha = (0.42f * fadeEnvelope).coerceIn(0f, 1f)),
                color.copy(alpha = (0.65f * fadeEnvelope).coerceIn(0f, 1f)),
                secondary.copy(alpha = (0.42f * fadeEnvelope).coerceIn(0f, 1f)),
                Color.Transparent
            )
        ),
        style = Stroke(width = strokePx * 3.2f, cap = StrokeCap.Round)
    )
    drawPath(
        path = wavePath,
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                color.copy(alpha = (0.85f * fadeEnvelope).coerceIn(0f, 1f)),
                Color.White.copy(alpha = (0.92f * fadeEnvelope).coerceIn(0f, 1f)),
                color.copy(alpha = (0.85f * fadeEnvelope).coerceIn(0f, 1f)),
                Color.Transparent
            )
        ),
        style = Stroke(width = (strokePx * 0.75f).coerceAtLeast(2f), cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawDropletEffect(
    w: Float,
    h: Float,
    cornerPx: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    masterAlpha: Float
) {
    val centerX = w / 2f
    val impactY = h * 0.14f

    if (phase < 0.28f) {
        // Phase 1: Accelerating gravity fall (t^2) from top notch to impact point
        val rawP = (phase / 0.28f).coerceIn(0f, 1f)
        val dropP = rawP * rawP
        val curY = dropP * impactY
        val dropRadius = (strokePx * 1.15f).coerceAtLeast(6.dp.toPx())
        val tailTop = (curY - impactY * 0.45f * rawP).coerceAtLeast(0f)

        // Tapered silk comet tail behind droplet
        val tailSegments = 8
        for (s in 0 until tailSegments) {
            val f1 = s.toFloat() / tailSegments
            val f2 = (s + 1).toFloat() / tailSegments
            val y1 = tailTop + (curY - tailTop) * f1
            val y2 = tailTop + (curY - tailTop) * f2
            drawLine(
                color = color.copy(alpha = (f2 * f2 * 0.78f * masterAlpha).coerceIn(0f, 1f)),
                start = Offset(centerX, y1),
                end = Offset(centerX, y2),
                strokeWidth = strokePx * (0.2f + 0.85f * f2),
                cap = StrokeCap.Round
            )
        }

        // Outer halo + glowing white droplet head
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.98f * masterAlpha).coerceIn(0f, 1f)),
                    color.copy(alpha = (0.88f * masterAlpha).coerceIn(0f, 1f)),
                    secondary.copy(alpha = (0.35f * masterAlpha).coerceIn(0f, 1f)),
                    Color.Transparent
                ),
                center = Offset(centerX, curY),
                radius = dropRadius * 2.8f
            ),
            radius = dropRadius * 2.8f,
            center = Offset(centerX, curY)
        )
    } else {
        // Phase 2: Decelerating concentric water ripples + perimeter energy wave
        val rawRipple = ((phase - 0.28f) / 0.72f).coerceIn(0f, 1f)
        val maxRadius = min(w, h) * 0.82f

        // Impact flash bloom at the point of contact
        if (rawRipple < 0.35f) {
            val flashAlpha = (1f - rawRipple / 0.35f).pow(2f) * masterAlpha
            val flashRad = (strokePx * 5f).coerceAtLeast(28.dp.toPx()) * (0.6f + rawRipple)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = (0.85f * flashAlpha).coerceIn(0f, 1f)),
                        color.copy(alpha = (0.55f * flashAlpha).coerceIn(0f, 1f)),
                        Color.Transparent
                    ),
                    center = Offset(centerX, impactY),
                    radius = flashRad
                ),
                radius = flashRad,
                center = Offset(centerX, impactY)
            )
        }

        for (ring in 0..2) {
            val lin = ((rawRipple - ring * 0.14f) / (1f - ring * 0.14f)).coerceIn(0f, 1f)
            if (lin > 0f && lin < 1f) {
                // Ease-out water wave propagation
                val eased = 1f - (1f - lin).pow(2.4f)
                val r = eased * maxRadius
                val ringAlpha = (1f - eased).pow(1.35f) * masterAlpha
                val c = if (ring % 2 == 0) color else secondary
                val ringStroke = (strokePx * (1.25f - eased * 0.75f)).coerceAtLeast(1.5f)

                // Soft outer ring halo
                drawCircle(
                    color = c.copy(alpha = (0.28f * ringAlpha).coerceIn(0f, 1f)),
                    radius = r,
                    center = Offset(centerX, impactY),
                    style = Stroke(width = ringStroke * 3.0f)
                )
                // Crisp inner ripple crest
                drawCircle(
                    color = c.copy(alpha = (0.88f * ringAlpha).coerceIn(0f, 1f)),
                    radius = r,
                    center = Offset(centerX, impactY),
                    style = Stroke(width = ringStroke)
                )
            }
        }
    }
}

private fun DrawScope.drawCornerBeams(
    w: Float,
    h: Float,
    cornerPx: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    pulse: Float,
    phase: Float,
    masterAlpha: Float
) {
    val inset = strokePx / 2f
    val perimPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(inset, inset, w - inset, h - inset),
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
        )
    }
    val measure = PathMeasure()
    measure.setPath(perimPath, true)
    val totalLen = measure.length
    if (totalLen <= 1f) return

    val alpha = (0.48f + 0.52f * pulse) * masterAlpha
    val cornerGlowRadius = min(w, h) * (0.26f + 0.10f * pulse)

    val corners = listOf(
        Offset(0f, 0f),
        Offset(w, 0f),
        Offset(w, h),
        Offset(0f, h)
    )
    corners.forEachIndexed { idx, corner ->
        val harmonic = 0.5f + 0.5f * sin((phase * 2f * PI + idx * PI * 0.5)).toFloat()
        val c = if (idx % 2 == 0) color else secondary
        val rad = cornerGlowRadius * (0.85f + 0.25f * harmonic)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.55f * alpha).coerceIn(0f, 1f)),
                    c.copy(alpha = (0.72f * alpha).coerceIn(0f, 1f)),
                    c.copy(alpha = (0.22f * alpha).coerceIn(0f, 1f)),
                    Color.Transparent
                ),
                center = corner,
                radius = rad.coerceAtLeast(10f)
            ),
            radius = rad.coerceAtLeast(10f),
            center = corner
        )

        // Draw smooth tapered corner arcs along the exact RoundRect path
        // Find closest corner distance approximation around the perimeter
        val approxCornerDists = floatArrayOf(0f, w - cornerPx, w + h - 2f * cornerPx, 2f * w + h - 3f * cornerPx)
        val centerDist = approxCornerDists[idx] % totalLen
        val halfSpan = min(w, h) * (0.16f + 0.08f * harmonic)
        val segments = 10
        for (s in -segments..segments) {
            val norm = kotlin.math.abs(s).toFloat() / segments.toFloat()
            val segFalloff = (1f - norm * norm).coerceIn(0f, 1f)
            val dStart = (centerDist + (s - 0.5f) * (halfSpan / segments) + totalLen) % totalLen
            val dEnd = (centerDist + (s + 0.5f) * (halfSpan / segments) + totalLen) % totalLen
            val seg = Path()
            if (dStart < dEnd) {
                measure.getSegment(dStart, dEnd, seg, true)
            }
            drawPath(
                path = seg,
                color = c.copy(alpha = (0.88f * segFalloff * alpha).coerceIn(0f, 1f)),
                style = Stroke(width = strokePx * (0.4f + 1.1f * segFalloff), cap = StrokeCap.Round)
            )
            if (segFalloff > 0.65f) {
                drawPath(
                    path = seg,
                    color = Color.White.copy(alpha = (0.75f * segFalloff * alpha).coerceIn(0f, 1f)),
                    style = Stroke(width = strokePx * 0.4f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

private fun DrawScope.drawNeonChase(
    w: Float,
    h: Float,
    cornerPx: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    masterAlpha: Float
) {
    val inset = strokePx / 2f
    val perimPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(inset, inset, w - inset, h - inset),
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
        )
    }
    val measure = PathMeasure()
    measure.setPath(perimPath, true)
    val totalLen = measure.length
    if (totalLen <= 1f) return

    // Soft ambient track around the perimeter
    drawPath(
        path = perimPath,
        color = color.copy(alpha = (0.12f * masterAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = strokePx * 0.6f)
    )

    // Two opposing neon comets with smooth multi-segment tapered tails & glowing heads
    val cometLen = totalLen * 0.30f
    val numSegments = 22
    val segLen = cometLen / numSegments

    for (cometIndex in 0..1) {
        val headFraction = (phase + cometIndex * 0.5f) % 1f
        val headDist = headFraction * totalLen
        val cometColor = if (cometIndex == 0) color else secondary

        for (i in 0 until numSegments) {
            // t goes from ~0 at the tail tip to 1.0 at the comet head
            val t = (i + 1).toFloat() / numSegments.toFloat()
            val easedT = t * t * (3f - 2f * t)
            val segEnd = (headDist - (numSegments - 1 - i) * segLen + totalLen) % totalLen
            val segStart = (segEnd - segLen * 1.15f + totalLen) % totalLen

            val segPath = Path()
            if (segStart <= segEnd) {
                measure.getSegment(segStart, segEnd, segPath, true)
            } else {
                measure.getSegment(segStart, totalLen, segPath, true)
                measure.getSegment(0f, segEnd, segPath, true)
            }

            // Outer bloom layer
            drawPath(
                path = segPath,
                color = cometColor.copy(alpha = (0.34f * easedT * masterAlpha).coerceIn(0f, 1f)),
                style = Stroke(width = strokePx * (0.8f + 2.1f * easedT), cap = StrokeCap.Round)
            )
            // Core neon body
            drawPath(
                path = segPath,
                color = cometColor.copy(alpha = (0.95f * easedT * masterAlpha).coerceIn(0f, 1f)),
                style = Stroke(width = strokePx * (0.25f + 0.85f * easedT), cap = StrokeCap.Round)
            )
            // Hot white core near head
            if (t > 0.72f) {
                val headHot = ((t - 0.72f) / 0.28f).coerceIn(0f, 1f)
                drawPath(
                    path = segPath,
                    color = Color.White.copy(alpha = (0.85f * headHot * masterAlpha).coerceIn(0f, 1f)),
                    style = Stroke(width = strokePx * 0.42f * headHot, cap = StrokeCap.Round)
                )
            }
        }

        // Radiant orb at the leading comet head
        val headPos = measure.getPosition(headDist)
        val orbRadius = (strokePx * 2.6f).coerceAtLeast(12.dp.toPx())
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.95f * masterAlpha).coerceIn(0f, 1f)),
                    cometColor.copy(alpha = (0.75f * masterAlpha).coerceIn(0f, 1f)),
                    Color.Transparent
                ),
                center = headPos,
                radius = orbRadius
            ),
            radius = orbRadius,
            center = headPos
        )
    }
}

private fun DrawScope.drawDualSideWave(
    w: Float,
    h: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    masterAlpha: Float
) {
    val steps = 64
    val amp = (strokePx * 2.2f).coerceAtLeast(14.dp.toPx())
    val glowWidth = (amp * 3.4f).coerceAtLeast(36.dp.toPx())

    // Traveling vertical aurora center along the left & right edges
    val waveFocusY = h * (0.5f + 0.32f * sin(phase * 2f * PI).toFloat())
    val focusSpan = h * 0.55f

    drawRect(
        brush = Brush.horizontalGradient(
            0.0f to color.copy(alpha = (0.58f * masterAlpha).coerceIn(0f, 1f)),
            0.4f to secondary.copy(alpha = (0.24f * masterAlpha).coerceIn(0f, 1f)),
            1.0f to Color.Transparent,
            startX = 0f,
            endX = glowWidth
        ),
        topLeft = Offset(0f, 0f),
        size = Size(glowWidth, h)
    )
    drawRect(
        brush = Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.6f to secondary.copy(alpha = (0.24f * masterAlpha).coerceIn(0f, 1f)),
            1.0f to color.copy(alpha = (0.58f * masterAlpha).coerceIn(0f, 1f)),
            startX = w - glowWidth,
            endX = w
        ),
        topLeft = Offset(w - glowWidth, 0f),
        size = Size(glowWidth, h)
    )

    // Render 2 harmonic ribbons per side for fluid silk motion
    for (ribbon in 0..1) {
        val leftPath = Path()
        val rightPath = Path()
        val freq = if (ribbon == 0) 3.0f else 2.0f
        val phaseShift = if (ribbon == 0) phase * 2f else -phase * 2f + 0.35f
        val ribbonAmp = if (ribbon == 0) amp else amp * 0.65f

        for (i in 0..steps) {
            val frac = i.toFloat() / steps
            val y = frac * h
            // Smooth bell envelope so waves taper gracefully into top and bottom corners
            val env = sin(frac * PI).toFloat().pow(1.4f)
            val harmonic = (sin((frac * freq - phaseShift) * PI) + 0.35 * cos((frac * (freq + 1f) + phase * 2f) * PI)).toFloat() / 1.35f
            val offset = (harmonic * 0.5f + 0.5f) * ribbonAmp * env
            val lx = strokePx * 0.5f + offset
            val rx = w - (strokePx * 0.5f + offset)
            if (i == 0) {
                leftPath.moveTo(lx, y)
                rightPath.moveTo(rx, y)
            } else {
                leftPath.lineTo(lx, y)
                rightPath.lineTo(rx, y)
            }
        }

        val rColor = if (ribbon == 0) color else secondary
        val rAlpha = (if (ribbon == 0) 0.92f else 0.58f) * masterAlpha
        val vBrush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                rColor.copy(alpha = (rAlpha * 0.7f).coerceIn(0f, 1f)),
                Color.White.copy(alpha = (rAlpha * 0.9f).coerceIn(0f, 1f)),
                rColor.copy(alpha = (rAlpha * 0.7f).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            startY = (waveFocusY - focusSpan).coerceAtLeast(0f),
            endY = (waveFocusY + focusSpan).coerceAtMost(h)
        )

        // Outer ribbon bloom
        drawPath(
            path = leftPath,
            color = rColor.copy(alpha = (0.30f * rAlpha).coerceIn(0f, 1f)),
            style = Stroke(width = strokePx * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = rightPath,
            color = rColor.copy(alpha = (0.30f * rAlpha).coerceIn(0f, 1f)),
            style = Stroke(width = strokePx * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Core ribbon
        drawPath(
            path = leftPath,
            brush = vBrush,
            style = Stroke(width = strokePx * (if (ribbon == 0) 0.95f else 0.6f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = rightPath,
            brush = vBrush,
            style = Stroke(width = strokePx * (if (ribbon == 0) 0.95f else 0.6f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private fun DrawScope.drawStarburstParticles(
    w: Float,
    h: Float,
    cornerPx: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    masterAlpha: Float
) {
    val inset = strokePx
    val perimPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(inset, inset, w - inset, h - inset),
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
        )
    }
    val measure = PathMeasure()
    measure.setPath(perimPath, true)
    val totalLen = measure.length
    if (totalLen <= 1f) return

    // Soft ambient rim so particles feel grounded along the display border
    drawPath(
        path = perimPath,
        color = color.copy(alpha = (0.16f * masterAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = strokePx * 1.4f)
    )

    val particleCount = 32
    for (i in 0 until particleCount) {
        val seed = i.toFloat() / particleCount
        val drift = sin((phase + seed * 3f) * 2f * PI).toFloat() * 0.015f
        val posFrac = (seed + phase * (if (i % 2 == 0) 0.35f else -0.25f) + drift + 1f) % 1f
        val pos = measure.getPosition(posFrac * totalLen)

        // Smooth sine-squared twinkle envelope
        val rawTwinkle = 0.5f + 0.5f * sin((phase * 2f + seed * 7f) * PI).toFloat()
        val twinkle = 0.18f + 0.82f * (rawTwinkle * rawTwinkle)
        val pRadius = strokePx * (0.65f + (i % 4) * 0.38f) * twinkle
        val pColor = if (i % 2 == 0) color else secondary

        // Soft outer bokeh halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.95f * twinkle * masterAlpha).coerceIn(0f, 1f)),
                    pColor.copy(alpha = (0.72f * twinkle * masterAlpha).coerceIn(0f, 1f)),
                    pColor.copy(alpha = (0.18f * twinkle * masterAlpha).coerceIn(0f, 1f)),
                    Color.Transparent
                ),
                center = pos,
                radius = (pRadius * 2.8f).coerceAtLeast(4f)
            ),
            radius = (pRadius * 2.8f).coerceAtLeast(4f),
            center = pos
        )

        // Subtle 4-point star flare on brightest sparkles
        if (i % 3 == 0 && twinkle > 0.62f) {
            val flareLen = pRadius * 3.1f
            val flareAlpha = ((twinkle - 0.62f) / 0.38f * 0.85f * masterAlpha).coerceIn(0f, 1f)
            drawLine(
                color = Color.White.copy(alpha = flareAlpha),
                start = Offset(pos.x - flareLen, pos.y),
                end = Offset(pos.x + flareLen, pos.y),
                strokeWidth = (strokePx * 0.22f).coerceAtLeast(1.2f),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White.copy(alpha = flareAlpha),
                start = Offset(pos.x, pos.y - flareLen),
                end = Offset(pos.x, pos.y + flareLen),
                strokeWidth = (strokePx * 0.22f).coerceAtLeast(1.2f),
                cap = StrokeCap.Round
            )
        }
    }
}

private fun DrawScope.drawHorizonRipple(
    w: Float,
    h: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    masterAlpha: Float
) {
    val maxReach = h * 0.26f

    // Ambient horizon glow at top and bottom edges
    val ambientReach = maxReach * 0.65f
    drawRect(
        brush = Brush.verticalGradient(
            listOf(color.copy(alpha = (0.38f * masterAlpha).coerceIn(0f, 1f)), Color.Transparent),
            startY = 0f,
            endY = ambientReach
        ),
        topLeft = Offset(0f, 0f),
        size = Size(w, ambientReach)
    )
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, secondary.copy(alpha = (0.38f * masterAlpha).coerceIn(0f, 1f))),
            startY = h - ambientReach,
            endY = h
        ),
        topLeft = Offset(0f, h - ambientReach),
        size = Size(w, ambientReach)
    )

    for (i in 0..2) {
        val rawP = (phase + i * 0.333f) % 1f
        // Cubic ease-out so ripples decelerate naturally as they expand
        val easedP = 1f - (1f - rawP).pow(2.3f)
        val offset = easedP * maxReach
        // Smooth fade-in at birth and fade-out at peak
        val birthFade = (rawP / 0.12f).coerceIn(0f, 1f)
        val alpha = birthFade * (1f - easedP).pow(1.4f) * masterAlpha
        val c = if (i % 2 == 0) color else secondary
        val strokeW = (strokePx * (1.3f - easedP * 0.65f)).coerceAtLeast(1.5f)

        val hBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                c.copy(alpha = (0.85f * alpha).coerceIn(0f, 1f)),
                Color.White.copy(alpha = (0.92f * alpha).coerceIn(0f, 1f)),
                c.copy(alpha = (0.85f * alpha).coerceIn(0f, 1f)),
                Color.Transparent
            )
        )
        val glowBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                c.copy(alpha = (0.32f * alpha).coerceIn(0f, 1f)),
                c.copy(alpha = (0.48f * alpha).coerceIn(0f, 1f)),
                c.copy(alpha = (0.32f * alpha).coerceIn(0f, 1f)),
                Color.Transparent
            )
        )

        // Top horizon ripple arc
        val topPath = Path().apply {
            moveTo(0f, offset * 0.25f)
            cubicTo(
                w * 0.28f, offset * 1.25f,
                w * 0.72f, offset * 1.25f,
                w, offset * 0.25f
            )
        }
        drawPath(path = topPath, brush = glowBrush, style = Stroke(width = strokeW * 2.8f, cap = StrokeCap.Round))
        drawPath(path = topPath, brush = hBrush, style = Stroke(width = strokeW, cap = StrokeCap.Round))

        // Bottom horizon ripple arc
        val botPath = Path().apply {
            moveTo(0f, h - offset * 0.25f)
            cubicTo(
                w * 0.28f, h - offset * 1.25f,
                w * 0.72f, h - offset * 1.25f,
                w, h - offset * 0.25f
            )
        }
        drawPath(path = botPath, brush = glowBrush, style = Stroke(width = strokeW * 2.8f, cap = StrokeCap.Round))
        drawPath(path = botPath, brush = hBrush, style = Stroke(width = strokeW, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawLaserScan(
    w: Float,
    h: Float,
    cornerPx: Float,
    strokePx: Float,
    color: Color,
    secondary: Color,
    phase: Float,
    masterAlpha: Float
) {
    // Smooth cosine harmonic ping-pong instead of robotic linear bounce
    val smoothPingPong = (0.5f - 0.5f * cos(phase * 2.0 * PI).toFloat())
    val scanY = smoothPingPong * h
    val beamHalfHeight = (strokePx * 5.2f).coerceAtLeast(36.dp.toPx())

    // Subtle perimeter frame that intensifies near the current scanY
    val rimBrush = Brush.verticalGradient(
        colors = listOf(
            color.copy(alpha = (0.18f * masterAlpha).coerceIn(0f, 1f)),
            secondary.copy(alpha = (0.75f * masterAlpha).coerceIn(0f, 1f)),
            Color.White.copy(alpha = (0.92f * masterAlpha).coerceIn(0f, 1f)),
            secondary.copy(alpha = (0.75f * masterAlpha).coerceIn(0f, 1f)),
            color.copy(alpha = (0.18f * masterAlpha).coerceIn(0f, 1f))
        ),
        startY = (scanY - h * 0.35f).coerceAtLeast(0f),
        endY = (scanY + h * 0.35f).coerceAtMost(h)
    )
    drawRoundRect(
        brush = rimBrush,
        topLeft = Offset(strokePx / 2f, strokePx / 2f),
        size = Size(w - strokePx, h - strokePx),
        cornerRadius = CornerRadius(cornerPx, cornerPx),
        style = Stroke(width = strokePx * 0.75f)
    )

    // Soft atmospheric laser wash
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                secondary.copy(alpha = (0.28f * masterAlpha).coerceIn(0f, 1f)),
                color.copy(alpha = (0.72f * masterAlpha).coerceIn(0f, 1f)),
                secondary.copy(alpha = (0.28f * masterAlpha).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            startY = scanY - beamHalfHeight,
            endY = scanY + beamHalfHeight
        ),
        topLeft = Offset(0f, (scanY - beamHalfHeight).coerceAtLeast(0f)),
        size = Size(w, beamHalfHeight * 2f)
    )

    // Tapered horizontal laser core line
    drawLine(
        brush = Brush.horizontalGradient(
            colors = listOf(
                color.copy(alpha = (0.65f * masterAlpha).coerceIn(0f, 1f)),
                Color.White.copy(alpha = (0.96f * masterAlpha).coerceIn(0f, 1f)),
                color.copy(alpha = (0.65f * masterAlpha).coerceIn(0f, 1f))
            )
        ),
        start = Offset(0f, scanY),
        end = Offset(w, scanY),
        strokeWidth = (strokePx * 0.55f).coerceAtLeast(2f),
        cap = StrokeCap.Round
    )

    // Bright edge contact nodes where the laser meets the left & right screen edges
    val nodeRadius = (strokePx * 3.0f).coerceAtLeast(14.dp.toPx())
    listOf(Offset(0f, scanY), Offset(w, scanY)).forEach { edgePos ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.95f * masterAlpha).coerceIn(0f, 1f)),
                    color.copy(alpha = (0.70f * masterAlpha).coerceIn(0f, 1f)),
                    Color.Transparent
                ),
                center = edgePos,
                radius = nodeRadius
            ),
            radius = nodeRadius,
            center = edgePos
        )
    }
}

private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val c = value * saturation
    val x = c * (1f - kotlin.math.abs(((h / 60f) % 2f) - 1f))
    val m = value - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r1 + m, green = g1 + m, blue = b1 + m, alpha = 1f)
}

