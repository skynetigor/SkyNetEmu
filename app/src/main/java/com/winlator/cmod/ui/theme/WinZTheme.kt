package com.winlator.cmod.ui.theme

import android.R as AndroidR
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.winlator.cmod.R

enum class WinlatorThemeType(
    val id: String,
    val displayName: String,
    val description: String
) {
    WHITE("white", "SkyNet Light", "Bright surfaces with violet and cyan accents"),
    BLACK("black", "SkyNet", "Deep navy with cyan and violet · Default"),
    AMOLED("amoled", "SkyNet AMOLED", "Pure black for OLED displays, cyan and violet accents"),
    BLUE("blue", "Blue", "Dark interface with cool blue accents"),
    RED("red", "Red", "Dark interface with warm red accents"),
    PURPLE("purple", "Purple", "Dark interface with rich purple accents");

    companion object {
        fun fromId(id: String?): WinlatorThemeType = values().firstOrNull { it.id == id } ?: BLACK
    }
}

object WinlatorThemeManager {
    private const val PREF_KEY = "winlator_ui_theme"
    private val currentState = mutableStateOf(WinlatorThemeType.BLACK)
    private var initialized = false

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        currentState.value = WinlatorThemeType.fromId(prefs.getString(PREF_KEY, WinlatorThemeType.BLACK.id))
        initialized = true
    }

    @Composable
    fun currentTheme(): WinlatorThemeType {
        val context = LocalContext.current
        ensureInitialized(context)
        return currentState.value
    }

    fun currentTheme(context: Context): WinlatorThemeType {
        ensureInitialized(context)
        return currentState.value
    }

    fun setTheme(context: Context, theme: WinlatorThemeType) {
        ensureInitialized(context)
        if (currentState.value == theme) return
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putString(PREF_KEY, theme.id)
            .apply()
        currentState.value = theme
    }
}

private val BlackColors = darkColorScheme(
    primary = Color(0xFF5EE7FF), onPrimary = Color(0xFF00252C),
    primaryContainer = Color(0xFF1B2C66), onPrimaryContainer = Color(0xFFD2F6FF),
    secondary = Color(0xFFB07CFF), onSecondary = Color(0xFF2A0F5C),
    secondaryContainer = Color(0xFF2E1B6B), onSecondaryContainer = Color(0xFFE7D8FF),
    tertiary = Color(0xFF8CA2FF), onTertiary = Color(0xFF0B1A5A),
    background = Color(0xFF070B1E), onBackground = Color(0xFFEAF0FF),
    surface = Color(0xFF0F1530), onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF1A2147), onSurfaceVariant = Color(0xFF9FAAD6),
    outline = Color(0xFF3C4A85), outlineVariant = Color(0xFF1F2A5A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val AmoledColors = darkColorScheme(
    primary = Color(0xFF5EE7FF), onPrimary = Color(0xFF00252C),
    primaryContainer = Color(0xFF0E2A38), onPrimaryContainer = Color(0xFFCFF7FF),
    secondary = Color(0xFFB07CFF), onSecondary = Color(0xFF2A0F5C),
    secondaryContainer = Color(0xFF1C1038), onSecondaryContainer = Color(0xFFE7D8FF),
    tertiary = Color(0xFF8CA2FF), onTertiary = Color(0xFF0B1A5A),
    background = Color.Black, onBackground = Color(0xFFEAF0FF),
    surface = Color(0xFF04060F), onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF0B1022), onSurfaceVariant = Color(0xFF8E99C7),
    outline = Color(0xFF2A3566), outlineVariant = Color(0xFF151B3A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val WhiteColors = lightColorScheme(
    primary = Color(0xFF3A1C8F), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3DBFF), onPrimaryContainer = Color(0xFF1E0B5C),
    secondary = Color(0xFF0A7C99), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF4FF), onSecondaryContainer = Color(0xFF00323E),
    tertiary = Color(0xFF7C4DDB), onTertiary = Color.White,
    background = Color(0xFFF4F6FF), onBackground = Color(0xFF0A1030),
    surface = Color.White, onSurface = Color(0xFF0A1030),
    surfaceVariant = Color(0xFFE6E9F8), onSurfaceVariant = Color(0xFF4A5280),
    outline = Color(0xFF7C84B0), outlineVariant = Color(0xFFCDD2EC),
    error = Color(0xFFBA1A1A), onError = Color.White
)

private val BlueColors = darkColorScheme(
    primary = Color(0xFF82B8FF), onPrimary = Color(0xFF001B3A),
    primaryContainer = Color(0xFF173A63), onPrimaryContainer = Color(0xFFD5E7FF),
    secondary = Color(0xFFAFC9EA), onSecondary = Color(0xFF102033),
    secondaryContainer = Color(0xFF17283D), onSecondaryContainer = Color(0xFFD3E5FF),
    background = Color(0xFF050A12), onBackground = Color(0xFFF2F6FC),
    surface = Color(0xFF0C1320), onSurface = Color(0xFFF2F6FC),
    surfaceVariant = Color(0xFF121D2D), onSurfaceVariant = Color(0xFFA9B9CD),
    outline = Color(0xFF45617F), outlineVariant = Color(0xFF25384F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val RedColors = darkColorScheme(
    primary = Color(0xFFFF8A8F), onPrimary = Color(0xFF3C0006),
    primaryContainer = Color(0xFF662128), onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFFE7B7BA), onSecondary = Color(0xFF301416),
    secondaryContainer = Color(0xFF432326), onSecondaryContainer = Color(0xFFFFDADB),
    background = Color(0xFF0C0607), onBackground = Color(0xFFFFF2F2),
    surface = Color(0xFF160B0D), onSurface = Color(0xFFFFF2F2),
    surfaceVariant = Color(0xFF241214), onSurfaceVariant = Color(0xFFCDB0B2),
    outline = Color(0xFF74454A), outlineVariant = Color(0xFF45292C),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val PurpleColors = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFF0E0A13), onBackground = Color(0xFFE9E1EC),
    surface = Color(0xFF17111E), onSurface = Color(0xFFE9E1EC),
    surfaceVariant = Color(0xFF241C2D), onSurfaceVariant = Color(0xFFCCC2DC),
    outline = Color(0xFF958DA0), outlineVariant = Color(0xFF494151),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

internal fun winlatorColorScheme(theme: WinlatorThemeType): ColorScheme = when (theme) {
    WinlatorThemeType.WHITE -> WhiteColors
    WinlatorThemeType.BLACK -> BlackColors
    WinlatorThemeType.AMOLED -> AmoledColors
    WinlatorThemeType.BLUE -> BlueColors
    WinlatorThemeType.RED -> RedColors
    WinlatorThemeType.PURPLE -> PurpleColors
}

private val WinlatorTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
)

private val WinlatorShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val theme = WinlatorThemeManager.currentTheme()
    val colors = winlatorColorScheme(theme)
    ConfigureComposeHostFocus()
    HideSystemBars(theme)
    ApplyLegacyChrome(colors)
    MaterialTheme(colorScheme = colors, typography = WinlatorTypography, shapes = WinlatorShapes) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            content()
        }
    }
}

@Composable
fun WinZTheme(content: @Composable () -> Unit) = WinlatorTheme(content)

@Composable
private fun ConfigureComposeHostFocus() {
    val owner = LocalView.current
    DisposableEffect(owner) {
        val previousHighlight = owner.defaultFocusHighlightEnabled
        owner.defaultFocusHighlightEnabled = false
        val host = owner.parent as? AbstractComposeView
        val previousHostFocusable = host?.focusable
        val previousHostTouchFocus = host?.isFocusableInTouchMode
        val previousHostHighlight = host?.defaultFocusHighlightEnabled
        val previousDescendantFocus = host?.descendantFocusability
        val previousAccessibility = host?.importantForAccessibility
        host?.apply {
            isFocusableInTouchMode = false
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            defaultFocusHighlightEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        onDispose {
            owner.defaultFocusHighlightEnabled = previousHighlight
            host?.apply {
                isFocusableInTouchMode = previousHostTouchFocus!!
                focusable = previousHostFocusable!!
                descendantFocusability = previousDescendantFocus!!
                defaultFocusHighlightEnabled = previousHostHighlight!!
                importantForAccessibility = previousAccessibility!!
            }
        }
    }
}

@Composable
private fun HideSystemBars(theme: WinlatorThemeType) {
    val activity = LocalContext.current.findActivity()
    val colors = winlatorColorScheme(theme)
    DisposableEffect(activity, theme) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val light = theme == WinlatorThemeType.WHITE
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        onDispose { }
    }
}

@Composable
private fun ApplyLegacyChrome(colors: ColorScheme) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, colors) {
        activity?.let { host ->
            val background = colors.background.toArgb()
            val surface = colors.surface.toArgb()
            val onSurface = colors.onSurface.toArgb()

            host.window.decorView.setBackgroundColor(background)
            host.findViewById<DrawerLayout>(R.id.DrawerLayout)?.let { drawerLayout ->
                drawerLayout.setBackgroundColor(background)
                if (drawerLayout.childCount > 0) {
                    drawerLayout.getChildAt(0)?.setBackgroundColor(background)
                }
            }
            host.findViewById<View>(R.id.FLFragmentContainer)?.setBackgroundColor(background)

            val toolbar = host.findViewById<Toolbar>(R.id.Toolbar)
            toolbar?.setBackgroundColor(surface)
            toolbar?.setTitleTextColor(onSurface)
            toolbar?.navigationIcon = toolbar?.navigationIcon?.mutate()?.apply { setTint(onSurface) }
            toolbar?.menu?.let { menu ->
                for (i in 0 until menu.size()) {
                    menu.getItem(i).icon?.mutate()?.setTint(onSurface)
                }
            }

            val selectedStates = arrayOf(
                intArrayOf(AndroidR.attr.state_checked),
                intArrayOf()
            )
            val navColors = intArrayOf(colors.primary.toArgb(), colors.onSurfaceVariant.toArgb())
            val tint = ColorStateList(selectedStates, navColors)

            host.findViewById<BottomNavigationView>(R.id.BottomNavigation)?.let { bottom ->
                ViewCompat.setBackgroundTintList(bottom, ColorStateList.valueOf(surface))
                bottom.itemIconTintList = tint
                bottom.itemTextColor = tint
                bottom.itemRippleColor = ColorStateList.valueOf(colors.primary.copy(alpha = 0.14f).toArgb())
            }

            host.findViewById<NavigationView>(R.id.NavigationView)?.let { drawer ->
                drawer.setBackgroundColor(surface)
                drawer.itemIconTintList = tint
                drawer.itemTextColor = ColorStateList.valueOf(onSurface)
            }
        }
        onDispose { }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
