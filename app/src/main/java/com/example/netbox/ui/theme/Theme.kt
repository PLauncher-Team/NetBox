package com.example.netbox.ui.theme

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

enum class AppColorScheme {
    DEFAULT, BLUE, GREEN, ORANGE, TEAL, CUSTOM
}

private val DefaultDark = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)

private val DefaultLight = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000)
)

private val BlueDark = darkColorScheme(
    primary = Blue80,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)
private val BlueLight = lightColorScheme(
    primary = Blue40,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000)
)

private val GreenDark = darkColorScheme(
    primary = Green80,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)
private val GreenLight = lightColorScheme(
    primary = Green40,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000)
)

private val OrangeDark = darkColorScheme(
    primary = Orange80,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)
private val OrangeLight = lightColorScheme(
    primary = Orange40,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000)
)

private val TealDark = darkColorScheme(
    primary = Color(0xFF4CD8E6),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)
private val TealLight = lightColorScheme(
    primary = Color(0xFF006874),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000)
)

@Composable
fun NetBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    colorSchemeOption: AppColorScheme = AppColorScheme.DEFAULT,
    customColor: Color = Color(0xFF6650a4),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        colorSchemeOption == AppColorScheme.CUSTOM -> {
            if (darkTheme) {
                darkColorScheme(
                    primary = customColor,
                    background = Color(0xFF121212),
                    surface = Color(0xFF121212),
                    onBackground = Color(0xFFFFFFFF),
                    onSurface = Color(0xFFFFFFFF)
                )
            } else {
                lightColorScheme(
                    primary = customColor,
                    background = Color(0xFFFFFFFF),
                    surface = Color(0xFFFFFFFF),
                    onBackground = Color(0xFF000000),
                    onSurface = Color(0xFF000000)
                )
            }
        }
        else -> {
            when (colorSchemeOption) {
                AppColorScheme.BLUE -> if (darkTheme) BlueDark else BlueLight
                AppColorScheme.GREEN -> if (darkTheme) GreenDark else GreenLight
                AppColorScheme.ORANGE -> if (darkTheme) OrangeDark else OrangeLight
                AppColorScheme.TEAL -> if (darkTheme) TealDark else TealLight
                else -> if (darkTheme) DefaultDark else DefaultLight
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
