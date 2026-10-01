package com.shilapi.xcertplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A bounded transport probe: never sends iAP2 payloads or changes pairing. */
@SuppressLint("MissingPermission")
class BluetoothDiagnosticActivity : ComponentActivity() {
    private val service = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private lateinit var output: TextView
    private lateinit var start: Button
    private val report = StringBuilder()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var cancelled = false
    private var device: BluetoothDevice? = null
    private var registered = false
    private var busy = false
    private val sdpReceived = java.util.concurrent.atomic.AtomicBoolean(false)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) begin() else record("Required Bluetooth permissions denied")
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val peer = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (peer?.address != device?.address) return
            when (intent.action) {
                BluetoothDevice.ACTION_UUID -> {
                    sdpReceived.set(true)
                    @Suppress("DEPRECATION")
                    val values = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                        ?.filterIsInstance<android.os.ParcelUuid>()?.map { it.uuid }
                    record("SDP result received: count=${values?.size} iAP2Present=${values?.contains(service)}")
                    values?.forEach { record("SDP service category=${category(it)}") }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> record("Selected peer ACL connected")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> record("Selected peer ACL disconnected")
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> record("Selected peer bond state=${peer.bondState}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply {
            text = "蓝牙连接诊断\n先断开 DiPlay 会话，再运行。测试所选手机的服务发现和数据连接，不需要热点。分别测试安全连接与不要求链路认证、加密的连接，只打开通道，不发送数据。每种连接最长等待 45 秒；成功仅代表数据通道可用，不代表 CarPlay 已成功。报告自动保存，并包含在设置中的诊断报告里。"
        })
        start = Button(this).apply { text = "开始诊断（约 2 分钟）"; setOnClickListener { requestProbe() } }
        layout.addView(start)
        layout.addView(Button(this).apply { text = "停止诊断"; setOnClickListener { cancel() } })
        output = TextView(this).apply { setTextIsSelectable(true) }
        layout.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        val file = File(filesDir, "logs/bluetooth-diagnostic.log")
        if (file.isFile) output.text = file.readText()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    private fun requestProbe() {
        if (busy) return
        if (CarPlayBackgroundSession.hasSession()) {
            record("请先在 DiPlay 主页面断开当前会话，避免两个连接互相干扰。")
            return
        }
        if (Build.VERSION.SDK_INT >= 31) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        else begin()
    }

    private fun begin() {
        if (busy) return
        synchronized(report) { report.clear() }
        cancelled = false
        sdpReceived.set(false)
        busy = true
        start.isEnabled = false
        worker.execute {
            try {
                record("Bluetooth transport diagnostic v1; Android=${Build.VERSION.RELEASE}; model=${Build.MODEL}")
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: error("Android Bluetooth adapter absent")
                record("Adapter enabled=${adapter.isEnabled}; state=${adapter.state}; discovering=${adapter.isDiscovering}")
                check(adapter.isEnabled) { "Enable Bluetooth first" }
                val address = DiPlayPreferences.phoneAddress(this)
                device = adapter.bondedDevices.firstOrNull { it.address.equals(address, true) }
                val peer = device ?: error("Selected phone is not in Android bonded list; select it in connection setup")
                record("Selected peer bond=${peer.bondState}; type=${peer.type}; class=${peer.bluetoothClass?.deviceClass}; bondedCount=${adapter.bondedDevices.size}")
                val cached = peer.uuids?.map { it.uuid }
                record("Cached SDP count=${cached?.size}; iAP2Present=${cached?.contains(service)} (cache is not proof of current availability)")
                record("Discovery cancellation=${adapter.cancelDiscovery()}")
                record("SDP request accepted=${peer.fetchUuidsWithSdp()}")
                // Allow the asynchronous SDP broadcast to arrive before socket probing.
                repeat(12) { if (cancelled) return@execute; Thread.sleep(1000) }
                record("SDP response within 12 seconds=${sdpReceived.get()}")
                for (secure in listOf(true, false)) {
                    if (cancelled) break
                    val mode = if (secure) "secure" else "insecure"
                    record("RFCOMM $mode starting; timeout=45000ms; insecure probe does not request link authentication/encryption")
                    val current = if (secure) peer.createRfcommSocketToServiceRecord(service)
                        else peer.createInsecureRfcommSocketToServiceRecord(service)
                    socket = current
                    if (cancelled) { current.close(); break }
                    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
                    val deadline = timer.schedule({ timedOut.set(true); runCatching { current.close() } }, 45, TimeUnit.SECONDS)
                    val began = android.os.SystemClock.elapsedRealtime()
                    try {
                        current.connect()
                        record("RFCOMM $mode connected; elapsedMs=${android.os.SystemClock.elapsedRealtime() - began}; no protocol data sent")
                    } catch (error: Exception) {
                        record("RFCOMM $mode failed; elapsedMs=${android.os.SystemClock.elapsedRealtime() - began}; timedOut=${timedOut.get()}; cancelled=$cancelled; ${error.javaClass.simpleName}: ${error.message}")
                    } finally {
                        deadline.cancel(false)
                        runCatching { current.close() }
                        socket = null
                    }
                }
                record("Diagnostic complete; missing SDP response is inconclusive; socket success does not verify iAP2 authentication")
            } catch (error: Exception) {
                record("Diagnostic error ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runOnUiThread { busy = false; start.isEnabled = true }
            }
        }
    }

    private fun category(uuid: UUID): String = when (uuid) {
        service -> "iAP2"
        UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb") -> "A2DP source"
        UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb") -> "A2DP sink"
        UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb") -> "Hands-free"
        else -> "other"
    }

    private fun record(message: String) {
        val safe = DiagnosticRedactor.redact(message.replace('\n', ' ').replace('\r', ' ')) ?: "Diagnostic detail omitted"
        synchronized(report) {
            report.append(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())).append("  ").append(safe).append('\n')
            val text = report.toString()
            val file = File(filesDir, "logs/bluetooth-diagnostic.log")
            file.parentFile?.mkdirs()
            file.writeText(text)
            runOnUiThread { output.text = text }
        }
    }

    private fun cancel() {
        cancelled = true
        runCatching { socket?.close() }
        record("Diagnostic cancellation requested")
    }

    override fun onDestroy() {
        cancelled = true
        runCatching { socket?.close() }
        worker.shutdownNow()
        timer.shutdownNow()
        if (registered) unregisterReceiver(receiver)
        super.onDestroy()
    }
}
