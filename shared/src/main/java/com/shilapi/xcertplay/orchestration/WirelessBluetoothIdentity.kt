package com.shilapi.xcertplay.orchestration

import android.content.Context
import java.util.Locale

/** Manual controller identity for firmware that hides the real address from ordinary apps. */
object WirelessBluetoothIdentity {
    fun normalize(value: String): String? {
        val address = value.trim().uppercase(Locale.ROOT)
        if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(address)) return null
        if (address in setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) return null
        if ((address.substring(0, 2).toInt(16) and 1) != 0) return null
        return address
    }
    fun loadOverride(context: Context): String? = context.getSharedPreferences("wireless_compatibility", 0)
        .getString("bluetooth_address", null)?.let(::normalize)
    fun save(context: Context, value: String) {
        val normalized = if (value.isBlank()) null else requireNotNull(normalize(value))
        context.getSharedPreferences("wireless_compatibility", 0).edit()
            .putString("bluetooth_address", normalized).apply()
    }
}
