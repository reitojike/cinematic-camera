package dev.cinematic.p0

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Shared visual vocabulary; contains no camera or photo processing state. */
internal object CaptureDesign {
    val Ink = Color(0xFF0A0B0C)
    val Panel = Color(0xFF111315)
    val Ruler = Color(0xF00D0F10)
    val Selected = Color(0xFF1E2225)
    val Line = Color(0xFF2C3034)
    val Border = Color(0xFF3A3F44)
    val Text = Color(0xFFE4E6E8)
    val Muted = Color(0xFFA3AAB1)
    val Disabled = Color(0xFF6B7177)
    val Amber = Color(0xFFE6A35C)
    val Accepted = Color(0xFF7CC4A5)
    val Failed = Color(0xFFEF8A74)
    val Recording = Color(0xFFE5483B)
    val Inverted = Color(0xFFCFD3D6)
    val SwitchBackground = Color(0xFF0D0F10)
    val SwitchBorder = Color(0xFF5A6066)
    val ShutterInactive = Color(0xFF4A5056)
    val ShutterExposing = Color(0xFF8D9397)
    val RecordingText = Color(0xFFFF7A6E)
    val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = .66.sp)
    val Value = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp)
    val Explanation = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)
    val Data = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val Body = Explanation
    val Scrim = Ink.copy(alpha = .9f)
    val ScrimLight = Ink.copy(alpha = .7f)
    fun messageTint(tone: CaptureChromeTone) = captureChromeColor(tone).copy(alpha = .08f)
    const val SettingsHeight = 504f
}
