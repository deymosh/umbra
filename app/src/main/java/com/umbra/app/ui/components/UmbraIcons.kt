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

    /** Tor: an onion's layered bulb — the circuit's layers of encryption. */
    val Onion: ImageVector by lazy {
        ImageVector.Builder("Onion", 24.dp, 24.dp, 24f, 24f).apply {
            stroked {
                // Outer bulb
                moveTo(12f, 5.5f)
                curveTo(16.2f, 8.2f, 19.5f, 11f, 19.5f, 14.5f)
                curveTo(19.5f, 18.4f, 16.2f, 21f, 12f, 21f)
                curveTo(7.8f, 21f, 4.5f, 18.4f, 4.5f, 14.5f)
                curveTo(4.5f, 11f, 7.8f, 8.2f, 12f, 5.5f)
                close()
            }
            stroked {
                // Middle layer
                moveTo(12f, 8.6f)
                curveTo(14.4f, 10.4f, 15.8f, 12.3f, 15.8f, 14.6f)
                curveTo(15.8f, 16.9f, 14.2f, 18.4f, 12f, 18.4f)
                curveTo(9.8f, 18.4f, 8.2f, 16.9f, 8.2f, 14.6f)
                curveTo(8.2f, 12.3f, 9.6f, 10.4f, 12f, 8.6f)
                close()
            }
            stroked {
                // Core
                moveTo(12f, 12f)
                verticalLineTo(15.8f)
            }
            stroked {
                // Sprout
                moveTo(12f, 5.5f)
                curveTo(12f, 4.2f, 12.8f, 3.2f, 14.3f, 2.8f)
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

    private inline fun ImageVector.Builder.stroked(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }
}
