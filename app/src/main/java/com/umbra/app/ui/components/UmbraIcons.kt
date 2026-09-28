package com.umbra.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.vectorResource
import com.umbra.app.R

/**
 * Glyphs for concepts Material Icons has no honest symbol for. Drawn on the standard
 * 24dp grid with a 1.8 stroke so they sit alongside Material's outlined set; tint via Icon's
 * `tint` like any other vector.
 */
object UmbraIcons {

    /** Tor: the Tor Project's own onion mark (see res/drawable/ic_tor_onion.xml for source). */
    val Onion: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_tor_onion)

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
