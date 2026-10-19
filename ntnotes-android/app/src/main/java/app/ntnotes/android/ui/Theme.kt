package app.ntnotes.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF1F7A6D)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEFE5),
    onPrimaryContainer = Color(0xFF00201B),
    background = Color(0xFFFAFDFB),
    surface = Color(0xFFFAFDFB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD6C6),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFBFEFE5),
    background = Color(0xFF101413),
    surface = Color(0xFF101413),
)

@Composable
fun NtTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
