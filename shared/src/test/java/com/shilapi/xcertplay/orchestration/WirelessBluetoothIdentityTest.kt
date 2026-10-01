package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessBluetoothIdentityTest {
    @Test fun rejectsPlaceholdersAndNonControllerAddresses() {
        listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF",
            "01:23:45:67:89:AB", "AA:BB:CC", "AA:BB:CC:DD:EE:FF; id").forEach {
            assertNull(WirelessBluetoothIdentity.normalize(it))
        }
    }

    @Test fun acceptsRealLocallyAdministeredAddressesAndNormalizesCase() {
        assertEquals("02:00:00:00:00:12", WirelessBluetoothIdentity.normalize("02:00:00:00:00:12"))
        assertEquals("AA:BB:CC:DD:EE:FF", WirelessBluetoothIdentity.normalize(" aa:bb:cc:dd:ee:ff "))
    }
}
