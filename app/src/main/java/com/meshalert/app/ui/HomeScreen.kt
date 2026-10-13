package com.meshalert.app.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshalert.app.location.LocationHelper
import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import com.meshalert.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@Composable
fun HomeScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    val userLocation by vm.currentLocation.collectAsState()
    val isAlarmActive by vm.isAlarmActive.collectAsState()
    val activeSosMessage by vm.activeSosMessage.collectAsState()
    val selectedRadarTarget by vm.selectedRadarTarget.collectAsState()
    val isStrobeActive by vm.isStrobeActive.collectAsState()
    val isWhistleActive by vm.isWhistleActive.collectAsState()
    val isWifiConnected by vm.isWifiDirectConnected.collectAsState()
    val wifiPeers by vm.wifiDirectPeers.collectAsState()
    val hasInternetOrCell by vm.hasInternetOrCell.collectAsState()
    var showAlertDialog by remember { mutableStateOf(false) }
    var showGatewayDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showSurvivalGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.startService() }

    // If Survival Guide is opened, display offline emergency protocol guide
    if (showSurvivalGuide) {
        SurvivalGuideScreen(onBack = { showSurvivalGuide = false })
        return
    }

    // If a radar beacon is selected, display the offline Radar HUD
    if (selectedRadarTarget != null) {
        RadarScreen(
            targetMessage = selectedRadarTarget!!,
            onBack = { vm.selectRadarTarget(null) },
            vm = vm
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MeshAlert", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("Decentralized Offline Emergency Network", fontSize = 12.sp, color = Color.Gray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSurvivalGuide = true }) {
                    Text("📖", fontSize = 20.sp)
                }
                IconButton(onClick = { showProfileDialog = true }) {
                    Text("🪪", fontSize = 20.sp)
                }
                IconButton(onClick = { showGatewayDialog = true }) {
                    Text(
                        text = if (hasInternetOrCell) "📶" else "📵",
                        fontSize = 20.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // GPS Lock & Radio Mesh Status Chip
        GpsStatusBar(
            location = userLocation,
            isWifiConnected = isWifiConnected,
            peerCount = wifiPeers.size,
            hasInternetOrCell = hasInternetOrCell
        )



        if (isAlarmActive) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        activeSosMessage?.let { sos ->
                            if (sos.latitude != 0.0 || sos.longitude != 0.0) {
                                vm.selectRadarTarget(sos)
                            }
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🚨 SOS ALARM ACTIVE!",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            activeSosMessage?.let { "From ${it.senderName} (${it.hopCount} hops) · Tap for Radar" }
                                ?: "Emergency beacon received",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = { vm.silenceAlarm() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("SILENCE", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Large SOS Trigger Button
        Button(
            onClick = { vm.sendSos("Survivor", survivorCount = 1) },
            modifier = Modifier.size(140.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("BROADCAST", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { showAlertDialog = true },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Send Alert", fontSize = 13.sp)
            }

            val nearestBeacon = messages.firstOrNull { it.latitude != 0.0 || it.longitude != 0.0 }
            Button(
                onClick = { nearestBeacon?.let { vm.selectRadarTarget(it) } },
                enabled = nearestBeacon != null,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1565C0),
                    disabledContainerColor = Color.LightGray
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (nearestBeacon != null) "🛰️ Radar HUD" else "🛰️ No Fix",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(10.dp))


        // Hardware Emergency Rescue Tools (Flashlight Strobe & Acoustic Whistle)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { vm.toggleSosStrobe() },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isStrobeActive) Color(0xFFFFD600) else Color(0xFFF1F5F9),
                    contentColor = if (isStrobeActive) Color.Black else Color.DarkGray
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isStrobeActive) "🔦 STROBE ON" else "🔦 SOS Strobe",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            FilledTonalButton(
                onClick = { vm.toggleWhistle() },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isWhistleActive) Color(0xFFFF5722) else Color(0xFFF1F5F9),
                    contentColor = if (isWhistleActive) Color.White else Color.DarkGray
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isWhistleActive) "📢 WHISTLE ON" else "📢 Siren Whistle",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))


        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Emergency Feed (${messages.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (userLocation != null) {
                Text("Range Active", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No broadcasts in range yet.\nListening on BLE Mesh...", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageCard(
                        msg = msg,
                        userLocation = userLocation,
                        onOpenRadar = { vm.selectRadarTarget(it) }
                    )
                }
            }
        }
    }

    if (showAlertDialog) {
        AlertDialog(vm) { showAlertDialog = false }
    }

    if (showGatewayDialog) {
        GatewaySettingsDialog(vm) { showGatewayDialog = false }
    }

    if (showProfileDialog) {
        ProfileDialog(vm = vm, onDismiss = { showProfileDialog = false })
    }
}


@Composable
private fun GpsStatusBar(
    location: Location?,
    isWifiConnected: Boolean = false,
    peerCount: Int = 0,
    hasInternetOrCell: Boolean = false
) {
    val p2pSuffix = when {
        isWifiConnected -> " · 📡 P2P Linked"
        peerCount > 0 -> " · 📡 $peerCount Peers"
        else -> " · 📡 BLE Active"
    }
    val cellSuffix = if (hasInternetOrCell) " · 📶 Gateway Online" else " · 📵 Offline"

    val (statusText, chipColor, textColor) = if (location != null) {
        val acc = location.accuracy.roundToInt()
        Triple(
            "🛰️ GPS: ±%dm%s%s".format(acc, p2pSuffix, cellSuffix),
            Color(0xFFE8F5E9),
            Color(0xFF1B5E20)
        )
    } else {
        Triple(
            "🛰️ Acquiring GPS%s%s".format(p2pSuffix, cellSuffix),
            Color(0xFFFFF3E0),
            Color(0xFFE65100)
        )
    }



    Surface(
        color = chipColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Text(
            text = statusText,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun MessageCard(
    msg: MeshMessage,
    userLocation: Location?,
    onOpenRadar: (MeshMessage) -> Unit
) {
    val isSos = msg.type == AlertType.SOS
    val bgColor = when (msg.type) {
        AlertType.SOS -> Color(0xFFFFEBEE)
        AlertType.SHELTER -> Color(0xFFE8F5E9)
        AlertType.ROAD_BLOCKED -> Color(0xFFFFF8E1)
        else -> Color(0xFFF5F5F5)
    }
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))

    // Distance and bearing calculation
    val hasCoordinates = msg.latitude != 0.0 || msg.longitude != 0.0
    val distanceInfo = remember(msg.latitude, msg.longitude, userLocation) {
        if (hasCoordinates && userLocation != null) {
            val (distMeters, bearing) = LocationHelper.calculateDistanceAndBearing(
                userLocation.latitude,
                userLocation.longitude,
                msg.latitude,
                msg.longitude
            )
            val distFormatted = LocationHelper.formatDistance(distMeters)
            val bearingFormatted = LocationHelper.formatBearing(bearing)
            "$distFormatted · $bearingFormatted"
        } else if (hasCoordinates) {
            "%.4f, %.4f".format(msg.latitude, msg.longitude)
        } else {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(enabled = hasCoordinates) { onOpenRadar(msg) },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSos) 3.dp else 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isSos) Color(0xFFD32F2F) else Color.DarkGray,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        msg.type.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Text(timeStr, fontSize = 12.sp, color = Color.Gray)
            }

            Spacer(Modifier.height(6.dp))

            Text(
                msg.content,
                fontSize = 15.sp,
                fontWeight = if (isSos) FontWeight.SemiBold else FontWeight.Normal
            )

            Spacer(Modifier.height(8.dp))

            // Distance & Bearing Indicator Badge
            if (distanceInfo != null) {
                Surface(
                    color = Color.White.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable {
                        onOpenRadar(msg)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📍 $distanceInfo",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSos) Color(0xFFC62828) else Color(0xFF1565C0)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(Tap for Radar HUD)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0277BD)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }


            if (msg.isForwarded) {
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        "✓ Forwarded via SMS Gateway",
                        color = Color(0xFF1B5E20),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Node: ${msg.senderName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                Text(
                    "${msg.hopCount} hops · TTL ${msg.ttl}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun GatewaySettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val hasInternetOrCell by vm.hasInternetOrCell.collectAsState()
    val emergencyPhone by vm.emergencyContactPhone.collectAsState()
    val isAutoForwardEnabled by vm.isAutoForwardSmsEnabled.collectAsState()
    val forwardedCount by vm.forwardedCount.collectAsState()

    var phoneInput by remember(emergencyPhone) { mutableStateOf(emergencyPhone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cellular SMS Gateway (DTN)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = if (hasInternetOrCell) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hasInternetOrCell) "📶 Cell / Internet Online" else "📵 Offline (DTN Queue Active)",
                            color = if (hasInternetOrCell) Color(0xFF1B5E20) else Color(0xFFE65100),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    "When any phone in the mesh catches cell signal, it will auto-forward accumulated offline SOS beacons to your emergency contacts via SMS.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("Emergency Contact Phone #") },
                    placeholder = { Text("+1234567890 or 911") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-forward on signal", fontSize = 13.sp)
                    Switch(
                        checked = isAutoForwardEnabled,
                        onCheckedChange = { vm.setAutoForwardSmsEnabled(it) }
                    )
                }

                if (forwardedCount > 0) {
                    Text(
                        "Total beacons forwarded: $forwardedCount",
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.setEmergencyContactPhone(phoneInput)
                if (hasInternetOrCell) {
                    vm.triggerManualSmsForward()
                }
                onDismiss()
            }) { Text("Save & Sync") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AlertDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var selectedType by remember { mutableStateOf(AlertType.INFO) }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send Emergency Alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select Alert Category:", fontSize = 12.sp, color = Color.Gray)
                AlertType.entries.forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedType = type }
                    ) {
                        RadioButton(
                            selected = selectedType == type,
                            onClick = { selectedType = type }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(type.name, fontSize = 14.sp)
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Message / Situation Details") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (content.isNotBlank()) {
                    vm.sendAlert("Me", selectedType, content)
                    onDismiss()
                }
            }) { Text("Broadcast") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

