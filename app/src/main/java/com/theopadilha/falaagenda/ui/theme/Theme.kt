package com.theopadilha.falaagenda.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.theopadilha.falaagenda.R

val OffWhite = Color(0xFFF4F1E8)
val DeepGreen = Color(0xFF2F6B52)
val CardWhite = Color(0xFFFFFFFF)
val Ink = Color(0xFF1A1A18)
val Muted = Color(0xFF5E5C56)
val Line = Color(0xFFE4DFD4)
val Missed = Color(0xFF8B3A2F)
val SoftGreen = Color(0xFFE3F2EA)

private val NightBg = Color(0xFF1A221E)
private val NightSurface = Color(0xFF24302B)
private val NightInk = Color(0xFFF4F1EA)
private val NightMuted = Color(0xFFC2C0B8)
private val NightLine = Color(0xFF3A4742)
private val NightPrimary = Color(0xFF8FCBB0)
private val NightMissed = Color(0xFFE08B7A)

val FigtreeFamily = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private val lightColors = lightColorScheme(
    primary = DeepGreen,
    onPrimary = OffWhite,
    primaryContainer = SoftGreen,
    onPrimaryContainer = DeepGreen,
    secondary = DeepGreen,
    onSecondary = OffWhite,
    background = OffWhite,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = OffWhite,
    onSurfaceVariant = Muted,
    outline = Line,
    error = Missed,
    onError = Color.White,
)

private val darkColors = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightBg,
    primaryContainer = DeepGreen,
    onPrimaryContainer = NightInk,
    secondary = NightPrimary,
    onSecondary = NightBg,
    background = NightBg,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF242A27),
    onSurfaceVariant = NightMuted,
    outline = NightLine,
    error = NightMissed,
    onError = NightBg,
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp),
    headlineMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp),
)

@Composable
fun FalaAgendaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors else lightColors,
        typography = typography,
        content = content,
    )
}
