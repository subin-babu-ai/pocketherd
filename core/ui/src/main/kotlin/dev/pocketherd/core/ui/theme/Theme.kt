package dev.pocketherd.core.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = Color(0xFF79C0FF),
    secondary = Color(0xFF56D4DD),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0969DA),
    secondary = Color(0xFF1B7C83),
)

@Composable
fun PocketHerdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Colours for the four agent states.
 *
 * Blocked is the only one that gets a saturated red, because it is the only one that means
 * the user has to do something. Status is never signalled by colour alone; every call site
 * pairs these with a text label so the list stays readable without colour vision.
 */
object StatusColors {
    val Blocked = Color(0xFFFF7B72)
    val Working = Color(0xFFD29922)
    val Done = Color(0xFF3FB950)
    val Idle = Color(0xFF6E7681)
    val Unknown = Color(0xFF484F58)
}
