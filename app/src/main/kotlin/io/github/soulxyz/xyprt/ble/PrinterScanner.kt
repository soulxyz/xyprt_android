package io.github.soulxyz.xyprt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.printer.Protocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One radio endpoint for a printer. Some X1 revisions expose both endpoints, others only BLE. */
data class PrinterEndpoint(
    val device: BluetoothDevice,
    val transport: PrinterTransport,
    val rssi: Int,
)

data class FoundPrinter(
    val name: String,
    val endpoints: List<PrinterEndpoint>,
) {
    val key: String get() = normalizePrinterName(name).lowercase()
    val rssi: Int get() = endpoints.maxOfOrNull { it.rssi } ?: Int.MIN_VALUE
    val transports: Set<PrinterTransport> get() = endpoints.mapTo(linkedSetOf()) { it.transport }
    val primaryAddress: String get() = preferredEndpoints().firstOrNull()?.device?.address.orEmpty()

    /** Preserve the proven SPP path when it is available; BLE is the transparent fallback. */
    fun preferredEndpoints(): List<PrinterEndpoint> = endpoints.sortedWith(
        compareBy<PrinterEndpoint> {
            when {
                it.transport == PrinterTransport.CLASSIC && it.device.bondState == BluetoothDevice.BOND_BONDED -> 0
                it.transport == PrinterTransport.CLASSIC -> 1
                else -> 2
            }
        }.thenByDescending { it.rssi }
    )
}

fun normalizePrinterName(name: String): String = name
    .replace(Regex("(?i)[_-]BLE$"), "")
    .trim()

/**
 * Unified discovery: emit paired/classic devices and BLE advertisements into one list.
 * The UI never asks users to choose a transport. Endpoints with the same Qring name are merged.
 */
@SuppressLint("MissingPermission")
class PrinterScanner(private val context: Context) {
    fun scan(): Flow<FoundPrinter> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
            ?: run { close(IllegalStateException(context.getString(R.string.err_no_bluetooth))); return@callbackFlow }
        if (!adapter.isEnabled) {
            close(IllegalStateException(context.getString(R.string.err_bt_off)))
            return@callbackFlow
        }

        val lock = Any()
        val found = linkedMapOf<String, FoundPrinter>()

        fun emitEndpoint(device: BluetoothDevice, rawName: String?, rssi: Int, transport: PrinterTransport) {
            val name = normalizePrinterName(rawName?.takeIf { it.isNotBlank() } ?: device.address)
            val key = if (name == device.address) "addr:${device.address.lowercase()}" else "name:${name.lowercase()}"
            val next = synchronized(lock) {
                val old = found[key]
                val endpoint = PrinterEndpoint(device, transport, rssi)
                val endpoints = if (old == null) {
                    listOf(endpoint)
                } else {
                    old.endpoints.filterNot {
                        it.transport == transport && it.device.address.equals(device.address, ignoreCase = true)
                    } + endpoint
                }
                FoundPrinter(name, endpoints).also { found[key] = it }
            }
            trySend(next)
        }

        // Existing SPP users see their remembered/paired printer immediately, exactly as before.
        runCatching {
            adapter.bondedDevices.forEach { d ->
                emitEndpoint(d, runCatching { d.name }.getOrNull(), 0, PrinterTransport.CLASSIC)
            }
        }

        val classicReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val device = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                emitEndpoint(device, runCatching { device.name }.getOrNull(), rssi, PrinterTransport.CLASSIC)
            }
        }
        val classicFilter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(classicReceiver, classicFilter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(classicReceiver, classicFilter)

        runCatching { adapter.cancelDiscovery() }
        runCatching { adapter.startDiscovery() }

        val leScanner = adapter.bluetoothLeScanner
        val leCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                emitEndpoint(result.device, name, result.rssi, PrinterTransport.BLE)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                // Classic discovery can still succeed. Do not close the whole scan because BLE failed.
            }
        }
        if (leScanner != null) {
            runCatching {
                leScanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    leCallback,
                )
            }
        }

        awaitClose {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(classicReceiver) }
            runCatching { leScanner?.stopScan(leCallback) }
        }
    }

    suspend fun findFirstPrinter(timeoutMs: Long = 15_000): FoundPrinter? = withTimeoutOrNull(timeoutMs) {
        scan().first { found -> Protocol.DEVICE_NAME_PREFIXES.any { found.name.startsWith(it, ignoreCase = true) } }
    }
}
