package com.system.superiormonitor.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.system.superiormonitor.util.LogManager
import com.system.superiormonitor.util.LogCategory

class MonitorDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        LogManager.log(LogCategory.SYSTEM, "Device Administrator enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        LogManager.log(LogCategory.SYSTEM, "Device Administrator disabled")
    }
}
