package com.codemew.bluecall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val CallGreen = Color(0xFF1E8E3E)
val HangUpRed = Color(0xFFD93025)

/**
 * Material 3 with the phone's own dynamic colours (One UI's colour palette on
 * Samsung), so BlueCall matches the rest of the system.
 */
@Composable
fun BlueCallTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}
