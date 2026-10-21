package com.helix.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared Android rendering tokens. No navigation, persistence or execution policy lives here. */
internal object HelixUiDesign {
    val contentWidth = 900.dp
    val formWidth = 660.dp
    val conversationWidth = 740.dp
    val pagePadding = 16.dp
    val cardRadius = 16.dp
    val composerRadius = 24.dp
    val shapes =
        Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        )

    private fun text(
        size: Int,
        height: Int,
        weight: FontWeight = FontWeight.Normal,
    ) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = height.sp,
        letterSpacing = 0.sp,
    )

    val typography =
        Typography(
            displayLarge = text(36, 46, FontWeight.Medium),
            displayMedium = text(32, 42, FontWeight.Medium),
            displaySmall = text(30, 39, FontWeight.Medium),
            headlineLarge = text(28, 36, FontWeight.Medium),
            headlineMedium = text(24, 32, FontWeight.Medium),
            headlineSmall = text(22, 30, FontWeight.Medium),
            titleLarge = text(20, 28, FontWeight.Medium),
            titleMedium = text(16, 25, FontWeight.Medium),
            titleSmall = text(14, 22, FontWeight.Medium),
            bodyLarge = text(15, 24),
            bodyMedium = text(14, 22),
            bodySmall = text(12, 18),
            labelLarge = text(14, 22, FontWeight.Medium),
            labelMedium = text(12, 18, FontWeight.Medium),
            labelSmall = text(11, 17, FontWeight.Medium),
        )

    fun colors(dark: Boolean): ColorScheme = if (dark) darkColors else lightColors

    private val darkColors =
        darkColorScheme(
            primary = Color(0xFFAED9B7),
            onPrimary = Color(0xFF12341E),
            primaryContainer = Color(0xFF284B32),
            onPrimaryContainer = Color(0xFFD3F0DA),
            secondary = Color(0xFFB4CCB9),
            onSecondary = Color(0xFF203627),
            secondaryContainer = Color(0xFF324B39),
            onSecondaryContainer = Color(0xFFD3F0DA),
            tertiary = Color(0xFFC3CCAA),
            onTertiary = Color(0xFF2C351F),
            tertiaryContainer = Color(0xFF424D34),
            onTertiaryContainer = Color(0xFFDFE8C5),
            background = Color(0xFF161C19),
            onBackground = Color(0xFFE2EAE2),
            surface = Color(0xFF202823),
            onSurface = Color(0xFFE2EAE2),
            surfaceVariant = Color(0xFF28332C),
            onSurfaceVariant = Color(0xFFACBBAE),
            surfaceContainerLowest = Color(0xFF111713),
            surfaceContainerLow = Color(0xFF202823),
            surfaceContainer = Color(0xFF28332C),
            surfaceContainerHigh = Color(0xFF303E34),
            surfaceContainerHighest = Color(0xFF38473C),
            surfaceTint = Color(0xFFAED9B7),
            outline = Color(0xFF87988A),
            outlineVariant = Color(0xFF3E4B41),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF8E201D),
            onErrorContainer = Color(0xFFFFDAD6),
            inverseSurface = Color(0xFFE2EAE2),
            inverseOnSurface = Color(0xFF202823),
            inversePrimary = Color(0xFF285E3B),
        )

    private val lightColors =
        lightColorScheme(
            primary = Color(0xFF285E3B),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCEBDD),
            onPrimaryContainer = Color(0xFF173D24),
            secondary = Color(0xFF4D6654),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCEBDD),
            onSecondaryContainer = Color(0xFF173D24),
            tertiary = Color(0xFF596545),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFDDE8C7),
            onTertiaryContainer = Color(0xFF28351B),
            background = Color(0xFFFFFEF9),
            onBackground = Color(0xFF202A24),
            surface = Color(0xFFFFFEF9),
            onSurface = Color(0xFF202A24),
            surfaceVariant = Color(0xFFF4F7F0),
            onSurfaceVariant = Color(0xFF5C695F),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF4F7F0),
            surfaceContainer = Color(0xFFEEF2E9),
            surfaceContainerHigh = Color(0xFFE8EEE3),
            surfaceContainerHighest = Color(0xFFE1E8DC),
            surfaceTint = Color(0xFF285E3B),
            outline = Color(0xFF78877B),
            outlineVariant = Color(0xFFDCE3D8),
            error = Color(0xFFA73833),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410003),
            inverseSurface = Color(0xFF28332C),
            inverseOnSurface = Color(0xFFF4F7F0),
            inversePrimary = Color(0xFFAED9B7),
        )
}
