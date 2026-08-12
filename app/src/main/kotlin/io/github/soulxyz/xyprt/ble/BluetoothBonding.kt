package io.github.soulxyz.xyprt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort bonding helper used only on the BLE fallback path.
 *
 * Normal SPP users are never forced through this. Some Qring BLE-first modules only expose their
 * Classic identity after Android has paired with the LE identity once, so when BLE is the only
 * available path we let Android run its normal pairing flow inside the app instead of asking the
 * user to leave for system settings.
 */
object BluetoothBonding {
    @SuppressLint("MissingPermission")
    suspend fun ensureBonded(
        context: Context,
        device: BluetoothDevice,
        timeoutMs: Long = 20_000,
        log: (String) -> Unit = {},
    ): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true

        val result = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                if (!changed.address.equals(device.address, ignoreCase = true)) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                log("BLE bond state: $state")
                if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) {
                    if (!result.isCompleted) result.complete(state)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        }

        return try {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> true
                BluetoothDevice.BOND_BONDING -> {
                    log("BLE pairing already in progress …")
                    withTimeoutOrNull(timeoutMs) { result.await() } == BluetoothDevice.BOND_BONDED
                }
                else -> {
                    log("BLE pairing ${device.address} …")
                    if (!device.createBond()) {
                        log("BLE pairing could not start; continuing without bond")
                        false
                    } else {
                        withTimeoutOrNull(timeoutMs) { result.await() } == BluetoothDevice.BOND_BONDED
                    }
                }
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
