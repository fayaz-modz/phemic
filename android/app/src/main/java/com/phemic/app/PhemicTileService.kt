package com.phemic.app

import android.app.Dialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner



class PhemicTileService : TileService() {

    private val statusListener = {
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        PhemicService.addListener(statusListener)
        updateTileState()
    }

    override fun onStopListening() {
        PhemicService.removeListener(statusListener)
        super.onStopListening()
    }

    override fun onClick() {
        val service = PhemicService.instance

        if (service != null && service.isRunning()) {
            val intent = Intent(this, PhemicService::class.java).apply {
                action = PhemicService.ACTION_STOP
            }
            startService(intent)
            service.stopServiceLogic()
        } else {
            val intent = Intent(this, PhemicService::class.java).apply {
                action = PhemicService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val service = PhemicService.instance

        if (service != null && service.isRunning()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Phemic"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (service.isConnected()) {
                    val prefs = getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE)
                    tile.subtitle = prefs.getString("saved_device_name", "Connected")
                } else {
                    tile.subtitle = "Searching..."
                }
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Phemic"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Off"
            }
        }
        tile.updateTile()
    }

    private fun showPhemicDialog() {
        val intent = Intent(this, PhemicTileActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

@Composable
fun PhemicTileTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme(
            primary = Color(0xFF6F3CFF),
            secondary = Color(0xFF00FFF0),
            background = Color(0xFF0F0F1A),
            surface = Color(0xFF141424),
            surfaceVariant = Color(0xFF1E1E38)
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun PhemicDialogContent() {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    var isRunning by remember { mutableStateOf(PhemicService.instance?.isRunning() == true) }
    var isConnected by remember { mutableStateOf(PhemicService.instance?.isConnected() == true) }
    var serverIP by remember { mutableStateOf(PhemicService.instance?.getServerIP()) }
    var discoveredDevices by remember { mutableStateOf(PhemicService.instance?.getDiscoveredDevices() ?: emptyList()) }
    
    val prefs = remember { context.getSharedPreferences("phemic_prefs", Context.MODE_PRIVATE) }
    var micSharingPref by remember { mutableStateOf(prefs.getBoolean("mic_sharing", false)) }
    var speakerListeningPref by remember { mutableStateOf(prefs.getBoolean("speaker_listening", false)) }
    var savedDeviceName by remember { mutableStateOf(prefs.getString("saved_device_name", null)) }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val listener: () -> Unit = {
            handler.post {
                val service = PhemicService.instance
                isRunning = service?.isRunning() == true
                isConnected = service?.isConnected() == true
                serverIP = service?.getServerIP()
                discoveredDevices = service?.getDiscoveredDevices() ?: emptyList()
                
                micSharingPref = prefs.getBoolean("mic_sharing", false)
                speakerListeningPref = prefs.getBoolean("speaker_listening", false)
                savedDeviceName = prefs.getString("saved_device_name", null)
            }
        }
        PhemicService.addListener(listener)
        onDispose {
            PhemicService.removeListener(listener)
        }
    }
    
    PhemicTileTheme {
        Box(
            modifier = Modifier
                .width(310.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phemic",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Switch(
                        checked = isRunning,
                        onCheckedChange = { checked ->
                            if (checked) {
                                val intent = Intent(context, PhemicService::class.java).apply {
                                    action = PhemicService.ACTION_START
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            } else {
                                val intent = Intent(context, PhemicService::class.java).apply {
                                    action = PhemicService.ACTION_STOP
                                }
                                context.startService(intent)
                                PhemicService.instance?.stopServiceLogic()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isRunning) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (discoveredDevices.isEmpty() && !isConnected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            discoveredDevices.forEach { device ->
                                val isThisConnected = isConnected && serverIP == device.ip
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isThisConnected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                        )
                                        .border(
                                            1.dp,
                                            if (isThisConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable {
                                            if (isThisConnected) {
                                                val intent = Intent(context, PhemicService::class.java).apply {
                                                    action = PhemicService.ACTION_STOP
                                                }
                                                context.startService(intent)
                                                PhemicService.instance?.stopServiceLogic()
                                            } else {
                                                prefs.edit().putString("saved_device_name", device.name).apply()
                                                val service = PhemicService.instance
                                                if (service != null && service.isRunning()) {
                                                    service.connectTo(device.ip)
                                                } else {
                                                    val intent = Intent(context, PhemicService::class.java).apply {
                                                        action = PhemicService.ACTION_START
                                                        putExtra("SERVER_IP", device.ip)
                                                    }
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        context.startForegroundService(intent)
                                                    } else {
                                                        context.startService(intent)
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = device.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isThisConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isThisConnected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Connected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Turn on to scan",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (micSharingPref) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                            .border(
                                1.dp,
                                if (micSharingPref) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                val next = !micSharingPref
                                val service = PhemicService.instance
                                if (service != null) {
                                    service.setMicSharing(next)
                                } else {
                                    prefs.edit().putBoolean("mic_sharing", next).apply()
                                    micSharingPref = next
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Mic",
                            tint = if (micSharingPref) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mic",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (micSharingPref) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (speakerListeningPref) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                            .border(
                                1.dp,
                                if (speakerListeningPref) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                val next = !speakerListeningPref
                                val service = PhemicService.instance
                                if (service != null) {
                                    service.setSpeakerListening(next)
                                } else {
                                    prefs.edit().putBoolean("speaker_listening", next).apply()
                                    speakerListeningPref = next
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Speaker",
                            tint = if (speakerListeningPref) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Speaker",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (speakerListeningPref) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}
