package io.github.loganrosen.wattdog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.loganrosen.wattdog.batterystats.analysis.Severity

@Composable
fun WattdogTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun Severity.color(): Color = when (this) {
    Severity.CRITICAL -> MaterialTheme.colorScheme.error
    Severity.WARNING -> if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFE65100)
    Severity.INFO -> MaterialTheme.colorScheme.primary
}
