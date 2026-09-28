package com.umbra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.umbra.app.ui.theme.UmbraTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Umbra's brand mark drawn in code: a shadow disc at totality, its corona, one surviving arc of
 * light and the "diamond ring" point — the same geometry as the launcher icon, but able to animate.
 *
 * @param ignition 0..1 — how lit the corona is. The TorGate hero animates this from dim to full as
 * the Tor circuit comes up; everywhere else leave it at 1.
 */
@Composable
fun EclipseMark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ignition: Float = 1f,
    corona: Color = UmbraTheme.colors.corona,
    flare: Color = UmbraTheme.colors.zap,
    disc: Color = Color(0xFF0B0A12)
) {
    Canvas(modifier.size(size)) {
        drawEclipse(
            ignition = ignition.coerceIn(0f, 1f),
            corona = corona,
            flare = flare,
            disc = disc,
            flareAngleDeg = -38f
        )
    }
}

internal fun DrawScope.drawEclipse(
    ignition: Float,
    corona: Color,
    flare: Color,
    disc: Color,
    flareAngleDeg: Float,
    discScale: Float = 0.62f,
    drawHalo: Boolean = true
) {
    val c = center
    val r = size.minDimension / 2f * discScale
    if (drawHalo) {
        // Corona: a soft radial falloff just outside the disc.
        drawCircle(
            brush = Brush.radialGradient(
                // Peaks right at the disc's rim (discScale of the half-size) and falls off outward.
                0f to corona.copy(alpha = 0.7f * ignition),
                discScale to corona.copy(alpha = 0.7f * ignition),
                (discScale + (1f - discScale) * 0.3f) to corona.copy(alpha = 0.22f * ignition),
                1f to Color.Transparent,
                center = c,
                radius = size.minDimension / 2f
            ),
            radius = size.minDimension / 2f,
            center = c
        )
    }
    drawCircle(color = disc, radius = r, center = c)
    // Rim: the thinnest possible edge so the disc reads against a black background.
    drawCircle(
        color = corona.copy(alpha = 0.22f + 0.2f * ignition),
        radius = r,
        center = c,
        style = Stroke(width = r * 0.035f)
    )
    val sweep = 34f + 22f * ignition
    drawArc(
        color = flare.copy(alpha = 0.55f + 0.4f * ignition),
        startAngle = flareAngleDeg - sweep,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(r * 2, r * 2),
        style = Stroke(width = r * 0.075f, cap = StrokeCap.Round)
    )
    val rad = Math.toRadians(flareAngleDeg.toDouble())
    val point = Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat())
    drawCircle(
        brush = Brush.radialGradient(
            0f to flare.copy(alpha = 0.9f * ignition),
            1f to Color.Transparent,
            center = point,
            radius = r * 0.55f
        ),
        radius = r * 0.55f,
        center = point
    )
    drawCircle(color = flare, radius = r * 0.12f, center = point)
}

/**
 * Deterministic per-pubkey avatar for accounts with no picture: an eclipse whose corona hue and
 * diamond-ring angle are derived from the key, so different people stay visually distinct in a
 * feed instead of all sharing one identical placeholder.
 */
@Composable
fun PubkeyEclipseAvatar(
    pubkey: String,
    modifier: Modifier = Modifier
) {
    val (hue, angle) = remember(pubkey) { pubkeySeed(pubkey) }
    val corona = remember(hue) { Color.hsl(hue, 0.7f, 0.76f) }
    val background = remember(hue) { Color.hsl(hue, 0.4f, 0.2f) }
    val flare = UmbraTheme.colors.zap
    Canvas(modifier) {
        drawRect(
            brush = Brush.radialGradient(
                0f to corona.copy(alpha = 0.45f),
                0.6f to background,
                1f to background,
                center = center,
                radius = size.minDimension * 0.75f
            )
        )
        drawEclipse(
            ignition = 1f,
            corona = corona,
            flare = flare,
            disc = Color(0xFF09080E),
            flareAngleDeg = angle,
            discScale = 0.68f,
            drawHalo = false
        )
    }
}

internal fun pubkeySeed(pubkey: String): Pair<Float, Float> {
    // Pubkeys are uniformly random hex, so their leading bytes already spread evenly; anything
    // else (malformed input) falls back to a mixed string hash.
    val bits = pubkey.take(8).toLongOrNull(16)?.toInt() ?: run {
        var h = pubkey.hashCode()
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h xor (h ushr 13)
    }
    val hue = ((bits ushr 1) % 360).toFloat()
    val angle = (((bits ushr 12) % 360) - 180).toFloat()
    return hue to angle
}
