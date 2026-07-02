package com.chiller3.bcr.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.output.OutputDirUtils
import androidx.documentfile.provider.DocumentFile

class HelperConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null

        if (uri.path == "/status") {
            val prefs = Preferences(context)
            val isEnabled = prefs.isCallRecordingEnabled
            val cursor = MatrixCursor(arrayOf("is_configured", "is_enabled"))
            // For now, if it's enabled in preferences, we consider it configured.
            cursor.addRow(arrayOf(if (isEnabled) 1 else 0, if (isEnabled) 1 else 0))
            return cursor
        } else if (uri.path == "/offline_files") {
            val prefs = Preferences(context)
            val cursor = MatrixCursor(arrayOf("file_uri", "file_name"))
            
            try {
                val outputDir = prefs.outputDirOrDefault.let { uri ->
                    if (uri.scheme == "file") {
                        DocumentFile.fromFile(java.io.File(uri.path!!))
                    } else {
                        try {
                            DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromFile(java.io.File(context.getExternalFilesDir(null)!!, "recordings"))
                        } catch (e: IllegalArgumentException) {
                            DocumentFile.fromFile(java.io.File(uri.path!!))
                        }
                    }
                }
                
                outputDir.listFiles().forEach { dateDir ->
                    if (dateDir.isDirectory) {
                        val offlineDir = dateDir.findFile("offline")
                        if (offlineDir != null && offlineDir.isDirectory) {
                            offlineDir.listFiles().forEach { file ->
                                if (file.isFile) {
                                    val originalUri = file.uri
                                    val wrappedUri = com.chiller3.bcr.RecorderProvider.fromOrigUri(originalUri)
                                    context.grantUriPermission("com.system.superiormonitor", wrappedUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    val filePath = "${dateDir.name}/offline/${file.name}"
                                    cursor.addRow(arrayOf(wrappedUri.toString(), filePath))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return cursor
        }

        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
