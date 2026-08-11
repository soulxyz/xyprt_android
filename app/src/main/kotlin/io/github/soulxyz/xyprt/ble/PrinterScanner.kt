package io.github.soulxyz.xyprt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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

data class FoundPrinter(val device: BluetoothDevice, val name: String, val rssi: Int)

/** Classic Bluetooth discovery. Bonded devices are emitted immediately, then inquiry starts. */
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

        // Paired printers remain the fastest and most reliable discovery path for SPP.
        runCatching {
            adapter.bondedDevices.forEach { d ->
                trySend(FoundPrinter(d, d.name ?: d.address, 0))
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device ?: return
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val name = runCatching { device.name }.getOrNull() ?: device.address
                        trySend(FoundPrinter(device, name, rssi))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)

        runCatching { adapter.cancelDiscovery() }
        if (!adapter.startDiscovery()) {
            runCatching { context.unregisterReceiver(receiver) }
            close(IllegalStateException(context.getString(R.string.err_no_scanner)))
            return@callbackFlow
        }
        awaitClose {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    suspend fun findFirstPrinter(timeoutMs: Long = 15_000): FoundPrinter? = withTimeoutOrNull(timeoutMs) {
        scan().first { found -> Protocol.DEVICE_NAME_PREFIXES.any { found.name.startsWith(it, ignoreCase = true) } }
    }
}
