package com.phemic.app

import android.view.MotionEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TouchpadScreen(
    phemicService: PhemicService?,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    isLandscape: Boolean,
    onToggleLandscape: () -> Unit
) {
    var pressureSensitivity by remember { mutableStateOf(1.0f) }
    var cursorSpeed by remember { mutableStateOf(1.2f) }
    var minPressureThreshold by remember { mutableStateOf(0.02f) }
    // mode: 0=touchpad(relative), 1=pen tablet(absolute)
    var inputMode by remember { mutableStateOf(1) }

    var currentLivePressure by remember { mutableStateOf(0.0f) }
    var activeTouchPoints by remember { mutableStateOf<List<TouchVisualPoint>>(emptyList()) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var showToolbar by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var isTouching by remember { mutableStateOf(false) }

    LaunchedEffect(isTouching) {
        if (isTouching) {
            showToolbar = false
        } else {
            delay(800)
            if (!isTouching) showToolbar = true
        }
    }

    val modeColor = if (inputMode == 1) Color(0xFF6F3CFF) else Color(0xFF00BFA5)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070F))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceSize = it }
                .pointerInteropFilter { motionEvent ->
                    if (!showSettings) {
                        val action = motionEvent.actionMasked
                        if (action == MotionEvent.ACTION_DOWN) isTouching = true
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) isTouching = false

                        handleTouchInterop(
                            event = motionEvent,
                            phemicService = phemicService,
                            inputMode = inputMode,
                            pressureScale = pressureSensitivity,
                            speedScale = cursorSpeed,
                            minPressure = minPressureThreshold,
                            surfaceWidth = surfaceSize.width.toFloat(),
                            surfaceHeight = surfaceSize.height.toFloat(),
                            onPressureUpdate = { currentLivePressure = it },
                            onVisualUpdate = { points -> activeTouchPoints = points }
                        )
                    }
                    true
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                activeTouchPoints.forEach { point ->
                    val radius = 20f + point.pressure * 50f
                    drawCircle(
                        color = Color(0xFF00FFF0).copy(alpha = 0.15f + point.pressure * 0.4f),
                        radius = radius,
                        center = point.offset
                    )
                    drawCircle(
                        color = modeColor.copy(alpha = 0.8f),
                        radius = 6f,
                        center = point.offset
                    )
                }
            }
        }

        // Pressure bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = currentLivePressure.coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(listOf(modeColor, Color(0xFF00FFF0)))
                    )
            )
        }

        // Floating toolbar
        AnimatedVisibility(
            visible = showToolbar && !isTouching,
            enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1A1A2E).copy(alpha = 0.92f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode toggle: Pen / Touchpad
                Surface(
                    onClick = { inputMode = if (inputMode == 1) 0 else 1 },
                    shape = RoundedCornerShape(14.dp),
                    color = modeColor.copy(alpha = 0.25f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (inputMode == 1) Icons.Default.Draw else Icons.Default.Mouse,
                            contentDescription = null,
                            tint = modeColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (inputMode == 1) "Pen" else "Pad",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Box(modifier = Modifier.height(24.dp).width(1.dp).background(Color.White.copy(alpha = 0.1f)))

                // Stylus buttons (only in pen mode)
                if (inputMode == 1) {
                    IconButton(
                        onClick = {
                            phemicService?.sendTouchpadEvent(
                                mode = 1, action = 4, x = 0f, y = 0f, pressure = 0f,
                                buttons = 0x01, touchCount = 1, scrollDx = 0f, scrollDy = 0f
                            )
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("S1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f))
                    }

                    IconButton(
                        onClick = {
                            phemicService?.sendTouchpadEvent(
                                mode = 1, action = 4, x = 0f, y = 0f, pressure = 0f,
                                buttons = 0x02, touchCount = 1, scrollDx = 0f, scrollDy = 0f
                            )
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("S2", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f))
                    }

                    Box(modifier = Modifier.height(24.dp).width(1.dp).background(Color.White.copy(alpha = 0.1f)))
                }

                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onToggleLandscape, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotation,
                        contentDescription = "Rotate",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Settings",
                        tint = if (showSettings) Color(0xFF00FFF0) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Settings panel
        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp)
                .padding(bottom = 72.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A2E).copy(alpha = 0.95f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pressure", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    Text("${(pressureSensitivity * 100).roundToInt()}%", fontSize = 12.sp, color = Color(0xFF00FFF0), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = pressureSensitivity,
                    onValueChange = { pressureSensitivity = it },
                    valueRange = 0.1f..3.0f,
                    modifier = Modifier.height(20.dp),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00FFF0), activeTrackColor = modeColor)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speed", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    Text("${(cursorSpeed * 10).roundToInt() / 10.0}x", fontSize = 12.sp, color = Color(0xFF00FFF0), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = cursorSpeed,
                    onValueChange = { cursorSpeed = it },
                    valueRange = 0.5f..3.0f,
                    modifier = Modifier.height(20.dp),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00FFF0), activeTrackColor = modeColor)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Min Pressure", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    Text("${(minPressureThreshold * 100).roundToInt()}%", fontSize = 12.sp, color = Color(0xFF00FFF0), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = minPressureThreshold,
                    onValueChange = { minPressureThreshold = it },
                    valueRange = 0.0f..0.2f,
                    modifier = Modifier.height(20.dp),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00FFF0), activeTrackColor = modeColor)
                )
            }
        }
    }
}

data class TouchVisualPoint(val offset: Offset, val pressure: Float)

private var lastX = 0f
private var lastY = 0f
private var startX = 0f
private var startY = 0f
private var touchStartTime = 0L
private var maxMovedDist = 0f
private var maxPointerCount = 1
private var prevScrollX = 0f
private var prevScrollY = 0f

private fun handleTouchInterop(
    event: MotionEvent,
    phemicService: PhemicService?,
    inputMode: Int,
    pressureScale: Float,
    speedScale: Float,
    minPressure: Float,
    surfaceWidth: Float,
    surfaceHeight: Float,
    onPressureUpdate: (Float) -> Unit,
    onVisualUpdate: (List<TouchVisualPoint>) -> Unit
) {
    if (phemicService == null || surfaceWidth <= 0f || surfaceHeight <= 0f) return

    val pointerCount = event.pointerCount.coerceAtMost(5)
    val action = event.actionMasked

    val rawPressure = event.pressure
    val scaledPressure = if (rawPressure < minPressure) 0f else ((rawPressure - minPressure) * pressureScale).coerceIn(0f, 1f)
    onPressureUpdate(scaledPressure)

    val visualPoints = mutableListOf<TouchVisualPoint>()
    val pointers = mutableListOf<TouchPointerData>()
    for (i in 0 until pointerCount) {
        val px = (event.getX(i) / surfaceWidth).coerceIn(0f, 1f)
        val py = (event.getY(i) / surfaceHeight).coerceIn(0f, 1f)
        val pPress = event.getPressure(i)
        visualPoints.add(TouchVisualPoint(Offset(event.getX(i), event.getY(i)), pPress))
        pointers.add(TouchPointerData(event.getPointerId(i), px, py, pPress))
    }

    when (action) {
        MotionEvent.ACTION_DOWN -> {
            startX = event.x
            startY = event.y
            touchStartTime = System.currentTimeMillis()
            maxMovedDist = 0f
            maxPointerCount = 1
            onVisualUpdate(visualPoints)

            if (inputMode == 1) {
                phemicService.sendTouchpadEvent(
                    mode = 1, action = 0,
                    x = (event.x / surfaceWidth).coerceIn(0f, 1f),
                    y = (event.y / surfaceHeight).coerceIn(0f, 1f),
                    pressure = scaledPressure,
                    buttons = 0, touchCount = 1
                )
            } else {
                phemicService.sendMultiTouchpadEvent(
                    mode = 0, action = 0,
                    buttons = 0, touchCount = 1, pointers = pointers
                )
            }
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
            maxPointerCount = maxOf(maxPointerCount, pointerCount)
            onVisualUpdate(visualPoints)

            if (inputMode == 0) {
                phemicService.sendMultiTouchpadEvent(
                    mode = 0, action = 1,
                    buttons = 0, touchCount = pointerCount.toByte(), pointers = pointers
                )
            }
        }
        MotionEvent.ACTION_MOVE -> {
            onVisualUpdate(visualPoints)
            val dist = kotlin.math.hypot(event.x - startX, event.y - startY)
            maxMovedDist = maxOf(maxMovedDist, dist)

            if (inputMode == 1) {
                phemicService.sendTouchpadEvent(
                    mode = 1, action = 1,
                    x = (event.x / surfaceWidth).coerceIn(0f, 1f),
                    y = (event.y / surfaceHeight).coerceIn(0f, 1f),
                    pressure = scaledPressure,
                    buttons = 0, touchCount = 1
                )
            } else {
                phemicService.sendMultiTouchpadEvent(
                    mode = 0, action = 1,
                    buttons = 0, touchCount = pointerCount.toByte(), pointers = pointers
                )
            }
        }
        MotionEvent.ACTION_POINTER_UP -> {
            onVisualUpdate(visualPoints)
            if (inputMode == 0) {
                phemicService.sendMultiTouchpadEvent(
                    mode = 0, action = 1,
                    buttons = 0, touchCount = pointerCount.toByte(), pointers = pointers
                )
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            onVisualUpdate(emptyList())
            onPressureUpdate(0f)

            val duration = System.currentTimeMillis() - touchStartTime

            if (inputMode == 0) {
                if (maxPointerCount >= 2 && maxMovedDist < 30f && duration < 350) {
                    // TWO-FINGER TAP -> RIGHT CLICK
                    phemicService.sendTouchpadEvent(
                        mode = 0, action = 5,
                        x = 0f, y = 0f, pressure = 0f,
                        buttons = 0x02, touchCount = 2
                    )
                } else if (maxPointerCount == 1 && maxMovedDist < 20f && duration < 250) {
                    // SINGLE-FINGER TAP -> LEFT CLICK
                    phemicService.sendTouchpadEvent(
                        mode = 0, action = 5,
                        x = 0f, y = 0f, pressure = 0f,
                        buttons = 0x01, touchCount = 1
                    )
                }
                // Release all touch slots
                phemicService.sendMultiTouchpadEvent(
                    mode = 0, action = 2,
                    buttons = 0, touchCount = 0, pointers = emptyList()
                )
            } else {
                // Pen mode lift
                phemicService.sendTouchpadEvent(
                    mode = 1, action = 2,
                    x = 0f, y = 0f, pressure = 0f,
                    buttons = 0, touchCount = 0
                )
            }
        }
    }
}
