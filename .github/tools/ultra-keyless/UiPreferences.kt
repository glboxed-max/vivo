package com.sukisu.ultra.backend

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object UiPreferences {
    var showNavigationNames by mutableStateOf(true)
        private set
    var liquidGlass by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        showNavigationNames = preferences.getBoolean("ultra_navigation_names", true)
        liquidGlass = preferences.getBoolean("ultra_liquid_glass", false)
    }

    fun setNavigationNames(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("ultra_navigation_names", enabled).apply()
        showNavigationNames = enabled
    }

    fun setLiquidGlass(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("ultra_liquid_glass", enabled).apply()
        liquidGlass = enabled
    }
}
