package com.example.phonebilling.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AppleBlue = Color(0xFF0071E3)
val TextDominant = Color(0xFF1D1D1F)
val TextSecondary = Color(0xFF333336)
val TextTertiary = Color(0xFF6E6E73)
val SurfaceLight = Color(0xFFE5E5EA)

private val PhoneBillingColors: ColorScheme = lightColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF34C759),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F7),
    onBackground = TextDominant,
    surface = Color.White,
    onSurface = TextDominant,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFFD1D1D6),
    error = Color(0xFFD70015)
)

private val PhoneBillingTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 44.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

private val PhoneBillingShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun PhoneBillingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhoneBillingColors,
        typography = PhoneBillingTypography,
        shapes = PhoneBillingShapes,
        content = content
    )
}
