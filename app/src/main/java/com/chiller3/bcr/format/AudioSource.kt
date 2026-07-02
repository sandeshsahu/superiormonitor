/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.format

import android.media.MediaRecorder


enum class AudioSource {
    VOICE_CALL,
    VOICE_UPLINK_DOWNLINK,
    VOICE_UPLINK,
    VOICE_DOWNLINK;

    val sources: Array<Int>
        get() = when (this) {
            VOICE_CALL -> arrayOf(MediaRecorder.AudioSource.VOICE_CALL)
            VOICE_UPLINK_DOWNLINK -> arrayOf(
                MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
            )
            VOICE_UPLINK -> arrayOf(MediaRecorder.AudioSource.VOICE_UPLINK)
            VOICE_DOWNLINK -> arrayOf(MediaRecorder.AudioSource.VOICE_DOWNLINK)
        }

    val isStereo: Boolean
        get() = this == VOICE_UPLINK_DOWNLINK

    val nameResId: Int
        get() = when (this) {
            VOICE_CALL -> 0
            VOICE_UPLINK_DOWNLINK -> 0
            VOICE_UPLINK -> 0
            VOICE_DOWNLINK -> 0
        }

    companion object {
        fun getByName(name: String): AudioSource? = AudioSource.entries.find { it.name == name }
    }
}
