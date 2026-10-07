package com.meshalert.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import com.meshalert.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    var showAlertDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.startService() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MeshAlert", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Offline Emergency Network", fontSize = 12.sp, color = Color.Gray)

        Spacer(Modifier.height(24.dp))

        // SOS Button
        Button(
            onClick = { vm.sendSos("Me", 0.0, 0.0) },
            modifier = Modifier.size(140.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("SOS", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = { showAlertDialog = true }) {
            Text("Send Alert")
        }

        Spacer(Modifier.height(24.dp))

        Text("Alert Feed (${messages.size})", fontWeight = FontWeight.SemiBold)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(messages, key = { it.id }) { msg ->
                MessageCard(msg)
            }
        }
    }

    if (showAlertDialog) {
        AlertDialog(vm) { showAlertDialog = false }
    }
}

@Composable
private fun MessageCard(msg: MeshMessage) {
    val bgColor = when (msg.type) {
        AlertType.SOS -> Color(0xFFFFEBEE)
        AlertType.SHELTER -> Color(0xFFE8F5E9)
        AlertType.ROAD_BLOCKED -> Color(0xFFFFF8E1)
        else -> Color(0xFFF5F5F5)
    }
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(msg.type.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(timeStr, fontSize = 12.sp, color = Color.Gray)
            }
            Text(msg.content, fontSize = 14.sp)
            Text("from ${msg.senderName} · ${msg.hopCount} hops", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun AlertDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var selectedType by remember { mutableStateOf(AlertType.INFO) }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send Alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AlertType.entries.forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedType == type,
                            onClick = { selectedType = type }
                        )
                        Text(type.name)
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (content.isNotBlank()) {
                    vm.sendAlert("Me", selectedType, content)
                    onDismiss()
                }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
