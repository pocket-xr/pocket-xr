package com.hayeco.flipspaces.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.core.Look

// Dark everywhere: on see-through glasses black is transparent, so dark UI floats in space.
// 0.5.68: built from [Look], the same tokens as the glasses and the trackpad (one accent, the glass greys), with the
// containers set too, so Material's default purple no longer shows through on the warning cards.
private val SpacesColors = darkColorScheme(
    primary = Color(Look.ACCENT),
    onPrimary = Color.White,
    primaryContainer = Color(Look.ACCENT_DEEP),
    onPrimaryContainer = Color.White,
    secondary = Color(Look.ACCENT),
    onSecondary = Color.White,
    secondaryContainer = Color(Look.GLASS_RAISED),
    onSecondaryContainer = Color(Look.INK),
    tertiary = Color(Look.ACCENT),
    tertiaryContainer = Color(Look.GLASS_RAISED),
    onTertiaryContainer = Color(Look.INK),
    background = Color(Look.PAGE),
    onBackground = Color(Look.INK),
    surface = Color(Look.CARD),
    onSurface = Color(Look.INK),
    surfaceVariant = Color(Look.GLASS_RAISED),
    onSurfaceVariant = Color(Look.INK_2),
    outline = Color(Look.RIM_FOCUS),
    outlineVariant = Color(Look.RIM),
    error = Color(Look.DANGER),
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 0.5.68 (Mike: one bundled font): Inter, Regular and Medium. */
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
)

/**
 * The type scale in [family]: Inter, or in Korean, Japanese and Chinese ([LookType.systemFontLanguage]) the phone's own
 * font, since Inter has no letters for them.
 */
private fun spacesType(family: FontFamily) = Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.inter() = copy(
        fontFamily = family,
        fontWeight = if ((fontWeight ?: FontWeight.Normal) >= FontWeight.Medium) FontWeight.Medium else FontWeight.Normal,
    )
    Typography(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
        headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
    )
}

private val SpacesType = spacesType(Inter)
private val SystemType = spacesType(FontFamily.Default)

@Composable
fun FlipSpacesTheme(content: @Composable () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val type = if (LookType.systemFontLanguage(locale)) SystemType else SpacesType
    MaterialTheme(colorScheme = SpacesColors, typography = type, content = content)
}
