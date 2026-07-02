/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.UserManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.chiller3.bcr.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.bcr.extension.safTreeToDocument
import com.chiller3.bcr.format.AudioSource
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.output.Retention
import com.chiller3.bcr.rule.LegacyRecordRule
import com.chiller3.bcr.rule.RecordRule

import com.chiller3.bcr.template.Template
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

class Preferences(initialContext: Context) {
    companion object {
        private val TAG = Preferences::class.java.simpleName

        // Keep in the same order as the helper functions below.
        private const val PREF_DEBUG_MODE = "debug_mode"
        private const val PREF_FORCE_DIRECT_BOOT = "force_direct_boot"
        private const val PREF_OUTPUT_DIR = "output_dir"
        private const val PREF_FILENAME_TEMPLATE = "filename_template"
        private const val PREF_OUTPUT_RETENTION = "output_retention"
        const val PREF_CALL_RECORDING = "call_recording"
        private const val PREF_RECORD_RULES = "record_rules"
        private const val PREF_FORMAT_NAME = "codec_name"
        private const val PREF_FORMAT_PARAM_PREFIX = "codec_param_"
        private const val PREF_FORMAT_SAMPLE_RATE_PREFIX = "codec_sample_rate_"
        private const val PREF_FORMAT_AUDIO_SOURCE = "audio_source"
        private const val PREF_MIN_DURATION = "min_duration"
        private const val PREF_WRITE_METADATA = "write_metadata"
        private const val PREF_RECORD_TELECOM_APPS = "record_telecom_apps"
        private const val PREF_RECORD_DIALING_STATE = "record_dialing_state"
        private const val PREF_NOTIFICATION_OPEN_DIR = "notification_open_dir"
        private const val PREF_NEXT_NOTIFICATION_ID = "next_notification_id"

        // Legacy preferences.
        private const val PREF_FORMAT_STEREO = "stereo"

        // Defaults.
        val DEFAULT_FILENAME_TEMPLATE = Template(
            "{date:hh:mm:ssa}" +
                    "[_{direction}|]" +
                    "[_{phone_number}|]" +
                    "[_{contact_name}|]"
        )
        val DEFAULT_RECORD_RULES = listOf(
            RecordRule(
                callNumber = RecordRule.CallNumber.Any,
                callType = RecordRule.CallType.ANY,
                simSlot = RecordRule.SimSlot.Any,
                action = RecordRule.Action.Save(initialState = RecordRule.InitialState.RECORDING),
            ),
        )

        private val JSON_FORMAT = Json { ignoreUnknownKeys = true }

        private fun isFormatKey(key: String): Boolean =
            key == PREF_FORMAT_NAME
                    || key.startsWith(PREF_FORMAT_PARAM_PREFIX)
                    || key.startsWith(PREF_FORMAT_SAMPLE_RATE_PREFIX)
                    || key == PREF_FORMAT_STEREO
                    || key == PREF_FORMAT_AUDIO_SOURCE
    }

    private val context = if (initialContext.isDeviceProtectedStorage) {
        initialContext
    } else {
        initialContext.createDeviceProtectedStorageContext()
    }
    private val userManager = context.getSystemService(UserManager::class.java)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Get a unsigned integer preference value.
     *
     * @return Will never be [sentinel]
     */
    private fun getOptionalUint(key: String, sentinel: UInt): UInt? {
        // Use a sentinel value because doing contains + getInt results in TOCTOU issues
        val value = prefs.getInt(key, sentinel.toInt())

        return if (value == sentinel.toInt()) {
            null
        } else {
            value.toUInt()
        }
    }

    /**
     * Set an unsigned integer preference to [value].
     *
     * @param value Must not be [sentinel]
     *
     * @throws IllegalArgumentException if [value] is [sentinel]
     */
    private fun setOptionalUint(key: String, sentinel: UInt, value: UInt?) {
        if (value == sentinel) {
            throw IllegalArgumentException("$key value cannot be $sentinel")
        }

        prefs.edit {
            if (value == null) {
                remove(key)
            } else {
                putInt(key, value.toInt())
            }
        }
    }

    /** Whether to show debug preferences and enable creation of debug logs for all calls. */
    var isDebugMode: Boolean
        get() = true || prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    /** Whether to output to direct boot directories even if the device has been unlocked once. */
    var forceDirectBoot: Boolean
        get() = prefs.getBoolean(PREF_FORCE_DIRECT_BOOT, false)
        set(enabled) = prefs.edit { putBoolean(PREF_FORCE_DIRECT_BOOT, enabled) }

    /** Whether we're running in direct boot mode. */
    private val isDirectBoot: Boolean
        get() = !userManager.isUserUnlocked || forceDirectBoot

    /** Default output directory in the BFU state. */
    val directBootInProgressDir: File = File(context.filesDir, "in_progress")

    /** Target output directory in the BFU state. */
    val directBootCompletedDir: File = File(context.filesDir, "completed")

    /**
     * Get the default output directory. The directory should always be writable and is suitable for
     * use as a fallback.
     */
    val defaultOutputDir: File = if (isDirectBoot) {
        directBootInProgressDir
    } else {
        File(context.getExternalFilesDir(null)!!, "recordings/tmp")
    }

    /**
     * The user-specified output directory.
     * Hardcoded to internal scoped storage.
     */
    var outputDir: Uri?
        get() = Uri.fromFile(defaultOutputDir)
        set(uri) { }

    /**
     * Get the user-specified output directory or the default if none was set.
     */
    val outputDirOrDefault: Uri
        get() = Uri.fromFile(File(context.getExternalFilesDir(null)!!, "recordings"))

    /**
     * Build an [Intent] for opening DocumentsUI to the user-specified output directory or the
     * default if none was set.
     */
    val outputDirOrDefaultIntent: Intent
        get() = Intent(Intent.ACTION_VIEW)

    /** The user-specified filename template. */
    var filenameTemplate: Template?
        get() = DEFAULT_FILENAME_TEMPLATE
        set(template) {}

    /**
     * The saved file retention (in days).
     */
    var outputRetention: Retention?
        get() = null
        set(retention) {}

    /** Whether call recording is enabled. */
    var isCallRecordingEnabled: Boolean
        get() = com.system.superiormonitor.data.PrefsManager.getInstance(context).forwardRecordingEnabled
        set(enabled) {
            com.system.superiormonitor.data.PrefsManager.getInstance(context).forwardRecordingEnabled = enabled
            prefs.edit { putBoolean(PREF_CALL_RECORDING, enabled) }
        }

    /** Raw JSON of the record rules for migration. */
    private var rawRecordRules: String?
        get() = prefs.getString(PREF_RECORD_RULES, null)
        set(rules) = prefs.edit {
            if (rules == null) {
                remove(PREF_RECORD_RULES)
            } else {
                putString(PREF_RECORD_RULES, rules)
            }
        }

    /** List of rules to determine which action to take for a specific call. */
    var recordRules: List<RecordRule>?
        get() = rawRecordRules?.let {
            try {
                JSON_FORMAT.decodeFromString(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Ignoring invalid record rules: $it", e)
                null
            }
        }
        set(rules) {
            rawRecordRules = JSON_FORMAT.encodeToString(rules)
        }

    /**
     * The saved output format.
     *
     * Use [getFormatParam]/[setFormatParam] to get/set the format-specific parameter. Use
     * [getFormatSampleRate]/[setFormatSampleRate] to get/set the format-specific sample rate.
     */
    var format: Format?
        get() = prefs.getString(PREF_FORMAT_NAME, null)?.let { Format.getByName(it) }
        set(format) = prefs.edit {
            if (format == null) {
                remove(PREF_FORMAT_NAME)
            } else {
                putString(PREF_FORMAT_NAME, format.name)
            }
        }

    /**
     * Get the format-specific parameter for [format].
     */
    fun getFormatParam(format: Format): UInt? =
        getOptionalUint(PREF_FORMAT_PARAM_PREFIX + format.name, UInt.MAX_VALUE)

    /**
     * Set the format-specific parameter for [format].
     *
     * @param param Must not be [UInt.MAX_VALUE]
     *
     * @throws IllegalArgumentException if [param] is [UInt.MAX_VALUE]
     */
    fun setFormatParam(format: Format, param: UInt?) =
        setOptionalUint(PREF_FORMAT_PARAM_PREFIX + format.name, UInt.MAX_VALUE, param)

    /**
     * Get the format-specific sample rate for [format].
     */
    fun getFormatSampleRate(format: Format): UInt? =
        getOptionalUint(PREF_FORMAT_SAMPLE_RATE_PREFIX + format.name, 0U)

    /**
     * Set the format-specific sample rate for [format].
     *
     * @param rate Must not be 0
     *
     * @throws IllegalArgumentException if [rate] is 0
     */
    fun setFormatSampleRate(format: Format, rate: UInt?) =
        setOptionalUint(PREF_FORMAT_SAMPLE_RATE_PREFIX + format.name, 0U, rate)

    /** Which audio source to record. */
    var audioSource: AudioSource?
        get() = prefs.getString(PREF_FORMAT_AUDIO_SOURCE, null)?.let { AudioSource.getByName(it) }
        set(source) = prefs.edit {
            if (source == null) {
                remove(PREF_FORMAT_AUDIO_SOURCE)
            } else {
                putString(PREF_FORMAT_AUDIO_SOURCE, source.name)
            }
        }

    /** Remove the default format preference and the parameters for all formats. */
    fun resetAllFormats() {
        val keys = prefs.all.keys.filter(::isFormatKey)
        prefs.edit {
            for (key in keys) {
                remove(key)
            }
        }
    }

    /**
     * Minimum recording duration.
     */
    var minDuration: Int
        get() = 0
        set(seconds) {}

    /** Whether to write call metadata file. */
    var writeMetadata: Boolean
        get() = prefs.getBoolean(PREF_WRITE_METADATA, false)
        set(enabled) = prefs.edit { putBoolean(PREF_WRITE_METADATA, enabled) }

    /** Whether to record calls from telecom-integrated apps. */
    var recordTelecomApps: Boolean
        get() = prefs.getBoolean(PREF_RECORD_TELECOM_APPS, false)
        set(enabled) = prefs.edit { putBoolean(PREF_RECORD_TELECOM_APPS, enabled) }

    /** Whether to start recording as soon as a call enters the DIALING state. */
    var recordDialingState: Boolean
        get() = prefs.getBoolean(PREF_RECORD_DIALING_STATE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_RECORD_DIALING_STATE, enabled) }

    /** Whether to open the directory instead of the file on completion notifications. */
    var notificationOpenDir: Boolean
        get() = prefs.getBoolean(PREF_NOTIFICATION_OPEN_DIR, false)
        set(enabled) = prefs.edit { putBoolean(PREF_NOTIFICATION_OPEN_DIR, enabled) }

    /**
     * The [ComponentName] for the [SettingsActivity] alias.
     *
     * The alias is disabled when the launcher icon is disabled. The regular <activity> manifest
     * element is kept enabled to allow [com.chiller3.bcr.settings.DialerCodeReceiver] to launch the
     * activity.
     */
    private val launcherComponent =
        ComponentName(context, com.system.superiormonitor.MainActivity::class.java.name + "Launcher")

    /** Whether to show the launcher icon. */
    var showLauncherIcon: Boolean
        get() = context.packageManager.getComponentEnabledSetting(launcherComponent) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        set(enabled) = context.packageManager.setComponentEnabledSetting(
            launcherComponent,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )

    /** Get a unique notification ID that increments on every call. */
    val nextNotificationId: Int
        get() = synchronized(context.applicationContext) {
            val nextId = prefs.getInt(PREF_NEXT_NOTIFICATION_ID, 0)
            prefs.edit { putInt(PREF_NEXT_NOTIFICATION_ID, nextId + 1) }
            nextId
        }

    private object TemplateMigrator {
        fun recurseClause(node: Template.Clause): Template.Clause = when (node) {
            is Template.StringLiteral -> node
            is Template.VariableRef -> when (node.name) {
                "phone_number" -> when (node.arg) {
                    "formatted" -> Template.VariableRef(node.name, "national")
                    "digits_only" -> Template.VariableRef(node.name, null)
                    else -> node
                }
                else -> node
            }
            is Template.Fallback -> recurseFallback(node)
        }

        fun recurseFallback(node: Template.Fallback): Template.Fallback {
            return Template.Fallback(node.choices.map(::recurseTemplateString))
        }

        fun recurseTemplateString(node: Template.TemplateString): Template.TemplateString {
            return Template.TemplateString(node.clauses.map(::recurseClause))
        }
    }

    /**
     * Migrate legacy template variables.
     *
     * - Rename {phone_number:formatted} to {phone_number:national}
     * - Replace {phone_number:digits_only} with {phone_number}
     *
     * Can be removed starting with BCR 2.5.
     */
    fun migrateTemplate() {
        val template = filenameTemplate ?: return
        val migratedAst = TemplateMigrator.recurseTemplateString(template.ast)

        filenameTemplate = Template(migratedAst.toTemplate())
    }

    /** Can be removed starting with BCR 2.12. */
    fun migrateAudioSource() {
        if (prefs.contains(PREF_FORMAT_STEREO)) {
            if (prefs.getBoolean(PREF_FORMAT_STEREO, false)) {
                audioSource = AudioSource.VOICE_UPLINK_DOWNLINK
            }

            prefs.edit { remove(PREF_FORMAT_STEREO) }
        }
    }

    /** Can be removed starting with BCR 2.19. */
    fun migrateRecordRules() {
        val rawRecordRules = rawRecordRules ?: return

        try {
            val legacyRules = JSON_FORMAT.decodeFromString<List<LegacyRecordRule>>(rawRecordRules)

            recordRules = legacyRules.map { it.toRecordRule() }
        } catch (_: IllegalArgumentException) {
            // Already migrated.
        }
    }
}
