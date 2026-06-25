package com.system.superiormonitor.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.format.AudioSource
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.format.RangedParamInfo
import com.chiller3.bcr.format.RangedParamType
import com.system.superiormonitor.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BCRSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }

    // State
    var recordTelecomApps by remember { mutableStateOf(prefs.recordTelecomApps) }
    var recordDialingState by remember { mutableStateOf(prefs.recordDialingState) }

    var currentFormat by remember { mutableStateOf(Format.fromPreferences(prefs).format) }
    var currentSampleRate by remember { mutableStateOf(Format.fromPreferences(prefs).sampleRate) }
    var currentParam by remember { mutableStateOf(Format.fromPreferences(prefs).param) }
    var currentAudioSource by remember { mutableStateOf(Format.fromPreferences(prefs).audioSource) }

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
        // ── Audio Settings ──
        OuterCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Audio Settings", Icons.Default.AudioFile)
                IconButton(onClick = { toggleInfo("audio_settings") }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
            AnimatedVisibility(visible = expandedInfo == "audio_settings") {
                Text(
                    "Configure how phone calls are captured and encoded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    CollapsibleDropdownSelector(
                        label = "Audio Source",
                        currentValue = currentAudioSource,
                        options = AudioSource.entries.toList(),
                        labelMapper = {
                            when (it) {
                                AudioSource.VOICE_CALL -> "Voice Call (Mono)"
                                AudioSource.VOICE_UPLINK_DOWNLINK -> "Voice Uplink + Downlink (Stereo)"
                                AudioSource.VOICE_UPLINK -> "Voice Uplink (You)"
                                AudioSource.VOICE_DOWNLINK -> "Voice Downlink (Them)"
                            }
                        },
                        onValueChange = {
                            prefs.audioSource = it
                            currentAudioSource = it
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            InnerListHost {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    CollapsibleDropdownSelector(
                        label = "Encoding Format",
                        currentValue = currentFormat,
                        options = Format.all,
                        labelMapper = { it.name },
                        onValueChange = { format ->
                            prefs.format = format
                            currentFormat = format
                            currentSampleRate = prefs.getFormatSampleRate(format)?.let { format.sampleRateInfo.toNearest(it) }
                            currentParam = prefs.getFormatParam(format)?.let { format.paramInfo.toNearest(it) }
                        }
                    )
                }
            }

            val sampleRates = currentFormat.sampleRateInfo.presets.toList()
            if (sampleRates.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                InnerListHost {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        CollapsibleDropdownSelector(
                            label = "Sample Rate",
                            currentValue = currentSampleRate ?: currentFormat.sampleRateInfo.default,
                            options = sampleRates,
                            labelMapper = { "${it} Hz" },
                            onValueChange = {
                                prefs.setFormatSampleRate(currentFormat, it)
                                currentSampleRate = it
                            }
                        )
                    }
                }
            }

            val paramInfo = currentFormat.paramInfo
            val paramOptions = paramInfo.presets.toList()
            if (paramOptions.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                val paramLabel = if (paramInfo is RangedParamInfo) {
                    when (paramInfo.type) {
                        RangedParamType.Bitrate -> "Bitrate (kbps)"
                        RangedParamType.CompressionLevel -> "Compression Level"
                        else -> "Parameter"
                    }
                } else "Quality"
                
                InnerListHost {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        CollapsibleDropdownSelector(
                            label = "Parameter",
                            currentValue = currentParam ?: paramInfo.default,
                            options = paramOptions,
                            labelMapper = { "${it}" },
                            onValueChange = {
                                prefs.setFormatParam(currentFormat, it)
                                currentParam = it
                            }
                        )
                    }
                }
            }
        }

        // ── Auto Record Rules ──
        OuterCard {
            SectionTitle("Auto Record Rules", Icons.Default.Phone)
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "Record Telecom Apps",
                    subtitle = "Record calls from VoIP apps integrated with the system telecom manager.",
                    checked = recordTelecomApps,
                    showDivider = false,
                    onCheckedChange = {
                        prefs.recordTelecomApps = it
                        recordTelecomApps = it
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            InnerListHost {
                TactileToggleRow(
                    label = "Record Dialing State",
                    subtitle = "Start recording as soon as the call begins dialing, before connection.",
                    checked = recordDialingState,
                    showDivider = false,
                    onCheckedChange = {
                        prefs.recordDialingState = it
                        recordDialingState = it
                    }
                )
            }
        }

        // ── About BCR ──
        OuterCard {
            SectionTitle("About Recorder", Icons.Default.Info)
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    InfoRow("Engine", "Basic Call Recorder (BCR)")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("Version", "3.2 (release)")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("Author", "chenxiaolong")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("Audio Source", "System Native AudioRecord")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("Storage", "Internal Scoped Storage")
                }
            }
        }
    }
}


@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1.5f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> CollapsibleDropdownSelector(
    label: String,
    currentValue: T,
    options: List<T>,
    labelMapper: (T) -> String,
    onValueChange: (T) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Icon(
                imageVector = if (isExpanded) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                contentDescription = "Expand Options",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        
        AnimatedVisibility(visible = isExpanded) {
            var expandedDropdown by remember { mutableStateOf(false) }
            
            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = { expandedDropdown = !expandedDropdown }
            ) {
                OutlinedTextField(
                    value = labelMapper(currentValue),
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
                    options.forEach { option ->
                        val isSelected = option == currentValue
                        DropdownMenuItem(
                            text = { Text(labelMapper(option), color = if (isSelected) AccentGreen else TextPrimary) },
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

