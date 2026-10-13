package com.meshalert.app.model

/**
 * In Case of Emergency (ICE) Profile & Medical Card.
 * Carried in the broadcast payload to assist first responders.
 */
data class UserProfile(
    val name: String = "Survivor",
    val bloodType: String = "Unknown",
    val allergies: String = "",
    val medicalConditions: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val defaultSurvivorCount: Int = 1
) {
    fun toMedicalSummary(): String {
        val parts = mutableListOf<String>()
        if (bloodType.isNotBlank() && bloodType != "Unknown") parts.add("Blood: $bloodType")
        if (allergies.isNotBlank()) parts.add("Allergies: $allergies")
        if (medicalConditions.isNotBlank()) parts.add("Med: $medicalConditions")
        return if (parts.isNotEmpty()) "[${parts.joinToString(", ")}]" else ""
    }
}
