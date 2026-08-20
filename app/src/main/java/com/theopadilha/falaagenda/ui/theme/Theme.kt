package com.theopadilha.falaagenda.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.theopadilha.falaagenda.R

val OffWhite = Color(0xFFF7F5F0)
val DeepGreen = Color(0xFF16382B)
val CardWhite = Color(0xFFFFFFFF)
val Ink = Color(0xFF1A1A18)
val Muted = Color(0xFF5E5C56)
val Line = Color(0xFFE4DFD4)
val Missed = Color(0xFF8B3A2F)
val SoftGreen = Color(0xFFE7F0EB)

val FigtreeFamily = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private val colors = lightColorScheme(
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

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = Ink),
    headlineMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Ink),
    titleLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Ink),
    titleMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Ink),
    bodyLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, color = Ink, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = Ink, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Ink),
)

@Composable
fun FalaAgendaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        content = content,
    )
}
