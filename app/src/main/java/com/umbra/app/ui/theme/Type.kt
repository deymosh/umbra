package com.umbra.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.umbra.app.R

// All faces are bundled in res/font (SIL OFL, licenses in assets/licenses/). Never switch these
// to Google's downloadable-fonts provider: that fetch goes through Play Services' own network
// stack, outside the Tor-only OkHttp client.

private fun variable(res: Int, weight: FontWeight) = Font(
    resId = res,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** UI and reading face. */
val GeistFamily = FontFamily(
    variable(R.font.geist, FontWeight.Normal),
    variable(R.font.geist, FontWeight.Medium),
    variable(R.font.geist, FontWeight.SemiBold),
    variable(R.font.geist, FontWeight.Bold)
)

/** Keys, npubs, event ids, relay URLs — Nostr's own vernacular reads as data, not prose. */
val GeistMonoFamily = FontFamily(
    variable(R.font.geist_mono, FontWeight.Normal),
    variable(R.font.geist_mono, FontWeight.Medium)
)

/** The wordmark and hero moments only — never body copy. */
val InstrumentSerifFamily = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal))

private val Ui = TextStyle(fontFamily = GeistFamily)

val UmbraTypography = Typography(
    displayLarge = TextStyle(fontFamily = InstrumentSerifFamily, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = InstrumentSerifFamily, fontSize = 48.sp, lineHeight = 50.sp, letterSpacing = (-0.015).em),
    displaySmall = TextStyle(fontFamily = InstrumentSerifFamily, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    headlineLarge = Ui.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    headlineMedium = Ui.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    headlineSmall = Ui.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em),
    titleLarge = Ui.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    titleMedium = Ui.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.005).em),
    titleSmall = Ui.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Ui.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.em),
    bodyMedium = Ui.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.em),
    bodySmall = Ui.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.em),
    labelLarge = Ui.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = Ui.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = Ui.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.01.em)
)

/** Monospace companion to [UmbraTypography] for identifiers (see [GeistMonoFamily]). */
val MonoStyle = TextStyle(fontFamily = GeistMonoFamily, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.em)
