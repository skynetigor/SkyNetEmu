package com.winlator.cmod.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class WinZBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle
) : BottomNavigationView(context, attrs, defStyleAttr) {
    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            view.setPadding(0, 0, 0, 0)
            insets
        }
        setPadding(0, 0, 0, 0)
    }
}
