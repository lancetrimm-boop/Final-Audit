package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutoScrollSpeed(val label: String, val pixelsPerSecond: Int) {
    SLOW("Slow", 50),
    MEDIUM("Medium", 120),
    FAST("Fast", 270)
}

class LibraryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("aura_library_prefs", Context.MODE_PRIVATE)

    private val _gridDensity = MutableStateFlow(prefs.getFloat(KEY_GRID_DENSITY, 160f))
    val gridDensity: StateFlow<Float> = _gridDensity.asStateFlow()

    private val _autoScrollSpeed = MutableStateFlow(
        AutoScrollSpeed.entries.getOrNull(prefs.getInt(KEY_AUTOSCROLL_SPEED, 1)) ?: AutoScrollSpeed.MEDIUM
    )
    val autoScrollSpeed: StateFlow<AutoScrollSpeed> = _autoScrollSpeed.asStateFlow()

    fun setGridDensity(density: Float) {
        val clamped = density.coerceIn(80f, 360f)
        if (_gridDensity.value != clamped) {
            _gridDensity.value = clamped
            prefs.edit().putFloat(KEY_GRID_DENSITY, clamped).apply()
        }
    }

    fun setAutoScrollSpeed(speed: AutoScrollSpeed) {
        if (_autoScrollSpeed.value != speed) {
            _autoScrollSpeed.value = speed
            prefs.edit().putInt(KEY_AUTOSCROLL_SPEED, speed.ordinal).apply()
        }
    }

    companion object {
        private const val KEY_GRID_DENSITY = "library_grid_density"
        private const val KEY_AUTOSCROLL_SPEED = "library_autoscroll_speed"
    }
}
