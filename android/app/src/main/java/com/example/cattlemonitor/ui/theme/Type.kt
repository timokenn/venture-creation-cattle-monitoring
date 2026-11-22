package com.example.cattlemonitor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// "sans-serif-condensed" is a built-in Android system font (Roboto Condensed),
// used here for the display/tag feel from the design — no bundled font files
// or network fetch required.
val DisplayFont = FontFamily(
    Font(familyName = DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.SemiBold),
    Font(familyName = DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Bold),
)
val BodyFont = FontFamily.SansSerif

val CattleTypography = Typography(
    // Big numbers (herd summary, current temperature).
    displaySmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp),
    // Screen titles.
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp),
    // Card titles.
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.6.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)
