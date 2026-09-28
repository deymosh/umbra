package com.umbra.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Umbra's own glyphs, for concepts Material Icons has no honest symbol for. Drawn on the standard
 * 24dp grid with a 1.8 stroke so they sit alongside Material's outlined set; tint via Icon's
 * `tint` like any other vector.
 */
object UmbraIcons {

    /**
     * Tor: the onion — three nested layers around a solid core (the circuit's layers of
     * encryption) with the stem and leaf that make the silhouette read as "onion" even at 16dp.
     */
    val Onion: ImageVector by lazy {
        ImageVector.Builder("Onion", 24.dp, 24.dp, 24f, 24f).apply {
            stroked(width = 1.6f) {
                // Outer layer: wide bulb tapering to the neck.
                moveTo(12f, 6.2f)
                curveTo(16.6f, 8.4f, 20f, 11.2f, 20f, 14.9f)
                curveTo(20f, 19f, 16.4f, 21.8f, 12f, 21.8f)
                curveTo(7.6f, 21.8f, 4f, 19f, 4f, 14.9f)
                curveTo(4f, 11.2f, 7.4f, 8.4f, 12f, 6.2f)
                close()
            }
            stroked(width = 1.4f) {
                // Middle layer.
                moveTo(12f, 9.4f)
                curveTo(15f, 11f, 16.8f, 12.8f, 16.8f, 15.2f)
                curveTo(16.8f, 17.6f, 14.7f, 19.2f, 12f, 19.2f)
                curveTo(9.3f, 19.2f, 7.2f, 17.6f, 7.2f, 15.2f)
                curveTo(7.2f, 12.8f, 9f, 11f, 12f, 9.4f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                // Solid core.
                moveTo(12f, 12.6f)
                curveTo(13.5f, 13.5f, 14.2f, 14.4f, 14.2f, 15.4f)
                curveTo(14.2f, 16.5f, 13.2f, 17.1f, 12f, 17.1f)
                curveTo(10.8f, 17.1f, 9.8f, 16.5f, 9.8f, 15.4f)
                curveTo(9.8f, 14.4f, 10.5f, 13.5f, 12f, 12.6f)
                close()
            }
            stroked(width = 1.6f) {
                // Stem.
                moveTo(12f, 6.2f)
                verticalLineTo(3.4f)
            }
            path(fill = SolidColor(Color.Black)) {
                // Leaf curling off the stem.
                moveTo(12f, 4.6f)
                curveTo(12.6f, 2.8f, 14.4f, 2f, 16.4f, 2.3f)
                curveTo(15.9f, 4.2f, 14.1f, 5.2f, 12f, 4.6f)
                close()
            }
        }.build()
    }

    /** The brand mark as a flat glyph (disc, surviving arc, diamond point) for small sizes. */
    val Eclipse: ImageVector by lazy {
        ImageVector.Builder("Eclipse", 24.dp, 24.dp, 24f, 24f).apply {
            stroked {
                moveTo(12f, 4.5f)
                arcTo(7.5f, 7.5f, 0f, true, false, 19.5f, 12f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(18.2f, 6.9f)
                arcTo(1.6f, 1.6f, 0f, true, true, 18.19f, 6.9f)
                close()
            }
        }.build()
    }

    private inline fun ImageVector.Builder.stroked(
        width: Float = 1.8f,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
    ) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }
}
