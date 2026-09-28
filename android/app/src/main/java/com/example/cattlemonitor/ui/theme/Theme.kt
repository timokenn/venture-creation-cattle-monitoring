package com.example.cattlemonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.cattlemonitor.data.CowStatus

private val CattleColorScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = BrandSoft,
    background = Bg,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Surface,
    onSurfaceVariant = InkSoft,
    outline = Line,
    error = StatusAlert,
)

// Dark palette: night-barn feel — deep green-black surfaces, oat text,
// same status hues brightened for contrast.
private val NightBg = Color(0xFF141710)
private val NightSurface = Color(0xFF1C2118)
private val NightInk = Color(0xFFE8E4D4)
private val NightInkSoft = Color(0xFFABA896)
private val NightLine = Color(0xFF33382B)

private val CattleDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FB57F),
    onPrimary = Color(0xFF10140C),
    secondary = BrandSoft,
    background = NightBg,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightInkSoft,
    outline = NightLine,
    error = Color(0xFFE08A7E),
)

@Composable
fun CattleMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CattleDarkColorScheme else CattleColorScheme,
        typography = CattleTypography,
        content = content,
    )
}

/** Single source of truth for cow-status color, used by every screen. */
fun statusColor(status: CowStatus): Color = when (status) {
    CowStatus.NORMAL -> StatusNormal
    CowStatus.WARNING -> StatusWarning
    CowStatus.ALERT -> StatusAlert
    CowStatus.OFFLINE -> StatusOffline
}

// Note: the localized status badge text lives in ui/common/Strings.kt
// (statusLabel) as a @Composable stringResource wrapper — i18n requirement.
