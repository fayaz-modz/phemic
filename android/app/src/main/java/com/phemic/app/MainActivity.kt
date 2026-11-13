package com.phemic.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private var phemicService: PhemicService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PhemicService.PhemicBinder
            phemicService = binder.getService()
            isBound = true
            updateServiceStates()
            phemicService?.onStatusChanged = {
                updateServiceStates()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            phemicService = null
            isBound = false
        }
    }

    private var isConnectedState = mutableStateOf(false)
    private var isMicSharingState = mutableStateOf(false)
    private var isSpeakerListeningState = mutableStateOf(false)
    private var serverIPState = mutableStateOf<String?>(null)
    private var isRunningState = mutableStateOf(false)
    private var discoveredDevicesState = mutableStateOf<List<DiscoveredDevice>>(emptyList())
    private var isFullscreenState = mutableStateOf(false)
    private var isLandscapeState = mutableStateOf(false)
    private var selectedTabState = mutableStateOf(0)

    private fun toggleFullscreen() {
        val next = !isFullscreenState.value
        isFullscreenState.value = next
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (next) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleLandscape() {
        val next = !isLandscapeState.value
        isLandscapeState.value = next
        requestedOrientation = if (next) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        if (!recordGranted) {
            Toast.makeText(this, "Microphone permission is required for mic sharing.", Toast.LENGTH_LONG).show()
        }
    }

    private val statusListener = {
        runOnUiThread {
            updateServiceStates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        startPhemicService(null)
        PhemicService.addListener(statusListener)

        setContent {
            PhemicTheme {
                var selectedTab by remember { selectedTabState }
                val isFullscreen by remember { isFullscreenState }
                val isLandscape by remember { isLandscapeState }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF07070F))
                ) {
                    if (!isFullscreen) {
                        TabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color(0xFF0D0D19),
                            contentColor = Color(0xFF00FFF0),
                            modifier = Modifier
                                .statusBarsPadding()
                                .height(48.dp)
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Audio Share", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Touchpad", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            0 -> PhemicDashboard()
                            1 -> TouchpadScreen(
                                phemicService = phemicService,
                                isFullscreen = isFullscreen,
                                onToggleFullscreen = { toggleFullscreen() },
                                isLandscape = isLandscape,
                                onToggleLandscape = { toggleLandscape() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        PhemicService.removeListener(statusListener)
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun updateServiceStates() {
        val service = phemicService
        if (service != null) {
            isConnectedState.value = service.isConnected()
            isMicSharingState.value = service.isMicSharing()
            isSpeakerListeningState.value = service.isSpeakerListening()
            serverIPState.value = service.getServerIP()
            isRunningState.value = service.isRunning()
            discoveredDevicesState.value = service.getDiscoveredDevices()
        } else {
            val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
            isConnectedState.value = false
            isMicSharingState.value = prefs.getBoolean("mic_sharing", false)
            isSpeakerListeningState.value = prefs.getBoolean("speaker_listening", false)
            serverIPState.value = null
            isRunningState.value = false
            discoveredDevicesState.value = emptyList()
        }
    }

    private fun startPhemicService(ip: String?) {
        if (phemicService?.isRunning() == true) {
            phemicService?.stopServiceLogic()
        }
        val intent = Intent(this, PhemicService::class.java).apply {
            action = PhemicService.ACTION_START
            putExtra("SERVER_IP", ip)
        }
        startForegroundService(intent)
        if (!isBound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopPhemicService() {
        val intent = Intent(this, PhemicService::class.java).apply {
            action = PhemicService.ACTION_STOP
        }
        startService(intent)
        phemicService?.stopServiceLogic()
    }

    @Composable
    fun PhemicDashboard() {
        var ipInput by remember { mutableStateOf("") }
        val isConnected = isConnectedState.value
        val isMicSharing = isMicSharingState.value
        val isSpeakerListening = isSpeakerListeningState.value
        val serverIP = serverIPState.value
        val isRunning = isRunningState.value
        val discoveredDevices = discoveredDevicesState.value

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val glowScale by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseGlow"
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF07070F))
        ) {
            val isTablet = maxWidth >= 600.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(120.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0xFF6F3CFF).copy(alpha = 0.15f),
                        radius = 450f,
                        center = Offset(0f, 0f)
                    )
                    drawCircle(
                        color = Color(0xFF00FFF0).copy(alpha = 0.12f),
                        radius = 450f,
                        center = Offset(size.width, size.height)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isTablet) 32.dp else 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF101020).copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "P H E M I C",
                            fontSize = if (isTablet) 28.sp else 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 4.sp
                        )
                        Text(
                            text = "High Fidelity Wireless Audio Share & Control",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isConnected) Color(0xFF00E676).copy(alpha = 0.15f)
                                else if (isRunning) Color(0xFF00B0FF).copy(alpha = 0.15f)
                                else Color.White.copy(alpha = 0.06f)
                            )
                            .border(
                                1.dp,
                                if (isConnected) Color(0xFF00E676).copy(alpha = 0.4f)
                                else if (isRunning) Color(0xFF00B0FF).copy(alpha = 0.4f)
                                else Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isConnected) "CONNECTED" else if (isRunning) "SEARCHING" else "IDLE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color(0xFF00E676) else if (isRunning) Color(0xFF00B0FF) else Color.White.copy(alpha = 0.6f),
                            letterSpacing = 1.sp
                        )
                    }
                }

                if (isTablet) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            StatusBentoCard(
                                isConnected = isConnected,
                                isRunning = isRunning,
                                serverIP = serverIP,
                                glowScale = glowScale,
                                ipInput = ipInput,
                                onIpInputChange = { ipInput = it },
                                onConnect = { startPhemicService(ipInput) },
                                onDisconnect = { stopPhemicService() }
                            )

                            EngineInfoBentoCard()
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AudioStreamBentoCard(
                                isMicSharing = isMicSharing,
                                isSpeakerListening = isSpeakerListening,
                                onMicChange = { checked ->
                                    if (phemicService != null) {
                                        phemicService?.setMicSharing(checked)
                                    } else {
                                        val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
                                        prefs.edit().putBoolean("mic_sharing", checked).apply()
                                        isMicSharingState.value = checked
                                    }
                                },
                                onSpeakerChange = { checked ->
                                    if (phemicService != null) {
                                        phemicService?.setSpeakerListening(checked)
                                    } else {
                                        val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
                                        prefs.edit().putBoolean("speaker_listening", checked).apply()
                                        isSpeakerListeningState.value = checked
                                    }
                                }
                            )

                            if (!isConnected && discoveredDevices.isNotEmpty()) {
                                DiscoveredServersBentoCard(
                                    discoveredDevices = discoveredDevices,
                                    onConnect = { ip -> startPhemicService(ip) }
                                )
                            }
                        }
                    }
                } else {
                    StatusBentoCard(
                        isConnected = isConnected,
                        isRunning = isRunning,
                        serverIP = serverIP,
                        glowScale = glowScale,
                        ipInput = ipInput,
                        onIpInputChange = { ipInput = it },
                        onConnect = { startPhemicService(ipInput) },
                        onDisconnect = { stopPhemicService() }
                    )

                    AudioStreamBentoCard(
                        isMicSharing = isMicSharing,
                        isSpeakerListening = isSpeakerListening,
                        onMicChange = { checked ->
                            if (phemicService != null) {
                                phemicService?.setMicSharing(checked)
                            } else {
                                val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putBoolean("mic_sharing", checked).apply()
                                isMicSharingState.value = checked
                            }
                        },
                        onSpeakerChange = { checked ->
                            if (phemicService != null) {
                                phemicService?.setSpeakerListening(checked)
                            } else {
                                val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putBoolean("speaker_listening", checked).apply()
                                isSpeakerListeningState.value = checked
                            }
                        }
                    )

                    if (!isConnected && discoveredDevices.isNotEmpty()) {
                        DiscoveredServersBentoCard(
                            discoveredDevices = discoveredDevices,
                            onConnect = { ip -> startPhemicService(ip) }
                        )
                    }

                    EngineInfoBentoCard()
                }
            }
        }
    }

    @Composable
    fun StatusBentoCard(
        isConnected: Boolean,
        isRunning: Boolean,
        serverIP: String?,
        glowScale: Float,
        ipInput: String,
        onIpInputChange: (String) -> Unit,
        onConnect: () -> Unit,
        onDisconnect: () -> Unit
    ) {
        val statusColor = when {
            isConnected -> Color(0xFF00E676)
            isRunning -> Color(0xFF00B0FF)
            else -> Color(0xFFFF1744)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF101020).copy(alpha = 0.7f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp * glowScale)
                        .clip(RoundedCornerShape(70.dp))
                        .background(statusColor.copy(alpha = 0.08f))
                        .border(1.5.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(70.dp))
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            isConnected -> "CONNECTED"
                            isRunning -> "SEARCHING..."
                            else -> "DISCONNECTED"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isConnected) serverIP ?: "Device Local" else "Offline",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            if (!isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = onIpInputChange,
                        placeholder = { Text("Server IP (Optional)", fontSize = 13.sp, color = Color.White.copy(alpha = 0.3f)) },
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00FFF0),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )

                    Button(
                        onClick = onConnect,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6F3CFF)),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text("Connect", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            } else {
                Button(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Disconnect", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    @Composable
    fun AudioStreamBentoCard(
        isMicSharing: Boolean,
        isSpeakerListening: Boolean,
        onMicChange: (Boolean) -> Unit,
        onSpeakerChange: (Boolean) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF101020).copy(alpha = 0.7f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "AUDIO STREAM CONTROLS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FFF0),
                letterSpacing = 1.5.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Share Phone Mic", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Route phone audio to PC mic input", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                }
                Switch(
                    checked = isMicSharing,
                    onCheckedChange = onMicChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FFF0),
                        checkedTrackColor = Color(0xFF6F3CFF).copy(alpha = 0.5f)
                    )
                )
            }

            Divider(color = Color.White.copy(alpha = 0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hear PC Speaker", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Stream PC output to phone speaker", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                }
                Switch(
                    checked = isSpeakerListening,
                    onCheckedChange = onSpeakerChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FFF0),
                        checkedTrackColor = Color(0xFF6F3CFF).copy(alpha = 0.5f)
                    )
                )
            }

            if (isMicSharing || isSpeakerListening) {
                Spacer(modifier = Modifier.height(4.dp))
                WaveformVisualizer(isActive = true)
            }
        }
    }

    @Composable
    fun DiscoveredServersBentoCard(
        discoveredDevices: List<DiscoveredDevice>,
        onConnect: (String) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF101020).copy(alpha = 0.7f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "DISCOVERED SERVERS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FFF0),
                letterSpacing = 1.5.sp
            )

            discoveredDevices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.03f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = device.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = device.ip,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    Button(
                        onClick = { onConnect(device.ip) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6F3CFF)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }

    @Composable
    fun EngineInfoBentoCard() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PROTOCOL", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                Text("Low-Latency UDP", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(Color.White.copy(alpha = 0.1f))
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AUDIO FORMAT", fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                Text("48kHz PCM 16-Bit", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    fun WaveformVisualizer(isActive: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "wave")
        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(vertical = 8.dp)
        ) {
            val width = size.width
            val height = size.height
            val halfHeight = height / 2f
            val path = Path()

            path.moveTo(0f, halfHeight)
            val points = 60
            for (i in 0..points) {
                val x = (i.toFloat() / points) * width
                val angle = (i.toFloat() / points) * 4 * Math.PI.toFloat() + phase
                val y = halfHeight + Math.sin(angle.toDouble()).toFloat() * 18f * if (isActive) 1f else 0.1f
                path.lineTo(x, y)
            }

            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF6F3CFF), Color(0xFF00FFF0), Color(0xFF6F3CFF))
                ),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }

    @Composable
    fun PhemicTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF6F3CFF),
                secondary = Color(0xFF00FFF0),
                background = Color(0xFF07070F),
                surface = Color(0xFF141424)
            ),
            content = content
        )
    }
}
