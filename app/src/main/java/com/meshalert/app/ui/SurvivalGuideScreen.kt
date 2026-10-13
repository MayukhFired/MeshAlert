package com.meshalert.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class GuideItem(
    val title: String,
    val icon: String,
    val category: String,
    val summary: String,
    val steps: List<String>,
    val warnings: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurvivalGuideScreen(onBack: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf("All", "Trauma & First Aid", "Disaster Protocol", "Rescue Signals")

    val guideItems = remember {
        listOf(
            GuideItem(
                title = "Severe Bleeding & Tourniquet",
                icon = "🩸",
                category = "Trauma & First Aid",
                summary = "Control life-threatening blood loss immediately to prevent hypovolemic shock.",
                steps = listOf(
                    "1. Expose wound and apply FIRM DIRECT PRESSURE with sterile gauze or cleanest cloth available.",
                    "2. If bleeding soaks through, DO NOT remove the cloth; add more layers and press harder.",
                    "3. For limb bleeding that won't stop: Apply a tourniquet 5–7 cm (2–3 inches) above the wound, never over a joint.",
                    "4. Tighten tourniquet windlass until bright red bleeding completely stops.",
                    "5. Mark the exact TIME applied on victim's forehead or tourniquet band."
                ),
                warnings = listOf("Never loosen or remove a tourniquet once applied; only hospital surgeons should remove it.")
            ),
            GuideItem(
                title = "CPR (Adult & Child)",
                icon = "❤️",
                category = "Trauma & First Aid",
                summary = "Cardiopulmonary Resuscitation for non-responsive, non-breathing victims.",
                steps = listOf(
                    "1. Check responsiveness (tap shoulders and shout: 'Are you okay?').",
                    "2. Check breathing for no more than 10 seconds. If absent or gasping, begin CPR.",
                    "3. Place heel of one hand in center of chest (lower breastbone), interlock fingers with other hand.",
                    "4. Compress chest 5–6 cm deep at a rate of 100–120 compressions per minute (tempo of 'Stayin' Alive').",
                    "5. Give 30 compressions, then tilt head back, pinch nose, and deliver 2 gentle rescue breaths.",
                    "6. Continue 30:2 cycles without stopping until medical help or an AED arrives."
                ),
                warnings = listOf("If untrained in rescue breaths, deliver CONTINUOUS uninterrupted chest compressions (Hands-Only CPR).")
            ),
            GuideItem(
                title = "Choking (Heimlich Maneuver)",
                icon = "🫁",
                category = "Trauma & First Aid",
                summary = "Clear an obstructed airway for someone who cannot speak, breathe, or cough.",
                steps = listOf(
                    "1. Stand behind victim and wrap arms around their upper abdomen.",
                    "2. Make a fist with one hand and place thumb side against the abdomen, just above the navel.",
                    "3. Grasp your fist with your other hand.",
                    "4. Press inward and upward with quick, forceful abdominal thrusts.",
                    "5. Repeat until the blocking object is expelled.",
                    "6. If victim becomes unconscious, lower them gently to ground and begin CPR."
                ),
                warnings = listOf("For infants (<1 year): Give 5 firm back blows between shoulder blades, then turn over and give 5 chest thrusts.")
            ),
            GuideItem(
                title = "Burns & Chemical Exposure",
                icon = "🔥",
                category = "Trauma & First Aid",
                summary = "Stop the burning process and protect damaged skin tissue from infection.",
                steps = listOf(
                    "1. Immediately flush burn with cool, running water for 15–20 minutes.",
                    "2. Gently remove jewelry, watches, or tight clothing around the burn before swelling begins.",
                    "3. Cover loosely with sterile, non-adherent dressing or clean plastic wrap.",
                    "4. Keep victim warm with a dry blanket on undamaged areas to prevent hypothermia."
                ),
                warnings = listOf(
                    "DO NOT use ice or freezing water (causes tissue frostbite).",
                    "DO NOT apply butter, oils, or toothpaste.",
                    "DO NOT burst blisters."
                )
            ),
            GuideItem(
                title = "Earthquake Protocol",
                icon = "🏚️",
                category = "Disaster Protocol",
                summary = "Immediate actions during violent seismic tremors.",
                steps = listOf(
                    "1. DROP down onto your hands and knees immediately.",
                    "2. COVER your head and neck under a sturdy table or desk.",
                    "3. HOLD ON to your shelter until shaking completely stops.",
                    "4. If in bed: Stay there, curl up, and protect head with pillows.",
                    "5. If outdoors: Move into open spaces away from buildings, streetlights, and power wires.",
                    "6. AFTER SHAKING: Check for gas smell (rotten eggs) — shut off main valve. Do NOT light matches."
                ),
                warnings = listOf("Do NOT stand in doorways (modern doorways are not load-bearing). Do NOT use elevators.")
            ),
            GuideItem(
                title = "Flooding & Rapid Water",
                icon = "🌊",
                category = "Disaster Protocol",
                summary = "Survival guidelines during flash floods and dam failures.",
                steps = listOf(
                    "1. Climb to high ground immediately. Evacuate low-lying structures before roads flood.",
                    "2. Stay off bridges over fast-moving water (structural collapse risk).",
                    "3. Never walk or swim into moving water: 15 cm (6 inches) of water can sweep an adult off their feet.",
                    "4. If trapped in a car in rising water: Abandon vehicle and climb onto roof."
                ),
                warnings = listOf("Never drive through flooded roads. 30 cm (1 foot) of water can carry away small vehicles.")
            ),
            GuideItem(
                title = "International Rescue Signals",
                icon = "📢",
                category = "Rescue Signals",
                summary = "Universal visual, acoustic, and ground-to-air signals for SAR teams.",
                steps = listOf(
                    "1. RULE OF THREE: Any signal repeated 3 times indicates extreme emergency (3 whistle blasts, 3 gunshots, 3 flashes).",
                    "2. WHISTLE: Blow 3 loud 3-second blasts, pause 1 minute, repeat.",
                    "3. GROUND-TO-AIR SYMBOLS: Stamp into snow/sand or arrange rocks into letters at least 3m long:",
                    "   • 'V' = Require Assistance",
                    "   • 'X' = Require Medical Assistance",
                    "   • 'N' = No / Negative",
                    "   • 'Y' = Yes / Affirmative",
                    "   • '→' = Proceeding in this direction",
                    "4. MIRROR / REFLECTOR: Sweep mirror flash along horizon and toward search aircraft."
                )
            )
        )
    }

    val filteredItems = remember(searchQuery, selectedCategory) {
        guideItems.filter { item ->
            val matchesCategory = selectedCategory == "All" || item.category == selectedCategory
            val matchesQuery = searchQuery.isBlank() ||
                    item.title.contains(searchQuery, ignoreCase = true) ||
                    item.summary.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "OFFLINE FIRST-AID & SURVIVAL",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search triage, CPR, burns, earthquake...", fontSize = 13.sp, color = Color.Gray) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF1E88E5),
                    unfocusedBorderColor = Color(0xFF263238),
                    focusedContainerColor = Color(0xFF151D24),
                    unfocusedContainerColor = Color(0xFF151D24)
                )
            )

            Spacer(Modifier.height(10.dp))

            // Category Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        color = if (isSelected) Color(0xFF1E88E5) else Color(0xFF1E2832),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { selectedCategory = cat }
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.White else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Guide Cards List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredItems) { item ->
                    GuideCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun GuideCard(item: GuideItem) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D24)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(item.icon, fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            item.title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        Text(
                            item.category,
                            color = Color(0xFF1E88E5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = if (expanded) "▲" else "▼",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(item.summary, fontSize = 12.sp, color = Color(0xFFB0BEC5))

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = Color(0xFF222B35), modifier = Modifier.padding(bottom = 8.dp))

                    Text("ACTION STEPS:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF00E676))
                    Spacer(Modifier.height(4.dp))

                    item.steps.forEach { step ->
                        Text(
                            text = step,
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 3.dp),
                            lineHeight = 17.sp
                        )
                    }

                    if (item.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFFD32F2F).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text("⚠️ CRITICAL WARNING:", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color(0xFFFF5252))
                                item.warnings.forEach { warn ->
                                    Text(warn, fontSize = 11.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
