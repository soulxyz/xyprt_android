package io.github.soulxyz.xyprt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.data.SettingsRepository
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.printer.PrintJobBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-wide BY-288 Classic Bluetooth connection / print manager. */
@SuppressLint("MissingPermission")
class PrinterManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PrinterState>(PrinterState.Disconnected)
    val state = _state.asStateFlow()

    private val _printerInfo = MutableStateFlow<PrinterInfo?>(null)
    val printerInfo = _printerInfo.asStateFlow()

    private var connection: PrinterConnection? = null
    private var statusClient: StatusClient? = null
    private var batteryJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var statusJob: Job? = null
    private val ioExclusive = Mutex()
    @Volatile private var lastBattery: Int? = null

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** SPP has no BLE autoConnect; use a low-duty retry loop for the remembered printer. */
    @Synchronized
    fun startBackgroundReconnect() {
        val s = _state.value
        if (s is PrinterState.Ready || s is PrinterState.Connecting || s is PrinterState.Printing) return
        if (!BlePermissions.allGranted(context)) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val saved = settings.savedPrinter.first() ?: return@launch
            var attempt = 0
            while (isActive && _state.value is PrinterState.Disconnected) {
                val a = adapter()
                if (a?.isEnabled == true) {
                    attempt++
                    val device = runCatching { a.getRemoteDevice(saved.address) }.getOrNull()
                    if (device != null) {
                        val ok = runCatching {
                            connectInternal(device, saved.name, retries = 1, showConnecting = false)
                        }.isSuccess
                        if (ok) return@launch
                    }
                }
                delay(if (attempt < 3) 4_000 else 15_000)
            }
        }
    }

    fun connectSavedActive() {
        if (_state.value is PrinterState.Ready) return
        connectJob?.cancel()
        connectJob = scope.launch {
            val saved = settings.savedPrinter.first() ?: return@launch
            val a = adapter()
            if (a == null || !a.isEnabled) {
                showTransientError(context.getString(R.string.err_bt_off)); return@launch
            }
            runCatching { connect(a.getRemoteDevice(saved.address), saved.name) }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel(); connectJob = null
        if (_state.value is PrinterState.Connecting) _state.value = PrinterState.Disconnected
    }

    suspend fun connect(device: BluetoothDevice, name: String) {
        reconnectJob?.cancelAndJoin()
        connectInternal(device, name, retries = 2, showConnecting = true)
    }

    private suspend fun connectInternal(
        device: BluetoothDevice,
        name: String,
        retries: Int,
        showConnecting: Boolean,
    ) {
        var lastError: Throwable? = null
        for (attempt in 1..retries) {
            if (showConnecting) _state.value = PrinterState.Connecting(attempt)
            try {
                disconnectInternal()
                val conn = PrinterConnection.open(context, device, log = ::bleLog)
                connection = conn
                val sc = StatusClient(conn)
                statusClient = if (sc.initialize()) sc else null
                settings.savePrinter(device.address, name)
                _state.value = PrinterState.Ready(name, device.address, null)
                startBatteryPolling()
                fetchStatusOnce()
                bleLog("BY-288 ready: $name / ${device.address}")
                return
            } catch (c: CancellationException) {
                disconnectInternal()
                if (showConnecting) _state.value = PrinterState.Disconnected
                throw c
            } catch (t: Throwable) {
                lastError = t
                disconnectInternal()
                if (attempt < retries) delay(700L * attempt)
            }
        }
        if (showConnecting) showTransientError(lastError?.message ?: context.getString(R.string.err_connect_failed))
        throw lastError ?: IllegalStateException(context.getString(R.string.err_connect_failed))
    }

    fun disconnect() {
        reconnectJob?.cancel()
        disconnectInternal()
        _state.value = PrinterState.Disconnected
    }

    suspend fun forget() { settings.forgetPrinter(); disconnect() }

    suspend fun print(image: MonoImage, media: MediaType, copies: Int) =
        printJobs(List(copies) { image }, media)

    suspend fun printJobs(images: List<MonoImage>, media: MediaType) {
        val job = scope.async {
            val ready = _state.value as? PrinterState.Ready ?: error(context.getString(R.string.err_not_connected))
            val conn = connection ?: error(context.getString(R.string.err_not_connected))
            try {
                val payloads = images.map { PrintJobBuilder.buildJob(it, media) }
                _state.value = PrinterState.Printing(0f, 1, payloads.size)
                ioExclusive.withLock {
                    PrintJobSender.sendAll(conn, payloads) { progress, jobIndex ->
                        _state.value = PrinterState.Printing(progress, jobIndex, payloads.size)
                    }
                }
                _state.value = ready.copy(batteryPercent = lastBattery ?: ready.batteryPercent)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                disconnectInternal()
                showTransientError(t.message ?: context.getString(R.string.err_print_failed))
                throw t
            }
        }
        job.await()
    }

    suspend fun sendCommand(bytes: ByteArray) {
        check(_state.value is PrinterState.Ready) { context.getString(R.string.err_not_connected) }
        val conn = connection ?: error(context.getString(R.string.err_not_connected))
        ioExclusive.withLock { conn.write(bytes) }
    }

    private fun showTransientError(message: String) {
        val error = PrinterState.Error(message)
        _state.value = error
        scope.launch {
            delay(4_000)
            if (_state.compareAndSet(error, PrinterState.Disconnected)) startBackgroundReconnect()
        }
    }

    private fun startBatteryPolling() {
        batteryJob?.cancel()
        batteryJob = scope.launch {
            while (isActive) {
                delay(60_000)
                val st = _state.value
                if (st is PrinterState.Ready) {
                    val battery = runCatching { ioExclusive.withLock { statusClient?.batteryPercent() } }.getOrNull()
                    if (battery != null) {
                        lastBattery = battery
                        _state.compareAndSet(st, st.copy(batteryPercent = battery))
                    }
                }
            }
        }
    }

    private fun fetchStatusOnce() {
        statusJob?.cancel()
        statusJob = scope.launch {
            val sc = statusClient ?: return@launch
            val info = runCatching { ioExclusive.withLock { sc.printerInfo() } }.getOrNull()
            if (statusClient === sc) _printerInfo.value = info
            val battery = runCatching { ioExclusive.withLock { sc.batteryPercent() } }.getOrNull()
            if (battery != null) lastBattery = battery
            val st = _state.value
            if (battery != null && statusClient === sc && st is PrinterState.Ready) {
                _state.compareAndSet(st, st.copy(batteryPercent = battery))
            }
        }
    }

    private fun disconnectInternal() {
        statusJob?.cancel(); statusJob = null
        batteryJob?.cancel(); batteryJob = null
        connection?.close(); connection = null
        statusClient = null
        _printerInfo.value = null
    }
}
