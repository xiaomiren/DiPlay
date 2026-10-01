package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        com.shilapi.xcertplay.orchestration.WirelessBluetoothIdentity.loadOverride(context)?.let { return it }
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstNotNullOfOrNull {
            com.shilapi.xcertplay.orchestration.WirelessBluetoothIdentity.normalize(it)
        }
    }
}
