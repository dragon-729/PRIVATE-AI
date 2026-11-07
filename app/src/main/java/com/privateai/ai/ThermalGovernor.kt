package com.privateai.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.privateai.settings.Settings
import kotlin.math.max

/** Slows down or refuses AI work when the phone is hot, so a long answer can never cook the device. */
object ThermalGovernor {
    fun advise(ctx: Context, baseThreads: Int): ThermalAdvice {
        if (!Settings.value.thermalProtection) return ThermalAdvice(baseThreads, null, null)
        val status = if (Build.VERSION.SDK_INT >= 29)
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0
        val b: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val t = if (raw == Int.MIN_VALUE) 0.0 else raw / 10.0
        return when {
            status >= 4 || t >= 48.0 ->
                ThermalAdvice(2, null, "Your phone is overheating. Let it cool down for a few minutes, then try again.")
            status >= 3 || t >= 45.0 -> ThermalAdvice(2, 256, null)
            status >= 2 || t >= 42.0 -> ThermalAdvice(max(2, baseThreads / 2), null, null)
            else -> ThermalAdvice(baseThreads, null, null)
        }
    }
}
