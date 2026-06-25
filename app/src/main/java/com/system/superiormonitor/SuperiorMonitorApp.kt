package com.system.superiormonitor

import android.app.Application
import com.topjohnwu.superuser.Shell

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
    }
}
