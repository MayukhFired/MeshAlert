package com.meshalert.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshalert.app.model.UserProfile
import com.meshalert.app.viewmodel.MainViewModel

@Composable
fun ProfileDialog(
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    val currentProfile by vm.userProfile.collectAsState()

    var name by remember(currentProfile) { mutableStateOf(currentProfile.name) }
    var selectedBloodType by remember(currentProfile) { mutableStateOf(currentProfile.bloodType) }
    var allergies by remember(currentProfile) { mutableStateOf(currentProfile.allergies) }
    var medicalConditions by remember(currentProfile) { mutableStateOf(currentProfile.medicalConditions) }
    var contactName by remember(currentProfile) { mutableStateOf(currentProfile.emergencyContactName) }
    var contactPhone by remember(currentProfile) { mutableStateOf(currentProfile.emergencyContactPhone) }
    var survivorCount by remember(currentProfile) { mutableIntStateOf(currentProfile.defaultSurvivorCount) }

    val bloodTypes = listOf("Unknown", "A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Medical ICE Emergency Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "This medical card is broadcast in your emergency SOS beacon to inform first responders.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Blood Type:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    bloodTypes.take(5).forEach { bt ->
                        val isSel = selectedBloodType == bt
                        Surface(
                            color = if (isSel) Color(0xFFD32F2F) else Color(0xFFF0F4F8),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable { selectedBloodType = bt }
                        ) {
                            Text(
                                text = bt,
                                color = if (isSel) Color.White else Color.DarkGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    bloodTypes.drop(5).forEach { bt ->
                        val isSel = selectedBloodType == bt
                        Surface(
                            color = if (isSel) Color(0xFFD32F2F) else Color(0xFFF0F4F8),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable { selectedBloodType = bt }
                        ) {
                            Text(
                                text = bt,
                                color = if (isSel) Color.White else Color.DarkGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = allergies,
                    onValueChange = { allergies = it },
                    label = { Text("Critical Allergies (e.g. Penicillin)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = medicalConditions,
                    onValueChange = { medicalConditions = it },
                    label = { Text("Conditions (e.g. Diabetic, Asthma)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contactName,
                    onValueChange = { contactName = it },
                    label = { Text("ICE Emergency Contact Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contactPhone,
                    onValueChange = { contactPhone = it },
                    label = { Text("ICE Contact Phone Number") },
                    placeholder = { Text("+1234567890") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Default Survivors in Party:", fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (survivorCount > 1) survivorCount-- }) { Text("-") }
                        Text("$survivorCount", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { survivorCount++ }) { Text("+") }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val updated = UserProfile(
                    name = name.ifBlank { "Survivor" },
                    bloodType = selectedBloodType,
                    allergies = allergies.trim(),
                    medicalConditions = medicalConditions.trim(),
                    emergencyContactName = contactName.trim(),
                    emergencyContactPhone = contactPhone.trim(),
                    defaultSurvivorCount = survivorCount
                )
                vm.updateUserProfile(updated)
                onDismiss()
            }) { Text("Save Profile") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
