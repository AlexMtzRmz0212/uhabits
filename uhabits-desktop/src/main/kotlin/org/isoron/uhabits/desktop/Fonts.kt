package org.isoron.uhabits.desktop

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

private fun hasResource(path: String) =
    Thread.currentThread().contextClassLoader?.getResource(path) != null

/** FontAwesome, the same icon font the phone uses for the check, cross and question glyphs. */
val FontAwesome: FontFamily = FontFamily(Font(resource = "typefaces/FontAwesome.ttf"))

const val FA_CHECK = ""
const val FA_TIMES = ""
const val FA_QUESTION = ""
const val FA_EYE = ""
const val FA_EYE_SLASH = ""
const val FA_PEN = ""
const val FA_FORWARD = ""

/**
 * Roboto, the phone's system font. Drop Roboto-Regular/Medium/Bold.ttf into
 * src/main/resources/typefaces/ and it is used everywhere; without the files the
 * platform default font is used.
 */
val AppFont: FontFamily =
    if (hasResource("typefaces/Roboto-Regular.ttf")) {
        FontFamily(
            Font(resource = "typefaces/Roboto-Regular.ttf", weight = FontWeight.Normal),
            Font(resource = "typefaces/Roboto-Medium.ttf", weight = FontWeight.Medium),
            Font(resource = "typefaces/Roboto-Bold.ttf", weight = FontWeight.Bold)
        )
    } else {
        FontFamily.Default
    }

/** Material3 typography with every style switched to [AppFont]. */
fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.app() = copy(fontFamily = AppFont)
    return Typography(
        displayLarge = base.displayLarge.app(),
        displayMedium = base.displayMedium.app(),
        displaySmall = base.displaySmall.app(),
        headlineLarge = base.headlineLarge.app(),
        headlineMedium = base.headlineMedium.app(),
        headlineSmall = base.headlineSmall.app(),
        titleLarge = base.titleLarge.app(),
        titleMedium = base.titleMedium.app(),
        titleSmall = base.titleSmall.app(),
        bodyLarge = base.bodyLarge.app(),
        bodyMedium = base.bodyMedium.app(),
        bodySmall = base.bodySmall.app(),
        labelLarge = base.labelLarge.app(),
        labelMedium = base.labelMedium.app(),
        labelSmall = base.labelSmall.app()
    )
}
