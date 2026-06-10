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
val SurfaceLight = Color(0xFFEDEDF2)

private val PhoneBillingColors: ColorScheme = lightColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF0077ED),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = TextDominant,
    surface = Color.White,
    onSurface = TextDominant,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFFD5D5D7),
    error = Color(0xFFB00020)
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
        fontSize = 28.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
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
