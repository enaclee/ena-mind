package com.enamind.ai.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.Settings
import com.example.R

val PlusJakartaSansFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val SpaceGroteskFontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val InterFontFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold)
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

private val AiStudioBlueDark = Color(0xFF8AB4F8)
private val AiStudioBlueLight = Color(0xFF1A73E8)
private val Green = Color(0xFF10B981)
private val Red = Color(0xFFEF4444)
private val Yellow = Color(0xFFF59E0B)
private val IceBlue = Color(0xFF60A5FA)

// Глубокая тёмная палитра (OLED Dark): по-настоящему тёмный фон, контрастные карточки и деликатные контуры
private val DarkScheme = darkColorScheme(
    primary = AiStudioBlueDark,
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF142238),
    onPrimaryContainer = Color(0xFFD2E3FC),
    secondary = Color(0xFFC4C7C5),
    onSecondary = Color(0xFF16181E),
    secondaryContainer = Color(0xFF141822),
    onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Yellow,
    onTertiary = Color(0xFF000000),
    background = Color(0xFF050608),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF0C0E14),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF131620),
    onSurfaceVariant = Color(0xFF949BA8),
    outline = Color(0xFF2B3142),
    outlineVariant = Color(0xFF1E2230),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410)
)

// Палитра в духе Google AI Studio Light — воздушный светлый фон и белые карточки с тонкими границами
private val LightScheme = lightColorScheme(
    primary = AiStudioBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE),
    onPrimaryContainer = Color(0xFF174EA6),
    secondary = Color(0xFF444746),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F3F4),
    onSecondaryContainer = Color(0xFF1F1F1F),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFFDADCE0),
    outlineVariant = Color(0xFFE8EAED),
    error = Color(0xFFD93025),
    onError = Color.White
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    )
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun EnaMindTheme(theme: Settings.Theme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        Settings.Theme.BLACK -> true
        Settings.Theme.WHITE -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

val Color.Companion.EnaGreen: Color get() = Green
val Color.Companion.EnaRed: Color get() = Red
val Color.Companion.EnaYellow: Color get() = Yellow
val Color.Companion.EnaIce: Color get() = IceBlue
val Color.Companion.TgBlue: Color get() = AiStudioBlueLight
