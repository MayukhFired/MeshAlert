package com.meshalert.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshalert.app.location.LocationHelper
import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import com.meshalert.app.viewmodel.MainViewModel
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    targetMessage: MeshMessage,
    onBack: () -> Unit,
    vm: MainViewModel
) {
    val userLocation by vm.currentLocation.collectAsState()
    val heading by vm.deviceHeading.collectAsState()
    val allMessages by vm.messages.collectAsState()

    // Filter messages with valid coordinates
    val targetsWithCoordinates = remember(allMessages) {
        allMessages.filter { it.latitude != 0.0 || it.longitude != 0.0 }
    }

    var currentTarget by remember(targetMessage) { mutableStateOf(targetMessage) }

    // Distance and true bearing calculation
    val (distanceMeters, trueBearing) = remember(userLocation, currentTarget) {
        val loc = userLocation
        if (loc != null && (currentTarget.latitude != 0.0 || currentTarget.longitude != 0.0)) {
            LocationHelper.calculateDistanceAndBearing(
                loc.latitude, loc.longitude,
                currentTarget.latitude, currentTarget.longitude
            )
        } else {
            Pair(null, null)
        }
    }

    // Relative angle: arrow angle relative to phone's top
    val relativeAngle = remember(trueBearing, heading) {
        if (trueBearing != null) {
            (trueBearing - heading + 360f) % 360f
        } else {
            0f
        }
    }

    // Is device pointing within +/- 15 degrees of target
    val isOnTarget = remember(relativeAngle) {
        relativeAngle in 345f..360f || relativeAngle in 0f..15f
    }

    // Radar pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Sweep"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SURVIVOR RADAR", fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text(
                            text = if (isOnTarget) "🟢 DIRECTLY ON TARGET" else "NAVIGATING TO BEACON",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOnTarget) Color(0xFF00E676) else Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF101418),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0A0D10)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(Modifier.height(8.dp))

            // Target Info Card
            TargetHeaderBadge(target = currentTarget)

            Spacer(Modifier.height(16.dp))

            // Radar Circle HUD
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .border(1.dp, Color(0xFF1E2832), CircleShape)
                    .background(Color(0xFF0D1217), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                RadarCanvas(
                    heading = heading,
                    relativeAngle = relativeAngle,
                    sweepAngle = sweepAngle,
                    isOnTarget = isOnTarget,
                    hasTargetFix = distanceMeters != null
                )

                // Center Bullseye / Status
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(
                            if (isOnTarget) Color(0xFF00E676).copy(alpha = 0.15f)
                            else Color(0xFFD32F2F).copy(alpha = 0.12f),
                            CircleShape
                        )
                        .border(
                            1.dp,
                            if (isOnTarget) Color(0xFF00E676) else Color(0xFFD32F2F),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isOnTarget) "LOCK" else "AIM",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnTarget) Color(0xFF00E676) else Color.White
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Distance & Bearing Telemetry Panel
            TelemetryCard(
                distanceMeters = distanceMeters,
                trueBearing = trueBearing,
                userLocation = userLocation,
                target = currentTarget,
                isOnTarget = isOnTarget
            )

            Spacer(Modifier.weight(1f))

            // Target Switcher (if more than 1 beacon in range)
            if (targetsWithCoordinates.size > 1) {
                Text(
                    "Other Beacons in Mesh:",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    targetsWithCoordinates.take(3).forEach { beacon ->
                        Surface(
                            color = if (beacon.id == currentTarget.id) Color(0xFF2C394B) else Color(0xFF151D24),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clickable { currentTarget = beacon }
                        ) {
                            Text(
                                text = "${beacon.type.name} · ${beacon.senderName}",
                                color = if (beacon.id == currentTarget.id) Color.White else Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TargetHeaderBadge(target: MeshMessage) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D24)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (target.type == AlertType.SOS) Color(0xFFD32F2F) else Color(0xFF0288D1),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            target.type.name,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        target.senderName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Coords: %.4f, %.4f".format(target.latitude, target.longitude),
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${target.hopCount} HOPS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color(0xFF00E676)
                )
                Text(
                    "TTL: ${target.ttl}",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun TelemetryCard(
    distanceMeters: Float?,
    trueBearing: Float?,
    userLocation: android.location.Location?,
    target: MeshMessage,
    isOnTarget: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D24)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("DISTANCE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Text(
                        text = if (distanceMeters != null) LocationHelper.formatDistance(distanceMeters) else "Acquiring...",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isOnTarget) Color(0xFF00E676) else Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("BEARING", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Text(
                        text = if (trueBearing != null) "%03d° %s".format(
                            trueBearing.roundToInt(),
                            LocationHelper.formatBearing(trueBearing)
                        ) else "--",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF222B35))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val accStr = if (userLocation != null) "±${userLocation.accuracy.roundToInt()}m" else "No GPS Fix"
                Text("🛰️ GPS Precision: $accStr", fontSize = 11.sp, color = Color.Gray)
                if (isOnTarget) {
                    Text("🎯 ON COURSE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                }
            }
        }
    }
}

@Composable
private fun RadarCanvas(
    heading: Float,
    relativeAngle: Float,
    sweepAngle: Float,
    isOnTarget: Boolean,
    hasTargetFix: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - 12.dp.toPx()

        // 1. Concentric Range Rings
        listOf(0.33f, 0.66f, 1.0f).forEach { scale ->
            drawCircle(
                color = Color(0xFF1E2832),
                radius = radius * scale,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
        }

        // 2. Crosshairs
        drawLine(
            color = Color(0xFF1E2832),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color(0xFF1E2832),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.dp.toPx()
        )

        // 3. Rotating Radar Sweep Beam
        rotate(degrees = sweepAngle, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    0.0f to Color(0xFF00E676).copy(alpha = 0.25f),
                    0.15f to Color.Transparent,
                    center = center
                ),
                startAngle = 0f,
                sweepAngle = 60f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
            )
        }

        // 4. Rotating Compass Card (North Indicator)
        rotate(degrees = -heading, pivot = center) {
            // Draw North Pip
            drawCircle(
                color = Color(0xFFD32F2F),
                radius = 4.dp.toPx(),
                center = Offset(center.x, center.y - radius + 8.dp.toPx())
            )
        }

        // 5. Target Directional Arrow Pointer
        if (hasTargetFix) {
            rotate(degrees = relativeAngle, pivot = center) {
                val arrowColor = if (isOnTarget) Color(0xFF00E676) else Color(0xFFFF5252)
                val arrowPath = Path().apply {
                    val tipY = center.y - radius + 10.dp.toPx()
                    val baseY = tipY + 28.dp.toPx()
                    moveTo(center.x, tipY)
                    lineTo(center.x - 14.dp.toPx(), baseY)
                    lineTo(center.x, baseY - 6.dp.toPx())
                    lineTo(center.x + 14.dp.toPx(), baseY)
                    close()
                }

                drawPath(
                    path = arrowPath,
                    color = arrowColor
                )

                // Draw beacon pulse dot
                drawCircle(
                    color = arrowColor.copy(alpha = 0.35f),
                    radius = 20.dp.toPx(),
                    center = Offset(center.x, center.y - radius + 22.dp.toPx())
                )
            }
        }
    }
}
