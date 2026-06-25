package com.system.superiormonitor.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GetMeResponse(
    val ok: Boolean,
    val result: User? = null
)

@Serializable
data class UpdateResponse(
    val ok: Boolean,
    val result: List<Update> = emptyList()
)

@Serializable
data class ChatMemberUpdated(
    val chat: Chat,
    val from: User,
    val date: Long
)

@Serializable
data class Update(
    val update_id: Long,
    val message: Message? = null,
    val callback_query: CallbackQuery? = null,
    val my_chat_member: ChatMemberUpdated? = null
)

@Serializable
data class Message(
    val message_id: Long,
    val from: User? = null,
    val chat: Chat,
    val date: Long = 0,
    val text: String? = null,
    val photo: List<JsonElement>? = null,
    val document: JsonElement? = null,
    val video: JsonElement? = null,
    val audio: JsonElement? = null,
    val voice: JsonElement? = null
)

@Serializable
data class CallbackQuery(
    val id: String,
    val from: User,
    val message: Message? = null,
    val data: String? = null
)

@Serializable
data class User(
    val id: Long,
    val is_bot: Boolean = false,
    val first_name: String,
    val username: String? = null
)

@Serializable
data class Chat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    val first_name: String? = null
)
