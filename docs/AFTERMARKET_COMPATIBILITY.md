# RK3326 / Android 11 aftermarket receiver investigation

Target supplied by user: auto_rk_t21, 2 GB RAM, Android 11, MCU
AP90 / CM06.21.03.1M_240518, GEELY-RZ CAN box, RK3326_SP204 configuration.
These identifiers do not specify the Bluetooth controller, its Android exposure,
the firmware RFCOMM implementation, or the Wi-Fi AP channel capabilities.
No successful connection on this hardware has yet been verified.

## Changes

- Skip BYD navigation service initialization where its receiver packages are unavailable.
- Cancel Android Bluetooth discovery before RFCOMM connection, best effort where permissions permit.
- Expose an optional actual Android Bluetooth MAC override in Settings → Connection setup →
  Wireless compatibility. It is shared by AirPlay configuration and iAP2 wireless identification.
  Empty means automatic detection. Saving applies on the next disconnected/new connection.
- Log the controller-address source, then distinguish link negotiation, accessory identification,
  and MFi authentication start/failure. The control message sequence and authentication identity
  are not changed for a particular chipset.
- Include Android adapter availability, enabled state, Android bond-list visibility of the selected
  iPhone and BYD receiver availability in exported diagnostics. No additional Bluetooth MAC is
  written into the report header.
- Keep connection logs when the settings menu is open, so handshake failures remain exportable.

Do not enter the phone MAC, Wi-Fi MAC or an invented address. If the MCU owns a separate
Bluetooth module and Android does not expose the paired iPhone through BluetoothAdapter,
changing an address cannot give the app RFCOMM access. Vendor integration or a different
supported transport is needed in that situation.

## In-car validation

1. Use built-in car hotspot with its exact saved credentials, or test Wi-Fi Direct separately.
2. Select the iPhone within DiPlay. Make one connection attempt and export diagnostics.
3. If Android has no adapter or cannot see the selected iPhone in its bonded devices,
   investigate Android/MCU Bluetooth exposure before changing iAP2.
4. RFCOMM failure occurs before iAP2. A link-negotiation failure is distinct from accessory
   identification rejection and MFi authentication failure. Passing authentication but failing
   Wi-Fi handoff calls for network/channel/Bonjour investigation.
5. Only set the MAC override when the real Android controller address is independently confirmed.
6. For a successful session on this 2 GB unit, begin with H.264 / 30 fps and 60–80% resolution.
   These reduce rendering load; they do not repair a handshake failure.

Build/test invocation: `:shared:testDebugUnitTest :common:compileDebugKotlin :mobile:processDebugResources`.
On this host, JDK 25 NIO PipeImpl loopback initialization initially failed
(`UnixDomainSockets.connect`, Invalid argument). Setting the command-local JVM option
`-Djdk.net.unixdomain.tmpdir=D:/project/DiPlay/.build-tmp` lets Gradle start using a short
workspace path for temporary sockets. A standalone APK still requires the external authentication
assets documented in BUILD.md; an identity-free debug APK is not a valid iPhone connection test.
The subsequent verification ignored repositories injected by the host's global init script,
then stopped at project configuration because no Android SDK was configured. Compilation and
unit tests therefore remain unverified; XML/resource-reference checks passed.
