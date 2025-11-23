package com.alfredoalpizar.rag.model.domain

data class ConversationContext(
    val conversation: Conversation,
    val messages: List<Message>,
    val totalTokens: Int
)
