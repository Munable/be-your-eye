package app.beyoureyes.monitor

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object ProductColors {
    // Quiet measuring-instrument palette; intentionally avoids an operations-dashboard look.
    val Background = Color(0xFF101614)
    val Surface = Color(0xFF17201D)
    val SurfaceRaised = Color(0xFF1E2925)
    val SurfaceHighlight = Color(0xFF27332F)
    val Border = Color(0xFF33413C)
    val Cyan = Color(0xFF86DBC9)
    val CyanSoft = Color(0xFF1C342E)
    val Green = Color(0xFF7DD6AB)
    val GreenSoft = Color(0xFF18382B)
    val Amber = Color(0xFFEFC36E)
    val AmberSoft = Color(0xFF3A2F19)
    val TextPrimary = Color(0xFFF2F5EF)
    val TextSecondary = Color(0xFFC8D0CA)
    val TextMuted = Color(0xFF929F98)
    val Error = Color(0xFFF08A82)
    val ErrorSoft = Color(0xFF432523)
}

private val ProductColorScheme = darkColorScheme(
    primary = ProductColors.Cyan,
    onPrimary = ProductColors.Background,
    primaryContainer = ProductColors.CyanSoft,
    onPrimaryContainer = ProductColors.Cyan,
    secondary = ProductColors.Green,
    onSecondary = ProductColors.Background,
    secondaryContainer = ProductColors.GreenSoft,
    onSecondaryContainer = ProductColors.Green,
    tertiary = ProductColors.Amber,
    onTertiary = ProductColors.Background,
    tertiaryContainer = ProductColors.AmberSoft,
    onTertiaryContainer = ProductColors.Amber,
    background = ProductColors.Background,
    onBackground = ProductColors.TextPrimary,
    surface = ProductColors.Surface,
    onSurface = ProductColors.TextPrimary,
    surfaceVariant = ProductColors.SurfaceRaised,
    onSurfaceVariant = ProductColors.TextSecondary,
    surfaceTint = ProductColors.Cyan,
    inverseSurface = ProductColors.TextSecondary,
    inverseOnSurface = ProductColors.Surface,
    inversePrimary = ProductColors.CyanSoft,
    outline = ProductColors.Border,
    outlineVariant = ProductColors.SurfaceHighlight,
    error = ProductColors.Error,
    onError = ProductColors.Background,
    errorContainer = ProductColors.ErrorSoft,
    onErrorContainer = ProductColors.Error,
    scrim = Color.Black,
    surfaceBright = ProductColors.SurfaceHighlight,
    surfaceDim = ProductColors.Background,
    surfaceContainerLowest = ProductColors.Background,
    surfaceContainerLow = ProductColors.Surface,
    surfaceContainer = ProductColors.SurfaceRaised,
    surfaceContainerHigh = ProductColors.SurfaceHighlight,
    surfaceContainerHighest = ProductColors.Border,
)

private val ProductTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 33.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val ProductShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(15.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(15.dp),
)

@Composable
internal fun BeYourEyeTheme(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val darkConfiguration = remember(configuration) {
        Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
    }
    CompositionLocalProvider(LocalConfiguration provides darkConfiguration) {
        MaterialTheme(
            colorScheme = ProductColorScheme,
            typography = ProductTypography,
            shapes = ProductShapes,
            content = content,
        )
    }
}
