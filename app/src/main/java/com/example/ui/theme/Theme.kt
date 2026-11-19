package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

private val StudioDarkColorScheme = darkColorScheme(
    primary = TubeAmber,
    onPrimary = OnTubeAmber,
    primaryContainer = TubeAmberContainer,
    onPrimaryContainer = OnTubeAmberContainer,
    secondary = TapeMint,
    onSecondary = OnTapeMint,
    secondaryContainer = TapeMintContainer,
    onSecondaryContainer = OnTapeMintContainer,
    tertiary = RecordCoral,
    onTertiary = OnRecordCoral,
    tertiaryContainer = RecordCoralContainer,
    onTertiaryContainer = OnRecordCoralContainer,
    background = StudioObsidian,
    onBackground = StudioInk,
    surface = StudioSurface,
    onSurface = StudioInk,
    surfaceVariant = StudioSurfaceHigh,
    onSurfaceVariant = StudioInkMuted,
    surfaceContainerLowest = StudioObsidian,
    surfaceContainerLow = StudioSurface,
    surfaceContainer = StudioSurfaceHigh,
    surfaceContainerHigh = StudioSurfaceHighest,
    surfaceContainerHighest = StudioSurfaceHighest,
    outline = StudioOutline,
    outlineVariant = StudioOutlineVariant,
    error = RecordCoral,
    onError = OnRecordCoral,
    errorContainer = RecordCoralContainer,
    onErrorContainer = OnRecordCoralContainer
)

private val StudioLightColorScheme = lightColorScheme(
    primary = DeepAmber,
    onPrimary = OnDeepAmber,
    primaryContainer = DeepAmberContainer,
    onPrimaryContainer = OnDeepAmberContainer,
    secondary = DeepTeal,
    onSecondary = OnDeepTeal,
    secondaryContainer = DeepTealContainer,
    onSecondaryContainer = OnDeepTealContainer,
    tertiary = DeepTerracotta,
    onTertiary = OnDeepTerracotta,
    tertiaryContainer = DeepTerracottaContainer,
    onTertiaryContainer = OnDeepTerracottaContainer,
    background = PaperBackground,
    onBackground = PaperInk,
    surface = PaperSurface,
    onSurface = PaperInk,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperInkMuted,
    surfaceContainerLowest = PaperSurface,
    surfaceContainerLow = PaperBackground,
    surfaceContainer = PaperSurfaceHigh,
    surfaceContainerHigh = PaperSurfaceHighest,
    surfaceContainerHighest = PaperSurfaceHighest,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
    error = DeepTerracotta,
    onError = OnDeepTerracotta,
    errorContainer = DeepTerracottaContainer,
    onErrorContainer = OnDeepTerracottaContainer
)

val LyricVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LyricVaultTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LyricVaultTypography,
        shapes = LyricVaultShapes,
        content = content
    )
}
