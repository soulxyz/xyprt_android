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

/** App-wide X1 printer manager. Classic SPP remains preferred; BLE is transparent fallback/support. */
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

    /** Low-duty reconnect for the remembered endpoint. Old 1.1.x saves default to SPP first. */
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
                        val preferred = PrinterTransport.fromSaved(saved.transport) ?: PrinterTransport.CLASSIC
                        val order = listOf(preferred) + PrinterTransport.entries.filterNot { it == preferred }
                        val ok = runCatching {
                            connectByTransports(
                                device = device,
                                name = saved.name,
                                transports = order,
                                showConnecting = false,
                                persist = true,
                            )
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
            val preferred = PrinterTransport.fromSaved(saved.transport) ?: PrinterTransport.CLASSIC
            val order = listOf(preferred) + PrinterTransport.entries.filterNot { it == preferred }
            runCatching {
                connectByTransports(
                    device = a.getRemoteDevice(saved.address),
                    name = saved.name,
                    transports = order,
                    showConnecting = true,
                    persist = true,
                )
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel(); connectJob = null
        if (_state.value is PrinterState.Connecting) _state.value = PrinterState.Disconnected
    }

    /** Connect a scan result without asking the user which radio transport the printer uses. */
    suspend fun connect(found: FoundPrinter) {
        reconnectJob?.cancelAndJoin()
        var lastError: Throwable? = null
        val endpoints = found.preferredEndpoints()
        for ((index, endpoint) in endpoints.withIndex()) {
            _state.value = PrinterState.Connecting(index + 1)
            try {
                connectByTransports(
                    device = endpoint.device,
                    name = found.name,
                    transports = listOf(endpoint.transport),
                    showConnecting = false,
                    persist = true,
                )
                return
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t
            }
        }
        showTransientError(lastError?.message ?: context.getString(R.string.err_connect_failed))
        throw lastError ?: IllegalStateException(context.getString(R.string.err_connect_failed))
    }

    /** Kept for internal callers/tests that already know the endpoint type. */
    suspend fun connect(device: BluetoothDevice, name: String, transport: PrinterTransport = PrinterTransport.CLASSIC) {
        reconnectJob?.cancelAndJoin()
        connectByTransports(device, name, listOf(transport), showConnecting = true, persist = true)
    }

    private suspend fun connectByTransports(
        device: BluetoothDevice,
        name: String,
        transports: List<PrinterTransport>,
        showConnecting: Boolean,
        persist: Boolean,
    ) {
        var lastError: Throwable? = null
        for ((index, transport) in transports.distinct().withIndex()) {
            if (showConnecting) _state.value = PrinterState.Connecting(index + 1)
            try {
                disconnectInternal()
                var actualTransport = transport
                var actualDevice = device

                if (transport == PrinterTransport.BLE) {
                    // Pair only on the BLE fallback path. Existing SPP users are not disturbed.
                    val wasBonded = device.bondState == BluetoothDevice.BOND_BONDED
                    val bonded = if (wasBonded) true else BluetoothBonding.ensureBonded(context, device, log = ::bleLog)
                    if (!wasBonded) {
                        bleLog(if (bonded) "BLE paired" else "BLE pairing unavailable; continuing with GATT")
                    }
                    if (!wasBonded && bonded) {
                        bleLog("Checking whether BLE pairing exposed Classic/SPP…")
                        val classic = PrinterScanner(context).findClassicEndpoint(name)
                        if (classic != null) {
                            actualTransport = PrinterTransport.CLASSIC
                            actualDevice = classic.device
                            bleLog("Classic/SPP appeared after pairing; preferring SPP")
                        }
                    }
                }

                val conn = when (actualTransport) {
                    PrinterTransport.CLASSIC -> SppPrinterConnection.open(context, actualDevice, ::bleLog)
                    PrinterTransport.BLE -> BlePrinterConnection.open(context, actualDevice, ::bleLog)
                }
                connection = conn
                val sc = StatusClient(conn)
                check(sc.initialize()) { context.getString(R.string.err_printer_no_response) }
                statusClient = sc
                if (persist) settings.savePrinter(actualDevice.address, normalizePrinterName(name), actualTransport.savedValue)
                _state.value = PrinterState.Ready(normalizePrinterName(name), actualDevice.address, null, actualTransport)
                startBatteryPolling()
                fetchStatusOnce()
                bleLog("X1 ready via ${actualTransport.savedValue}: ${normalizePrinterName(name)} / ${actualDevice.address}")
                return
            } catch (c: CancellationException) {
                disconnectInternal()
                if (showConnecting) _state.value = PrinterState.Disconnected
                throw c
            } catch (t: Throwable) {
                lastError = t
                bleLog("${transport.savedValue} connect failed: ${t.message}")
                disconnectInternal()
                if (index < transports.lastIndex) delay(450)
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

    suspend fun print(
        image: MonoImage,
        media: MediaType,
        copies: Int,
        feedBeforeDots: Int = io.github.soulxyz.xyprt.printer.Protocol.PRE_FEED_DOTS,
        feedAfterDots: Int = io.github.soulxyz.xyprt.printer.Protocol.CONTINUOUS_FEED_DOTS,
    ) = printJobs(List(copies) { image }, media, feedBeforeDots, feedAfterDots)

    suspend fun printJobs(
        images: List<MonoImage>,
        media: MediaType,
        feedBeforeDots: Int = io.github.soulxyz.xyprt.printer.Protocol.PRE_FEED_DOTS,
        feedAfterDots: Int = io.github.soulxyz.xyprt.printer.Protocol.CONTINUOUS_FEED_DOTS,
    ) {
        val job = scope.async {
            val ready = _state.value as? PrinterState.Ready ?: error(context.getString(R.string.err_not_connected))
            val conn = connection ?: error(context.getString(R.string.err_not_connected))
            try {
                val payloads = images.map {
                    PrintJobBuilder.buildJob(it, media, feedBeforeDots = feedBeforeDots, feedAfterDots = feedAfterDots)
                }
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
                // A rejected write does not necessarily mean the radio link died. Keep a live
                // connection ready so a BLE status-13 retry does not turn into a forced reconnect.
                if (conn.isConnected) {
                    runCatching { ioExclusive.withLock { conn.write(io.github.soulxyz.xyprt.printer.Protocol.PRINT_END) } }
                    _state.value = ready.copy(batteryPercent = lastBattery ?: ready.batteryPercent)
                } else {
                    disconnectInternal()
                    showTransientError(t.message ?: context.getString(R.string.err_print_failed))
                }
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
