package com.winlator.cmod.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import com.winlator.cmod.ui.theme.winlatorColorScheme

/** Standalone app editors share MainActivity's bar, without adding navigation to guest or dialog windows. */
object AppBottomNavigation {
    @JvmStatic
    fun attach(activity: Activity, selected: Int) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        check(content.childCount == 1) { "Attach navigation after setting the activity content" }
        val screen = content.getChildAt(0)
        content.removeView(screen)
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(screen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = activity.layoutInflater.inflate(R.layout.main_bottom_navigation, layout, false) as WinZBottomNavigationView
        val colors = winlatorColorScheme(WinlatorThemeManager.currentTheme(activity))
        layout.setBackgroundColor(colors.background.toArgb())
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.primary.toArgb(), colors.onSurfaceVariant.toArgb()))
        ViewCompat.setBackgroundTintList(bar, ColorStateList.valueOf(colors.surface.toArgb()))
        bar.foreground?.mutate()?.setTint(colors.outlineVariant.toArgb())
        bar.itemIconTintList = tint
        bar.itemTextColor = tint
        bar.selectedItemId = selected
        bar.setOnItemSelectedListener { item ->
            val destination = when (item.itemId) {
                R.id.bottom_nav_library -> R.id.main_menu_shortcuts
                R.id.bottom_nav_controls -> R.id.main_menu_input_controls
                R.id.bottom_nav_settings -> R.id.main_menu_settings
                else -> return@setOnItemSelectedListener false
            }
            activity.startActivity(Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("selected_menu_item_id", destination))
            activity.finish()
            true
        }
        layout.addView(bar)
        content.addView(layout)
    }
}
