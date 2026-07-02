package com.system.superiormonitor.bot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.system.superiormonitor.data.GetMeResponse
import com.system.superiormonitor.data.UpdateResponse
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class LinkPreviewOptions(
    @SerialName("is_disabled") val isDisabled: Boolean
)

@Serializable
private data class EditMessageTextRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("message_id") val messageId: Long,
    val text: String,
    @SerialName("parse_mode") val parseMode: String? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
    @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions? = null
)

@Serializable
private data class SendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("parse_mode") val parseMode: String? = null,
    @SerialName("reply_markup") val replyMarkup: JsonElement? = null,
    @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions? = null
)

@Serializable
private data class DeleteMessageRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("message_id") val messageId: Long
)

@Serializable
private data class LeaveChatRequest(
    @SerialName("chat_id") val chatId: String
)

@Serializable
private data class AnswerCallbackQueryRequest(
    @SerialName("callback_query_id") val callbackQueryId: String,
    @SerialName("text") val text: String,
    @SerialName("show_alert") val showAlert: Boolean
)

/**
 * Centralized Telegram Bot API client.
 *
 * Single [OkHttpClient] instance and token sanitization logic shared across
 * all components (BotService, SnapshotEngine, BotCommands).
 */
object TelegramApi {

    val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val json: Json = Json { ignoreUnknownKeys = true }

    /** Escape characters that break Telegram's Markdown (V1) parsing */
    fun escapeMarkdown(text: String?): String {
        if (text == null) return ""
        return text.replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("`", "\\`")
    }

    /** Helper to strip markdown for cleaner BOT_OUT logs */
    private fun cleanMarkup(text: String): String {
        return text.replace(Regex("[*_`\\[\\]\\\\]"), "")
    }

    /** Strip leading non-alphanumeric chars (BOM, invisible Unicode, etc.) from stored tokens. */
    fun sanitizeToken(rawToken: String): String =
        rawToken.trim().replace(Regex("^[^a-zA-Z0-9]+"), "")

    private fun apiUrl(token: String, method: String): String =
        "https://api.telegram.org/bot${sanitizeToken(token)}/$method"

    // ═══════════════════════════════════════════════════════════
    //  POLLING & IDENTITY
    // ═══════════════════════════════════════════════════════════

    /**
     * Execute a long-poll getUpdates call.
     * Returns the raw OkHttp [Response] so the caller retains full control
     * over reachability tracking and error handling.
     */
    fun getUpdatesRaw(token: String, offset: Long, timeout: Int = 30): Response {
        val url = apiUrl(token, "getUpdates") + "?offset=$offset&timeout=$timeout"
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute()
    }

    /** Fetch bot identity via /getMe. Returns null on any failure. */
    fun getMe(token: String): GetMeResponse? {
        return try {
            val request = Request.Builder().url(apiUrl(token, "getMe")).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json.decodeFromString<GetMeResponse>(it) }
            } else null
        } catch (e: Exception) { null }
    }

    // ═══════════════════════════════════════════════════════════
    //  SENDING MESSAGES
    // ═══════════════════════════════════════════════════════════

    /** Send a text message. Returns the message ID on success, or null on failure. */
    fun sendMessage(
        token: String,
        chatId: String,
        text: String,
        parseMode: String? = "Markdown",
        replyMarkup: String? = null
    ): Long? {
        return try {
            val markupJson = replyMarkup?.let { json.parseToJsonElement(it) }
            val req = SendMessageRequest(chatId, text, parseMode, markupJson, LinkPreviewOptions(isDisabled = true))
            val jsonBody = json.encodeToString(req)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl(token, "sendMessage"))
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            var messageId: Long? = null
            if (!success) {
                val errorBody = response.body?.string()
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendMessage failed: ${response.code} - $errorBody", com.system.superiormonitor.util.LogLevel.ERROR)
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] " + cleanMarkup(text).take(200))
                val respBody = response.body?.string()
                if (respBody != null) {
                    try {
                        val jsonObject = json.parseToJsonElement(respBody).jsonObject
                        val result = jsonObject["result"]?.jsonObject
                        messageId = result?.get("message_id")?.jsonPrimitive?.long
                    } catch (e: Exception) {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]Failed to parse sendMessage response: ${e.message}", com.system.superiormonitor.util.LogLevel.WARN)
                    }
                }
            }
            response.close()
            messageId
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendMessage error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            null
        }
    }

    /** Edit a text message. Returns true on success. */
    fun editMessageText(
        token: String,
        chatId: String,
        messageId: Long,
        text: String,
        parseMode: String = "Markdown",
        replyMarkup: String? = null
    ): Boolean {
        return try {
            val markupJson = replyMarkup?.let { json.parseToJsonElement(it) }
            val req = EditMessageTextRequest(chatId, messageId, text, parseMode, markupJson, LinkPreviewOptions(isDisabled = true))
            val jsonBody = json.encodeToString(req)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl(token, "editMessageText"))
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                val errorBody = response.body?.string()
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]editMessageText failed: ${response.code} - $errorBody", com.system.superiormonitor.util.LogLevel.ERROR)
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG]: ${cleanMarkup(text).take(200)}")
            }
            response.close()
            success
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]editMessageText error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            false
        }
    }

    /** Delete a message. Returns true on success. */
    fun deleteMessage(
        token: String,
        chatId: String,
        messageId: Long
    ): Boolean {
        return try {
            val req = DeleteMessageRequest(chatId, messageId)
            val jsonBody = json.encodeToString(req)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl(token, "deleteMessage"))
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                val errorBody = response.body?.string()
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]deleteMessage failed: ${response.code} - $errorBody", com.system.superiormonitor.util.LogLevel.ERROR)
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Message ID: $messageId")
            }
            response.close()
            success
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]deleteMessage error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            false
        }
    }

    /** Answer a callback query, optionally showing an alert. Returns true on success. */
    fun answerCallbackQuery(
        token: String,
        callbackQueryId: String,
        text: String,
        showAlert: Boolean = false
    ): Boolean {
        return try {
            val req = AnswerCallbackQueryRequest(callbackQueryId, text, showAlert)
            val jsonBody = json.encodeToString(req)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl(token, "answerCallbackQuery"))
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                val errorBody = response.body?.string()
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]answerCallbackQuery failed: ${response.code} - $errorBody", com.system.superiormonitor.util.LogLevel.ERROR)
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Callback Query: $callbackQueryId with text: $text")
            }
            response.close()
            success
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]answerCallbackQuery error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            false
        }
    }

    /** Leave a group chat. Returns true on success. */
    fun leaveChat(
        token: String,
        chatId: String
    ): Boolean {
        return try {
            val req = LeaveChatRequest(chatId)
            val jsonBody = json.encodeToString(req)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl(token, "leaveChat"))
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                val errorBody = response.body?.string()
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]leaveChat failed: ${response.code} - $errorBody", com.system.superiormonitor.util.LogLevel.ERROR)
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Chat ID: $chatId")
            }
            response.close()
            success
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]leaveChat error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            false
        }
    }

    /** Send a photo file. Returns true on success. */
    fun sendPhoto(token: String, chatId: String, file: File, caption: String? = null): Boolean {
        return try {
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            if (caption != null) {
                builder.addFormDataPart("caption", caption)
                builder.addFormDataPart("parse_mode", "Markdown")
            }

            val request = Request.Builder()
                .url(apiUrl(token, "sendPhoto"))
                .post(builder.build())
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendPhoto failed: ${response.code}", com.system.superiormonitor.util.LogLevel.ERROR)
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Photo: ${file.name}" + (if (caption != null) " - ${cleanMarkup(caption).take(100)}" else ""))
            }
            response.close()
            success
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendPhoto error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            false
        }
    }

    /** Send a document file. Returns true on success. */
    fun sendDocument(
        token: String,
        chatId: String,
        file: File,
        caption: String,
        parseMode: String = "Markdown"
    ): Boolean {
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("parse_mode", parseMode)
                .addFormDataPart("document", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
                .build()

            val request = Request.Builder()
                .url(apiUrl(token, "sendDocument"))
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                val errorBody = response.body?.string()
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendDocument failed: ${response.code} - $errorBody", com.system.superiormonitor.util.LogLevel.ERROR)
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Document: ${file.name} - ${cleanMarkup(caption).take(100)}")
            }
            response.close()
            success
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendDocument error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            false
        }
    }

    /** Send a document file from a content URI. Returns true on success. */
    fun sendDocument(
        botToken: String,
        chatId: String,
        context: Context,
        uri: android.net.Uri,
        mimeType: String,
        fileName: String,
        caption: String,
        parseMode: String = "Markdown"
    ): Boolean? {
        return try {
            val tempFile = File(context.cacheDir, "temp_upload_${System.currentTimeMillis()}")
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            tempFile.outputStream().use { out ->
                inputStream.use { it.copyTo(out) }
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("parse_mode", parseMode)
                .addFormDataPart("document", fileName, tempFile.asRequestBody(mimeType.toMediaType()))
                .build()

            val request = Request.Builder()
                .url(apiUrl(botToken, "sendDocument"))
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            
            tempFile.delete()
            
            if (!success) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendDocument failed: ${response.code}", com.system.superiormonitor.util.LogLevel.ERROR)
                response.close()
                null
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Document (URI): $fileName - ${cleanMarkup(caption).take(100)}")
                response.close()
                true
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK]sendDocument error: ${e.message}", com.system.superiormonitor.util.LogLevel.ERROR)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  UTILITIES
    // ═══════════════════════════════════════════════════════════

    fun evictConnections() = client.connectionPool.evictAll()
    fun cancelAll() = client.dispatcher.cancelAll()

    /** Simple connectivity check (activeNetwork != null). */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    /** Detailed connectivity check verifying transport capabilities and actual internet validation. */
    fun isOnlineDetailed(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return hasTransport && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Verifies BOTH local internet capabilities and Telegram API reachability. */
    fun isApiReachable(context: Context, token: String): Boolean {
        if (!isOnlineDetailed(context)) return false
        return getMe(token) != null
    }
}
