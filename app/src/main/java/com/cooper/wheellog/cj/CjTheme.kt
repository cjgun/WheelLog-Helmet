package com.cooper.wheellog.cj

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cooper.wheellog.R

/** Цвета нового дизайна (макет cj-helmet/maket/istoriya/26-ves-dizajn.html). */
object Cj {
    val bg = Color(0xFF0B0D10)
    val ink = Color(0xFFEEF0F2)
    val mute = Color(0xFF7D848E)
    val dim = Color(0xFF4A5059)
    val hair = Color(0x12FFFFFF)
    val acc = Color(0xFFFF9A2E)      // скорость, акцент
    val pwm = Color(0xFF5AB8FF)
    val cur = Color(0xFFC9A0FF)
    val volt = Color(0xFF4FD18B)
    val warm = Color(0xFFFFCF8A)
    val red = Color(0xFFFF4B3E)
    val green = Color(0xFF4FD18B)
}

@OptIn(ExperimentalTextApi::class)
private fun manrope(w: Int) = Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
val Manrope = FontFamily(manrope(200), manrope(300), manrope(400), manrope(500), manrope(600), manrope(700))

private val base = TextStyle(fontFamily = Manrope, color = Cj.ink)

private val CjTypography = Typography(
    displayLarge = base.copy(fontWeight = FontWeight(200), fontSize = 96.sp),
    headlineLarge = base.copy(fontWeight = FontWeight(300), fontSize = 30.sp),
    titleLarge = base.copy(fontWeight = FontWeight(600), fontSize = 17.sp),
    titleMedium = base.copy(fontWeight = FontWeight(600), fontSize = 15.sp),
    bodyLarge = base.copy(fontWeight = FontWeight(500), fontSize = 15.sp),
    bodyMedium = base.copy(fontWeight = FontWeight(500), fontSize = 14.sp),
    bodySmall = base.copy(fontWeight = FontWeight(500), fontSize = 12.sp, color = Cj.mute),
    labelLarge = base.copy(fontWeight = FontWeight(600), fontSize = 13.sp),
    labelMedium = base.copy(fontWeight = FontWeight(600), fontSize = 11.sp, letterSpacing = 1.5.sp),
    labelSmall = base.copy(fontWeight = FontWeight(600), fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute),
)

private val CjColors = darkColorScheme(
    primary = Cj.acc, onPrimary = Color(0xFF1A0D00),
    secondary = Cj.acc, onSecondary = Color(0xFF1A0D00),
    secondaryContainer = Color(0x24FF9A2E), onSecondaryContainer = Cj.acc,
    background = Cj.bg, onBackground = Cj.ink,
    surface = Cj.bg, onSurface = Cj.ink,
    surfaceVariant = Color(0xFF14171C), onSurfaceVariant = Cj.mute,
    surfaceContainer = Color(0xFF101318), surfaceContainerHigh = Color(0xFF14181D),
    outline = Color(0x22FFFFFF), outlineVariant = Cj.hair,
    error = Cj.red,
)

@Composable
fun CjTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = CjColors, typography = CjTypography, content = content)
