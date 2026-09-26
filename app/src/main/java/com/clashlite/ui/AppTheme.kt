package com.clashlite.ui

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
import com.clashlite.data.DarkMode
import com.clashlite.data.ThemeConfig

/** 将 ThemeConfig 渲染为全局 MaterialTheme */
@Composable
fun AppTheme(
    config: ThemeConfig,
    content: @Composable () -> Unit,
) {
    val darkWhen = when (config.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }

    val context = LocalContext.current
    val scheme = if (config.useDynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (darkWhen) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val seed = Color(config.seedColor.toInt())
        if (darkWhen) {
            val s = darkColorScheme(primary = seed, secondary = seed, tertiary = seed)
            if (config.amoled) s.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainer = Color(0xFF0A0A0A),
                surfaceContainerLow = Color(0xFF060606),
                surfaceContainerHigh = Color(0xFF121212),
            ) else s
        } else {
            lightColorScheme(primary = seed, secondary = seed, tertiary = seed)
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
