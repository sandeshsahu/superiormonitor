package com.system.superiormonitor

import android.app.Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class SuperiorMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize libsu with MOUNT_MASTER flag to cross mount namespaces
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )

        // Initialize BCR notification channels
        com.chiller3.bcr.Notifications(this).updateChannels()

        // Migrate BCR Preferences
        val bcrPrefs = com.chiller3.bcr.Preferences(this)
        bcrPrefs.migrateTemplate()
        bcrPrefs.migrateAudioSource()
        bcrPrefs.migrateRecordRules()

        // Pre-warm EncryptedSharedPreferences on a background thread.
        // This prevents massive 1-3 second IPC/decryption hangs (ANRs) on the Main Thread
        // when BroadcastReceivers or the UI subsequently call PrefsManager.getInstance()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.system.superiormonitor.data.PrefsManager.getInstance(this@SuperiorMonitorApp)
            } catch (e: Exception) {
                // Pre-warming failed, will fallback to on-demand decryption
            }
        }
    }
}
