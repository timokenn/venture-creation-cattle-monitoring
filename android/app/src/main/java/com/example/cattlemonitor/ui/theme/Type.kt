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
)
val BodyFont = FontFamily.SansSerif

val CattleTypography = Typography(
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.3.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.2.sp),
)
