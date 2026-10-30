package com.loralink.messenger

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.location.Location
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// ==============================================================================
// 1. DATA MODELS & ENUMS
// ==============================================================================
enum class MessageDirection { TX, RX }
enum class MessageType { TEXT, LOCATION, AUDIO }
enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val direction: MessageDirection,
    val type: MessageType,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val rssi: Int? = null,
    val snr: Float? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val audioBytes: ByteArray? = null,
    val totalChunks: Int = 0
)

data class AudioTransferState(
    val isActive: Boolean = false,
    val mode: String = "idle", // "sending" or "receiving"
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val retries: Int = 0,
    val progressPercent: Int = 0,
    val statusText: String = ""
)

// ==============================================================================
// 2. MAIN ACTIVITY
// ==============================================================================
class MainActivity : ComponentActivity(), SerialInputOutputManager.Listener {

    private val ACTION_USB_PERMISSION = "com.loralink.messenger.USB_PERMISSION"
    private var usbManager: UsbManager? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Reactive State Holders
    private val messages = mutableStateListOf<ChatMessage>()
    private val isUsbConnected = mutableStateOf(false)
    private val activePortName = mutableStateOf<String?>("No Port Connected")
    private val currentFrequencyMhz = mutableFloatStateOf(433.0f)
    private val currentRssi = mutableStateOf<Int?>(null)
    private val audioTransfer = mutableStateOf(AudioTransferState())
    private val isRecordingAudio = mutableStateOf(false)

    // Stop-and-Wait ARQ Coordination
    private var pendingAckSeq: Int? = null
    private var ackDeferred: CompletableDeferred<Boolean>? = null
    private val ackMutex = Mutex()

    // Inbound Chunk Reassembly Buffer
    private val incomingChunks = ConcurrentHashMap<Int, ByteArray>()
    private var incomingTotalChunks = 0

    // Serial line buffer for packet framing
    private val incomingBuffer = StringBuilder()

    // Permission Launchers
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            fetchAndTransmitLocation()
        } else {
            Toast.makeText(this, "Location permission required for GPS sharing", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startAudioRecordingAndTransfer()
        } else {
            Toast.makeText(this, "Microphone permission required for audio memo", Toast.LENGTH_SHORT).show()
        }
    }

    // USB Broadcast Receiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openUsbSerialDevice(it) }
                    } else {
                        Toast.makeText(context, "USB Permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                closeUsbSerialDevice()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Try automatic connection to attached ESP32 on startup
        connectUsbDevice()

        setContent {
            LoRaLinkTheme {
                LoRaMessengerScreen(
                    messages = messages,
                    isUsbConnected = isUsbConnected.value,
                    portName = activePortName.value,
                    frequencyMhz = currentFrequencyMhz.floatValue,
                    currentRssi = currentRssi.value,
                    audioTransferState = audioTransfer.value,
                    isRecording = isRecordingAudio.value,
                    onConnectUsb = { connectUsbDevice() },
                    onDisconnectUsb = { closeUsbSerialDevice() },
                    onSendTextMessage = { text -> sendTextMessage(text) },
                    onShareLocation = { checkLocationPermissionAndShare() },
                    onRecordVoiceMemo = { checkAudioPermissionAndRecord() },
                    onUpdateFrequency = { freq -> setLoRaFrequency(freq) },
                    onClearChat = { messages.clear() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        closeUsbSerialDevice()
    }

    // ==============================================================================
    // 3. USB OTG SERIAL MANAGEMENT
    // ==============================================================================
    private fun connectUsbDevice() {
        val manager = usbManager ?: return
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "No ESP32 USB device found. Connect via USB OTG.", Toast.LENGTH_SHORT).show()
            return
        }

        val driver: UsbSerialDriver = availableDrivers[0]
        val connection: UsbDeviceConnection? = manager.openDevice(driver.device)

        if (connection == null) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            manager.requestPermission(driver.device, permissionIntent)
        } else {
            openUsbSerialDevice(driver.device)
        }
    }

    private fun openUsbSerialDevice(device: UsbDevice) {
        val manager = usbManager ?: return
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = availableDrivers.find { it.device == device } ?: return

        try {
            val connection = manager.openDevice(driver.device) ?: return
            val port = driver.ports[0] // Most ESP32 dev boards use port 0
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            usbSerialPort = port
            usbIoManager = SerialInputOutputManager(port, this).apply {
                Executors.newSingleThreadExecutor().submit(this)
            }

            isUsbConnected.value = true
            activePortName.value = "ESP32 (VID:0x${Integer.toHexString(device.vendorId)})"
            Toast.makeText(this, "ESP32 USB OTG Connected @ 115200 Baud", Toast.LENGTH_SHORT).show()

            // Request gateway status / ping
            writeSerialRaw("CMD:PING\n")
        } catch (e: Exception) {
            Toast.makeText(this, "Serial Error: ${e.message}", Toast.LENGTH_LONG).show()
            closeUsbSerialDevice()
        }
    }

    private fun closeUsbSerialDevice() {
        try {
            usbIoManager?.listener = null
            usbIoManager?.stop()
            usbIoManager = null
            usbSerialPort?.close()
            usbSerialPort = null
        } catch (_: Exception) {}

        isUsbConnected.value = false
        activePortName.value = "Disconnected"
    }

    private fun writeSerialRaw(text: String): Boolean {
        val port = usbSerialPort ?: return false
        val formatted = if (text.endsWith("\n")) text else "$text\n"
        return try {
            port.write(formatted.toByteArray(Charsets.UTF_8), 2000)
            true
        } catch (e: IOException) {
            false
        }
    }

    // ==============================================================================
    // 4. PROTOCOL FRAMING & SERIAL LISTENER (Line-By-Line)
    // ==============================================================================
    override fun onNewData(data: ByteArray?) {
        if (data == null) return
        val chunkStr = String(data, Charsets.UTF_8)
        synchronized(incomingBuffer) {
            incomingBuffer.append(chunkStr)
            var newlineIndex: Int
            while (incomingBuffer.indexOf('\n').also { newlineIndex = it } != -1) {
                val line = incomingBuffer.substring(0, newlineIndex).trim()
                incomingBuffer.delete(0, newlineIndex + 1)
                if (line.isNotEmpty()) {
                    runOnUiThread { parseIncomingProtocolLine(line) }
                }
            }
        }
    }

    override fun onRunError(e: Exception?) {
        runOnUiThread {
            isUsbConnected.value = false
            activePortName.value = "Serial Error: ${e?.message}"
        }
    }

    private fun parseIncomingProtocolLine(line: String) {
        // 1. Inbound ACK: ACK:<seq>
        if (line.startsWith("ACK:")) {
            val seqStr = line.substring(4).trim()
            val seq = seqStr.toIntOrNull()
            if (seq != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    ackMutex.withLock {
                        if (pendingAckSeq == seq) {
                            ackDeferred?.complete(true)
                        }
                    }
                }
            }
            return
        }

        // 2. Inbound Signal RSSI: RSSI:<val>
        if (line.startsWith("RSSI:")) {
            val rssi = line.substring(5).trim().toIntOrNull()
            if (rssi != null) currentRssi.value = rssi
            return
        }

        // 3. Inbound Audio Chunk: CHK:<curr>/<total>:<base64_data>
        if (line.startsWith("CHK:")) {
            val payload = line.substring(4).trim()
            val colonIdx = payload.indexOf(':')
            if (colonIdx != -1) {
                val header = payload.substring(0, colonIdx).split('/')
                val b64 = payload.substring(colonIdx + 1)
                if (header.size == 2) {
                    val curr = header[0].toIntOrNull()
                    val total = header[1].toIntOrNull()
                    if (curr != null && total != null) {
                        handleInboundAudioChunk(curr, total, b64)
                    }
                }
            }
            return
        }

        // 4. Inbound GPS Location: LOC:<lat>,<lng>
        if (line.startsWith("LOC:")) {
            val coords = line.substring(4).trim().split(',')
            if (coords.size >= 2) {
                val lat = coords[0].toDoubleOrNull()
                val lng = coords[1].toDoubleOrNull()
                if (lat != null && lng != null) {
                    messages.add(
                        ChatMessage(
                            direction = MessageDirection.RX,
                            type = MessageType.LOCATION,
                            content = "LOC:$lat,$lng",
                            lat = lat,
                            lng = lng,
                            rssi = currentRssi.value
                        )
                    )
                }
            }
            return
        }

        // 5. Standard Inbound Text Message: MSG:<text>
        if (line.startsWith("MSG:")) {
            val text = line.substring(4).trim()
            messages.add(
                ChatMessage(
                    direction = MessageDirection.RX,
                    type = MessageType.TEXT,
                    content = text,
                    rssi = currentRssi.value
                )
            )
            return
        }

        // Fallback for raw text
        if (!line.startsWith("TX:") && !line.startsWith("CMD:") && !line.startsWith("STATUS:")) {
            messages.add(
                ChatMessage(
                    direction = MessageDirection.RX,
                    type = MessageType.TEXT,
                    content = line,
                    rssi = currentRssi.value
                )
            )
        }
    }

    // ==============================================================================
    // 5. LOCATION SHARING LOGIC
    // ==============================================================================
    private fun checkLocationPermissionAndShare() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fetchAndTransmitLocation()
        } else {
            requestLocationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun fetchAndTransmitLocation() {
        if (!isUsbConnected.value) {
            Toast.makeText(this, "Connect ESP32 via USB OTG to transmit location", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        val lat = loc.latitude
                        val lng = loc.longitude
                        val locString = String.format(Locale.US, "LOC:%.5f,%.5f", lat, lng)

                        // 1. Add to UI
                        val msg = ChatMessage(
                            direction = MessageDirection.TX,
                            type = MessageType.LOCATION,
                            content = locString,
                            lat = lat,
                            lng = lng,
                            status = MessageStatus.SENDING
                        )
                        messages.add(msg)

                        // 2. Transmit over USB Serial
                        val success = writeSerialRaw(locString)
                        val idx = messages.indexOfFirst { it.id == msg.id }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(status = if (success) MessageStatus.DELIVERED else MessageStatus.FAILED)
                        }
                    } else {
                        Toast.makeText(this, "Could not acquire GPS fix", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "GPS Error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (_: SecurityException) {}
    }

    // ==============================================================================
    // 6. AUDIO COMPRESSION & STOP-AND-WAIT ARQ TRANSFER LOGIC
    // ==============================================================================
    private fun checkAudioPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudioRecordingAndTransfer()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudioRecordingAndTransfer() {
        if (!isUsbConnected.value) {
            Toast.makeText(this, "Connect ESP32 via USB OTG to transmit audio", Toast.LENGTH_SHORT).show()
            return
        }

        isRecordingAudio.value = true
        CoroutineScope(Dispatchers.IO).launch {
            // Record 2 seconds of 8kHz 16-bit Mono PCM for extreme radio compression
            val sampleRate = 8000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(1024)

            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                val outputStream = ByteArrayOutputStream()
                val data = ByteArray(bufferSize)

                record.startRecording()
                val startTime = System.currentTimeMillis()
                // Capture for 2.2 seconds
                while (System.currentTimeMillis() - startTime < 2200) {
                    val read = record.read(data, 0, data.size)
                    if (read > 0) outputStream.write(data, 0, read)
                }
                record.stop()
                record.release()

                val rawAudioBytes = outputStream.toByteArray()
                withContext(Dispatchers.Main) {
                    isRecordingAudio.value = false
                    executeStopAndWaitArqTransfer(encodeVoice(rawAudioBytes))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isRecordingAudio.value = false
                    Toast.makeText(this@MainActivity, "Mic Record Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeStopAndWaitArqTransfer(audioBytes: ByteArray) {
        val chunkSize = 120 // 120 bytes binary -> 160 chars base64 (+ ~12 header chars) fits LoRa 255-byte packet
        val chunks = mutableListOf<String>()

        var offset = 0
        while (offset < audioBytes.size) {
            val length = (audioBytes.size - offset).coerceAtMost(chunkSize)
            val slice = audioBytes.copyOfRange(offset, offset + length)
            val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
            chunks.add(b64)
            offset += length
        }

        val totalChunks = chunks.size
        val chatMessage = ChatMessage(
            direction = MessageDirection.TX,
            type = MessageType.AUDIO,
            content = "Voice Note (${totalChunks} chunks)",
            audioBytes = audioBytes,
            totalChunks = totalChunks,
            status = MessageStatus.SENDING
        )
        messages.add(chatMessage)

        audioTransfer.value = AudioTransferState(
            isActive = true,
            mode = "sending",
            currentChunk = 1,
            totalChunks = totalChunks,
            statusText = "Starting ARQ transfer over LoRa..."
        )

        CoroutineScope(Dispatchers.IO).launch {
            var allSucceeded = true

            for (i in 0 until totalChunks) {
                val chunkSeq = i + 1
                val b64 = chunks[i]
                var ackReceived = false
                var retryCount = 0
                val maxRetries = 4

                while (!ackReceived && retryCount <= maxRetries) {
                    withContext(Dispatchers.Main) {
                        audioTransfer.value = audioTransfer.value.copy(
                            currentChunk = chunkSeq,
                            retries = retryCount,
                            progressPercent = ((chunkSeq - 1) * 100) / totalChunks,
                            statusText = if (retryCount == 0) "Transmitting chunk $chunkSeq/$totalChunks..."
                            else "Timeout! Retrying chunk $chunkSeq (Attempt ${retryCount + 1})..."
                        )
                    }

                    // Prepare ACK listener
                    ackMutex.withLock {
                        pendingAckSeq = chunkSeq
                        ackDeferred = CompletableDeferred()
                    }

                    // Write chunk frame to serial port: CHK:<curr>/<total>:<payload>
                    writeSerialRaw("CHK:$chunkSeq/$totalChunks:$b64\n")

                    // Wait up to 2500ms for incoming ACK:<seq>
                    val success = withTimeoutOrNull(2500L) {
                        ackDeferred?.await()
                    } ?: false

                    if (success) {
                        ackReceived = true
                    } else {
                        retryCount++
                    }
                }

                if (!ackReceived) {
                    allSucceeded = false
                    break
                }
            }

            withContext(Dispatchers.Main) {
                audioTransfer.value = audioTransfer.value.copy(
                    isActive = false,
                    statusText = if (allSucceeded) "All chunks delivered!" else "Transfer failed on timeout."
                )

                val idx = messages.indexOfFirst { it.id == chatMessage.id }
                if (idx != -1) {
                    messages[idx] = messages[idx].copy(
                        status = if (allSucceeded) MessageStatus.DELIVERED else MessageStatus.FAILED
                    )
                }
            }
        }
    }

    private fun handleInboundAudioChunk(curr: Int, total: Int, base64Data: String) {
        // 1. Immediately send ACK confirmation back over serial to ESP32: ACK:<curr>
        writeSerialRaw("ACK:$curr\n")

        if (incomingTotalChunks != total) {
            incomingChunks.clear()
            incomingTotalChunks = total
        }

        try {
            val binary = Base64.decode(base64Data, Base64.NO_WRAP)
            incomingChunks[curr] = binary

            audioTransfer.value = AudioTransferState(
                isActive = true,
                mode = "receiving",
                currentChunk = incomingChunks.size,
                totalChunks = total,
                progressPercent = (incomingChunks.size * 100) / total,
                statusText = "Receiving audio chunk $curr/$total..."
            )

            // When all chunks arrive, stitch into full playable audio message
            if (incomingChunks.size == total) {
                val byteStream = ByteArrayOutputStream()
                for (i in 1..total) {
                    incomingChunks[i]?.let { byteStream.write(it) }
                }
                val fullAudioBytes = byteStream.toByteArray()

                messages.add(
                    ChatMessage(
                        direction = MessageDirection.RX,
                        type = MessageType.AUDIO,
                        content = "Voice Memo (${total} chunks)",
                        audioBytes = fullAudioBytes,
                        totalChunks = total,
                        rssi = currentRssi.value
                    )
                )

                incomingChunks.clear()
                incomingTotalChunks = 0
                audioTransfer.value = audioTransfer.value.copy(isActive = false)
            }
        } catch (_: Exception) {}
    }

    // ==============================================================================
    // 7. RADIO FREQUENCY & TEXT MESSAGES
    // ==============================================================================
    private fun sendTextMessage(text: String) {
        if (!isUsbConnected.value) {
            Toast.makeText(this, "Connect ESP32 via USB OTG to transmit", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = ChatMessage(
            direction = MessageDirection.TX,
            type = MessageType.TEXT,
            content = text,
            status = MessageStatus.SENDING
        )
        messages.add(msg)

        val success = writeSerialRaw("TX:$text\n")
        val idx = messages.indexOfFirst { it.id == msg.id }
        if (idx != -1) {
            messages[idx] = messages[idx].copy(status = if (success) MessageStatus.SENT else MessageStatus.FAILED)
        }
    }

    private fun setLoRaFrequency(freqMhz: Float) {
        if (freqMhz in 410.0f..525.0f) {
            currentFrequencyMhz.floatValue = freqMhz
            val freqHz = (freqMhz * 1_000_000L).toLong()
            writeSerialRaw("CMD:FREQ:$freqHz\n")
            Toast.makeText(this, "Frequency changed to $freqMhz MHz", Toast.LENGTH_SHORT).show()
        }
    }

    fun playAudioBytes(bytes: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pcm = decodeVoice(bytes)
                val sampleRate = 8000
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBuffer.coerceAtLeast(pcm.size),
                    AudioTrack.MODE_STREAM
                )
                track.play()
                track.write(pcm, 0, pcm.size)
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }

    // ---- Voice compression: 8 kHz PCM16 -> 4 kHz 8-bit u-law (4x fewer bytes over LoRa) ----
    private fun encodeVoice(pcm: ByteArray): ByteArray {
        val outLen = pcm.size / 4
        val out = ByteArray(outLen)
        for (i in 0 until outLen) {
            val a = (pcm[4 * i + 1].toInt() shl 8) or (pcm[4 * i].toInt() and 0xFF)
            val b = (pcm[4 * i + 3].toInt() shl 8) or (pcm[4 * i + 2].toInt() and 0xFF)
            out[i] = linearToMulaw((a + b) / 2)
        }
        return out
    }

    private fun decodeVoice(ulaw: ByteArray): ByteArray {
        val out = ByteArray(ulaw.size * 4)
        var prev = 0
        for (i in ulaw.indices) {
            val cur = mulawToLinear(ulaw[i])
            writeSample(out, 4 * i, (prev + cur) / 2)
            writeSample(out, 4 * i + 2, cur)
            prev = cur
        }
        return out
    }

    private fun writeSample(buf: ByteArray, pos: Int, v: Int) {
        buf[pos] = (v and 0xFF).toByte()
        buf[pos + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun linearToMulaw(sample: Int): Byte {
        val bias = 0x84
        val clip = 32635
        var s = sample
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > clip) s = clip
        s += bias
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun mulawToLinear(b: Byte): Int {
        val u = (b.toInt() and 0xFF).inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val t = ((mantissa shl 3) + 0x84) shl exponent
        return if (sign != 0) 0x84 - t else t - 0x84
    }
}

// ==============================================================================
// 8. JETPACK COMPOSE UI (MESSENGER INTERFACE)
// ==============================================================================
@Composable
fun LoRaLinkTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF059669),
        surface = Color(0xFF0B0F17),
        background = Color(0xFF070A0F),
        onPrimary = Color.White,
        onSurface = Color(0xFFF1F5F9)
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoRaMessengerScreen(
    messages: List<ChatMessage>,
    isUsbConnected: Boolean,
    portName: String?,
    frequencyMhz: Float,
    currentRssi: Int?,
    audioTransferState: AudioTransferState,
    isRecording: Boolean,
    onConnectUsb: () -> Unit,
    onDisconnectUsb: () -> Unit,
    onSendTextMessage: (String) -> Unit,
    onShareLocation: () -> Unit,
    onRecordVoiceMemo: () -> Unit,
    onUpdateFrequency: (Float) -> Unit,
    onClearChat: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "LoRaLink",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF059669).copy(alpha = 0.2f),
                                border = borderStroke(Color(0xFF059669).copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.2f MHz", frequencyMhz),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (isUsbConnected) "ESP32 USB OTG Online (115200)" else "USB Disconnected",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isUsbConnected) Color(0xFF34D399) else Color(0xFF94A3B8)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Radio Settings", tint = Color(0xFF34D399))
                    }
                    if (isUsbConnected) {
                        IconButton(onClick = onDisconnectUsb) {
                            Icon(Icons.Rounded.UsbOff, contentDescription = "Disconnect USB", tint = Color(0xFFF43F5E))
                        }
                    } else {
                        IconButton(onClick = onConnectUsb) {
                            Icon(Icons.Rounded.Usb, contentDescription = "Connect USB", tint = Color(0xFF34D399))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF0F172A),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Location Pin Button
                    IconButton(
                        onClick = onShareLocation,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = "Share Location", tint = Color(0xFFF43F5E))
                    }

                    Spacer(Modifier.width(6.dp))

                    // Audio Voice Memo Button
                    IconButton(
                        onClick = onRecordVoiceMemo,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Color(0xFFF43F5E) else Color(0xFF1E293B))
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = "Record Audio",
                            tint = if (isRecording) Color.White else Color(0xFF38BDF8)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    // Text Input Field
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Type a LoRa message...", fontSize = 13.sp, color = Color(0xFF64748B)) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF070A0F),
                            unfocusedContainerColor = Color(0xFF070A0F),
                            focusedBorderColor = Color(0xFF059669),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )

                    Spacer(Modifier.width(6.dp))

                    // Send Button
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendTextMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF059669))
                    ) {
                        Icon(Icons.Rounded.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        },
        containerColor = Color(0xFF070A0F)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubble(msg = msg, onPlayAudio = { bytes -> (context as? MainActivity)?.playAudioBytes(bytes) })
                }
            }

            // ARQ Audio Transfer Progress Overlay
            if (audioTransferState.isActive) {
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(16.dp),
                    border = borderStroke(Color(0xFF059669)),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).fillMaxWidth(0.9f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "LoRa Stop-and-Wait ARQ Transfer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { audioTransferState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF34D399),
                            trackColor = Color(0xFF334155)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "Chunk ${audioTransferState.currentChunk}/${audioTransferState.totalChunks}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                "${audioTransferState.progressPercent}%",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                        }
                        Text(
                            audioTransferState.statusText,
                            fontSize = 11.sp,
                            color = Color(0xFFE2E8F0),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // Hardware Settings Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("LoRa Hardware & Radio Settings", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Frequency: ${String.format(Locale.US, "%.2f", frequencyMhz)} MHz",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF34D399)
                    )
                    Slider(
                        value = frequencyMhz,
                        onValueChange = { onUpdateFrequency(it) },
                        valueRange = 410.0f..525.0f,
                        steps = 115,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF059669),
                            activeTrackColor = Color(0xFF34D399)
                        )
                    )
                    Text(
                        "Ai-Thinker Ra-02 (SX1278) operates between 410 MHz and 525 MHz. Default is 433.00 MHz.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "USB Port: ${portName ?: "None"}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close", color = Color(0xFF34D399))
                }
            },
            containerColor = Color(0xFF0F172A)
        )
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, onPlayAudio: (ByteArray) -> Unit) {
    val isTx = msg.direction == MessageDirection.TX
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isTx) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isTx) 16.dp else 4.dp,
                bottomEnd = if (isTx) 4.dp else 16.dp
            ),
            color = if (isTx) Color(0xFF047857) else Color(0xFF1E293B),
            modifier = Modifier.widthIn(min = 120.dp, max = 290.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                // Remote RSSI info header for RX
                if (!isTx && msg.rssi != null) {
                    Text(
                        text = "Remote Node · RSSI: ${msg.rssi} dBm",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                when (msg.type) {
                    MessageType.TEXT -> {
                        Text(
                            text = msg.content,
                            fontSize = 14.sp,
                            color = Color.White,
                            lineHeight = 18.sp
                        )
                    }
                    MessageType.LOCATION -> {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("LoRa GPS Beacon", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                            }
                            if (msg.lat != null && msg.lng != null) {
                                Text(
                                    text = String.format(Locale.US, "%.4f° N, %.4f° E", msg.lat, msg.lng),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFE2E8F0),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                Button(
                                    onClick = {
                                        val geoUri = Uri.parse("geo:${msg.lat},${msg.lng}?q=${msg.lat},${msg.lng}(LoRa+Beacon)")
                                        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                                        context.startActivity(mapIntent)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(34.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Rounded.Navigation, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("View on Map", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    MessageType.AUDIO -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { msg.audioBytes?.let { onPlayAudio(it) } },
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF0F172A))
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color(0xFF38BDF8))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Voice Memo (LoRa)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("${msg.totalChunks} Chunks (u-law 4kHz)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF94A3B8))
                            }
                        }
                    }
                }

                // Bubble Footer: Time & Status
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeFormat.format(Date(msg.timestamp)),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    if (isTx) {
                        Spacer(Modifier.width(4.dp))
                        when (msg.status) {
                            MessageStatus.SENDING -> Icon(Icons.Rounded.HourglassTop, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(11.dp))
                            MessageStatus.SENT -> Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(11.dp))
                            MessageStatus.DELIVERED -> Icon(Icons.Rounded.DoneAll, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(11.dp))
                            MessageStatus.FAILED -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }
        }
    }
}

fun borderStroke(color: Color) = androidx.compose.foundation.BorderStroke(1.dp, color)
