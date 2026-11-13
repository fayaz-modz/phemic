package com.phemic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.PowerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredDevice(val ip: String, val name: String)

class PhemicService : Service() {

    companion object {
        const val ACTION_START = "com.phemic.app.START"
        const val ACTION_STOP = "com.phemic.app.STOP"
        const val ACTION_STATUS = "com.phemic.app.STATUS"

        const val CHANNEL_ID = "phemic_audio_channel_v2"
        const val NOTIFICATION_ID = 4848

        const val PORT_SERVER = 9898
        const val PKT_DISCOVER = 0x01.toByte()
        const val PKT_HEARTBEAT = 0x02.toByte()
        const val PKT_AUDIO_MIC = 0x10.toByte()
        const val PKT_AUDIO_SPEAKER = 0x20.toByte()
        const val PKT_KEEP_ALIVE = 0x30.toByte()
        const val PKT_DISCONNECT = 0x40.toByte()
        const val PKT_TOUCHPAD = 0x50.toByte()
        const val PKT_MIC_CONTROL = 0x60.toByte()

        @Volatile
        var instance: PhemicService? = null

        private val listeners = mutableListOf<() -> Unit>()

        fun addListener(listener: () -> Unit) {
            synchronized(listeners) { listeners.add(listener) }
            listener()
        }

        fun removeListener(listener: () -> Unit) {
            synchronized(listeners) { listeners.remove(listener) }
        }

        fun notifyListeners() {
            synchronized(listeners) { listeners.toList().forEach { it() } }
        }
    }

    private val binder = PhemicBinder()
    private val _discoveredDevices = mutableListOf<DiscoveredDevice>()
    fun getDiscoveredDevices(): List<DiscoveredDevice> = synchronized(_discoveredDevices) { _discoveredDevices.toList() }

    private var socket: DatagramSocket? = null
    private var serverIP: String? = null

    private val isRunning = AtomicBoolean(false)
    private val isMicSharing = AtomicBoolean(false)
    private val isSpeakerListening = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    private var micThread: Thread? = null
    private var rxThread: Thread? = null
    private var txHeartbeatThread: Thread? = null
    private var discoveryThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Phemic::StreamingWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Phemic::StreamingWifiLock")
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            android.util.Log.e("Phemic", "Error acquiring locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {}
    }

    var onStatusChanged: (() -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun notifyStateChange() {
        mainHandler.post {
            onStatusChanged?.invoke()
            notifyListeners()
        }
    }

    inner class PhemicBinder : Binder() {
        fun getService(): PhemicService = this@PhemicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra("SERVER_IP")
                startServiceLogic(ip)
            }
            ACTION_STOP -> {
                stopServiceLogic()
            }
        }
        return START_NOT_STICKY
    }

    fun isConnected() = isConnected.get()
    fun isMicSharing() = isMicSharing.get()
    fun isSpeakerListening() = isSpeakerListening.get()
    fun isRunning() = isRunning.get()
    fun getServerIP() = serverIP

    fun startServiceLogic(ip: String?) {
        if (isRunning.getAndSet(true)) {
            connectTo(ip)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Initializing Phemic..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Initializing Phemic..."))
        }
        connectTo(ip)
    }

    fun connectTo(ip: String?) {
        val oldSocket = socket
        val oldServerIP = serverIP

        isConnected.set(false)
        isMicSharing.set(false)
        isSpeakerListening.set(false)

        socket = null
        serverIP = null

        if (oldSocket != null) {
            Thread {
                try {
                    if (oldServerIP != null) {
                        val disconnect = byteArrayOf('P'.toByte(), 'H'.toByte(), 'M'.toByte(), PKT_DISCONNECT)
                        val serverAddr = InetAddress.getByName(oldServerIP)
                        val packet = DatagramPacket(disconnect, disconnect.size, serverAddr, PORT_SERVER)
                        oldSocket.send(packet)
                    }
                } catch (e: Exception) {}
                try {
                    oldSocket.close()
                } catch (e: Exception) {}
            }.start()
        }

        Thread {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    receiveBufferSize = 65536
                    sendBufferSize = 65536
                }

                if (ip.isNullOrEmpty()) {
                    discoverServer()
                } else {
                    serverIP = ip
                    val name = synchronized(_discoveredDevices) {
                        _discoveredDevices.find { it.ip == ip }?.name
                    } ?: "Saved Device"
                    val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("saved_device_name", name).apply()

                    isConnected.set(true)
                    updateNotification("Connected to $serverIP")
                    
                    val micShared = prefs.getBoolean("mic_sharing", false)
                    val speakerListened = prefs.getBoolean("speaker_listening", false)
                    isMicSharing.set(micShared)
                    isSpeakerListening.set(speakerListened)

                    if (micShared) {
                        startMicRecording()
                    }
                    notifyStateChange()
                }

                if (isConnected.get()) {
                    startStreaming()
                }
            } catch (e: Exception) {
                stopServiceLogic()
            }
        }.start()
    }

    private fun discoverServer() {
        updateNotification("Searching for Phemic server...")
        android.util.Log.d("Phemic", "Starting server discovery")

        var multicastLock: WifiManager.MulticastLock? = null
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("PhemicDiscoveryLock")
            multicastLock?.setReferenceCounted(false)
            multicastLock?.acquire()
        } catch (e: Exception) {
            android.util.Log.e("Phemic", "Failed to acquire multicast lock", e)
        }

        val probeSocket = socket ?: return
        val discoverPacket = byteArrayOf('P'.toByte(), 'H'.toByte(), 'M'.toByte(), PKT_DISCOVER)

        val probeThread = Thread {
            while (isRunning.get() && !isConnected.get() && socket == probeSocket) {
                try {
                    val targets = getProbeTargets()
                    android.util.Log.d("Phemic", "Probing ${targets.size} addresses: $targets")
                    for (target in targets) {
                        if (isConnected.get() || socket != probeSocket) break
                        try {
                            val addr = InetAddress.getByName(target)
                            val pkt = DatagramPacket(discoverPacket, discoverPacket.size, addr, PORT_SERVER)
                            probeSocket.send(pkt)
                        } catch (e: Exception) { }
                    }
                    Thread.sleep(1500)
                } catch (e: Exception) {
                    if (!isRunning.get() || socket != probeSocket) break
                }
            }
        }
        probeThread.start()

        val buf = ByteArray(1024)
        val rxPacket = DatagramPacket(buf, buf.size)
        probeSocket.soTimeout = 1000

        val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("saved_device_name", null)

        synchronized(_discoveredDevices) {
            _discoveredDevices.clear()
        }
        notifyStateChange()

        while (isRunning.get() && !isConnected.get() && socket == probeSocket) {
            try {
                probeSocket.receive(rxPacket)
                android.util.Log.d("Phemic", "Received ${rxPacket.length} bytes from ${rxPacket.address?.hostAddress}")
                if (rxPacket.length >= 4 && buf[0] == 'P'.toByte() && buf[1] == 'H'.toByte() && buf[2] == 'M'.toByte() && buf[3] == PKT_HEARTBEAT) {
                    val length = rxPacket.length
                    val hostname = if (length > 4) {
                        String(buf, 4, length - 4, Charsets.UTF_8).trim()
                    } else {
                        "Unknown Device"
                    }
                    val ip = rxPacket.address.hostAddress
                    val device = DiscoveredDevice(ip, hostname)
                    synchronized(_discoveredDevices) {
                        if (_discoveredDevices.none { it.ip == device.ip }) {
                            _discoveredDevices.add(device)
                            notifyStateChange()
                        }
                    }

                    if (!savedName.isNullOrEmpty() && hostname == savedName) {
                        serverIP = ip
                        isConnected.set(true)
                        updateNotification("Connected to $serverIP")
                        
                        val micShared = prefs.getBoolean("mic_sharing", false)
                        val speakerListened = prefs.getBoolean("speaker_listening", false)
                        isMicSharing.set(micShared)
                        isSpeakerListening.set(speakerListened)

                        if (micShared) {
                            startMicRecording()
                        }
                        notifyStateChange()
                        break
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                // timeout
            } catch (e: Exception) {
                if (isRunning.get() && socket == probeSocket) {
                    android.util.Log.e("Phemic", "Receive error", e)
                }
                if (!isRunning.get() || socket != probeSocket) break
            }
        }
        probeSocket.soTimeout = 0

        try {
            if (multicastLock?.isHeld == true) multicastLock.release()
        } catch (e: Exception) {}
    }

    private fun getProbeTargets(): List<String> {
        val targets = mutableListOf<String>()
        targets.add("255.255.255.255")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return targets
            for (iface in interfaces.asSequence()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    val ip = addr.address
                    if (ip is java.net.Inet4Address) {
                        val ipBytes = ip.address
                        val prefixLen = addr.networkPrefixLength.toInt()
                        val maskBytes = ByteArray(4)
                        for (i in 0 until 32) {
                            if (i < prefixLen) {
                                maskBytes[i / 8] = (maskBytes[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
                            }
                        }
                        val networkBytes = ByteArray(4)
                        for (i in 0..3) {
                            networkBytes[i] = (ipBytes[i].toInt() and maskBytes[i].toInt()).toByte()
                        }
                        // scan a small range around common DHCP assignments
                        for (host in 1..254) {
                            val candidate = ByteArray(4)
                            candidate[0] = networkBytes[0]
                            candidate[1] = networkBytes[1]
                            candidate[2] = networkBytes[2]
                            candidate[3] = host.toByte()
                            val candidateIP = InetAddress.getByAddress(candidate).hostAddress ?: continue
                            if (candidateIP != ip.hostAddress) {
                                targets.add(candidateIP)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Phemic", "Error getting probe targets", e)
        }
        return targets
    }

    private fun startStreaming() {
        val activeSocket = socket ?: return
        val activeServerIP = serverIP ?: return

        acquireLocks()

        try {
            activeSocket.soTimeout = 0
            activeSocket.receiveBufferSize = 65536
            activeSocket.sendBufferSize = 65536
        } catch (e: Exception) {
            android.util.Log.e("Phemic", "Error setting socket parameters", e)
        }

        txHeartbeatThread = Thread {
            val heartbeat = byteArrayOf('P'.toByte(), 'H'.toByte(), 'M'.toByte(), PKT_KEEP_ALIVE)
            val serverAddr = try {
                InetAddress.getByName(activeServerIP)
            } catch (e: Exception) {
                android.util.Log.e("Phemic", "Failed to resolve server IP: $activeServerIP", e)
                null
            }

            if (serverAddr != null) {
                while (isRunning.get() && isConnected.get() && socket == activeSocket) {
                    try {
                        val packet = DatagramPacket(heartbeat, heartbeat.size, serverAddr, PORT_SERVER)
                        activeSocket.send(packet)
                    } catch (e: Exception) {
                        android.util.Log.w("Phemic", "Heartbeat send error: ${e.message}")
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
        txHeartbeatThread?.start()

        rxThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var audioTrack: AudioTrack? = null
            try {
                val sampleRate = 48000
                val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val bufferSize = (minBufSize * 2).coerceAtLeast(8192)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                audioTrack.play()

                val rxBuf = ByteArray(8192)
                val packet = DatagramPacket(rxBuf, rxBuf.size)

                while (isRunning.get() && isConnected.get() && socket == activeSocket) {
                    try {
                        activeSocket.receive(packet)
                        if (packet.length < 4 || rxBuf[0] != 'P'.toByte() || rxBuf[1] != 'H'.toByte() || rxBuf[2] != 'M'.toByte()) {
                            continue
                        }
                        if (rxBuf[3] == PKT_AUDIO_SPEAKER && isSpeakerListening.get()) {
                            audioTrack.write(rxBuf, 4, packet.length - 4)
                        } else if (rxBuf[3] == PKT_MIC_CONTROL && packet.length >= 5) {
                            when (rxBuf[4]) {
                                0x01.toByte() -> setMicSharing(true)
                                0x00.toByte() -> setMicSharing(false)
                                0x02.toByte() -> setMicSharing(!isMicSharing.get())
                            }
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        // Socket receive timeout is expected if soTimeout is set, continue listening
                        continue
                    } catch (e: Exception) {
                        if (!isRunning.get() || !isConnected.get() || socket != activeSocket) break
                        android.util.Log.w("Phemic", "rxThread error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Phemic", "AudioTrack stream error", e)
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {}
            }
        }
        rxThread?.start()
    }

    fun setMicSharing(enabled: Boolean) {
        if (isMicSharing.get() == enabled && ((enabled && micThread != null) || (!enabled && micThread == null))) {
            return
        }
        isMicSharing.set(enabled)
        val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("mic_sharing", enabled).apply()
        notifyStateChange()
        if (enabled) {
            startMicRecording()
        } else {
            micThread = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification("Phemic Active"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } catch (e: Exception) {}
            }
        }
    }

    fun setSpeakerListening(enabled: Boolean) {
        isSpeakerListening.set(enabled)
        val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("speaker_listening", enabled).apply()
        notifyStateChange()
    }

    private fun startMicRecording() {
        val activeSocket = socket ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Phemic Active (Mic sharing)"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: Exception) {
                android.util.Log.e("Phemic", "Failed to promote FGS to microphone type: ${e.message}")
            }
        }
        micThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val sampleRate = 48000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                recorder.startRecording()

                val sendBuf = ByteArray(1028)
                sendBuf[0] = 'P'.toByte()
                sendBuf[1] = 'H'.toByte()
                sendBuf[2] = 'M'.toByte()
                sendBuf[3] = PKT_AUDIO_MIC

                val rawBuf = ByteArray(1024)

                while (isRunning.get() && isConnected.get() && isMicSharing.get() && socket == activeSocket) {
                    val readBytes = recorder.read(rawBuf, 0, rawBuf.size)
                    if (readBytes > 0) {
                        System.arraycopy(rawBuf, 0, sendBuf, 4, readBytes)
                        val sIP = serverIP ?: break
                        val serverAddr = InetAddress.getByName(sIP)
                        val packet = DatagramPacket(sendBuf, 4 + readBytes, serverAddr, PORT_SERVER)
                        activeSocket.send(packet)
                    }
                }
            } catch (e: Exception) {
                // handle silently
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (e: Exception) {}
            }
        }
        micThread?.start()
    }

    private val touchpadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var cachedServerAddr: InetAddress? = null



    fun sendMultiTouchpadEvent(
        mode: Byte,
        action: Byte,
        buttons: Byte,
        touchCount: Byte,
        pointers: List<TouchPointerData>
    ) {
        val sSocket = socket ?: return
        val sIP = serverIP ?: return
        if (!isConnected.get()) return

        val count = pointers.size.coerceAtMost(5)
        val sendBuf = ByteArray(8 + count * 13)
        sendBuf[0] = 'P'.toByte()
        sendBuf[1] = 'H'.toByte()
        sendBuf[2] = 'M'.toByte()
        sendBuf[3] = PKT_TOUCHPAD
        sendBuf[4] = mode
        sendBuf[5] = action
        sendBuf[6] = buttons
        sendBuf[7] = touchCount

        for (i in 0 until count) {
            val ptr = pointers[i]
            val offset = 8 + i * 13
            sendBuf[offset] = ptr.id.toByte()
            writeFloatLE(sendBuf, offset + 1, ptr.x)
            writeFloatLE(sendBuf, offset + 5, ptr.y)
            writeFloatLE(sendBuf, offset + 9, ptr.pressure)
        }

        touchpadExecutor.execute {
            try {
                var addr = cachedServerAddr
                if (addr == null || addr.hostAddress != sIP) {
                    addr = InetAddress.getByName(sIP)
                    cachedServerAddr = addr
                }
                val packet = DatagramPacket(sendBuf, sendBuf.size, addr, PORT_SERVER)
                sSocket.send(packet)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun sendTouchpadEvent(
        mode: Byte,
        action: Byte,
        x: Float,
        y: Float,
        pressure: Float,
        buttons: Byte,
        touchCount: Byte,
        scrollDx: Float = 0f,
        scrollDy: Float = 0f
    ) {
        val pointers = if (touchCount > 0) listOf(TouchPointerData(0, x, y, pressure)) else emptyList()
        sendMultiTouchpadEvent(mode, action, buttons, touchCount, pointers)
    }

    private fun writeFloatLE(buffer: ByteArray, offset: Int, value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        buffer[offset] = (bits and 0xFF).toByte()
        buffer[offset + 1] = ((bits shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((bits shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((bits shr 24) and 0xFF).toByte()
    }

    fun stopServiceLogic() {
        if (!isRunning.getAndSet(false)) return

        releaseLocks()

        isConnected.set(false)
        isMicSharing.set(false)
        isSpeakerListening.set(false)

        val oldSocket = socket
        val oldServerIP = serverIP

        socket = null
        serverIP = null

        if (oldSocket != null) {
            Thread {
                try {
                    if (oldServerIP != null) {
                        val disconnect = byteArrayOf('P'.toByte(), 'H'.toByte(), 'M'.toByte(), PKT_DISCONNECT)
                        val serverAddr = InetAddress.getByName(oldServerIP)
                        val packet = DatagramPacket(disconnect, disconnect.size, serverAddr, PORT_SERVER)
                        oldSocket.send(packet)
                    }
                } catch (e: Exception) {}
                try {
                    oldSocket.close()
                } catch (e: Exception) {}
            }.start()
        }

        notifyStateChange()
        instance = null
        stopSelf()
    }

    override fun onDestroy() {
        stopServiceLogic()
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Phemic Active Connection",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phemic Audio Sharing")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }
}

data class TouchPointerData(
    val id: Int,
    val x: Float,
    val y: Float,
    val pressure: Float
)
