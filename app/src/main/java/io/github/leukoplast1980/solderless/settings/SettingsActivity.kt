/*
 * Solderless - the Settings screen. Changes are stored at once; the
 * main screen applies them when it comes back to the front.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.settings

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.leukoplast1980.solderless.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val sizeValue = findViewById<TextView>(R.id.textSizeValue)
        val sample = findViewById<TextView>(R.id.textSizeSample)
        val slider = findViewById<Slider>(R.id.textSize)
        slider.valueFrom = Settings.MIN_TEXT_SIZE.toFloat()
        slider.valueTo = Settings.MAX_TEXT_SIZE.toFloat()
        slider.stepSize = 1f
        slider.value = settings.textSizeSp.toFloat()
        fun showSize(sp: Int) {
            sizeValue.text = getString(R.string.text_size_value, sp)
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }
        showSize(settings.textSizeSp)
        slider.addOnChangeListener { _, value, _ ->
            settings.textSizeSp = value.toInt()
            showSize(value.toInt())
        }

        findViewById<MaterialSwitch>(R.id.lineWrap).apply {
            isChecked = settings.lineWrap
            setOnCheckedChangeListener { _, on -> settings.lineWrap = on }
        }
        findViewById<MaterialSwitch>(R.id.keepScreenOn).apply {
            isChecked = settings.keepScreenOn
            setOnCheckedChangeListener { _, on ->
                settings.keepScreenOn = on
                applyKeepScreenOn(on)
            }
        }
        applyKeepScreenOn(settings.keepScreenOn)
        // The labels under each switch toggle it too.
        findViewById<View>(R.id.lineWrapHint).setOnClickListener { findViewById<MaterialSwitch>(R.id.lineWrap).toggle() }
        findViewById<View>(R.id.keepScreenOnHint).setOnClickListener { findViewById<MaterialSwitch>(R.id.keepScreenOn).toggle() }
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
