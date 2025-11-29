package com.alfredoalpizar.rag.config

import com.alfredoalpizar.rag.repository.ConversationMessageRepository
import com.alfredoalpizar.rag.repository.ConversationRepository
import com.alfredoalpizar.rag.service.context.ConversationStorage
import com.alfredoalpizar.rag.service.context.DatabaseConversationStorage
import com.alfredoalpizar.rag.service.context.InMemoryConversationStorage
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Optional

/**
 * Configuration for conversation storage.
 * Creates the appropriate storage bean based on conversation.storage-mode property.
 */
@Configuration
class ConversationStorageConfig {

    private val logger = KotlinLogging.logger {}

    @Bean
    fun conversationStorage(
        conversationRepository: Optional<ConversationRepository>,
        messageRepository: Optional<ConversationMessageRepository>
    ): ConversationStorage {
        val storageMode = Environment.CONVERSATION_STORAGE_MODE.lowercase().replace("-", "_")

        return when (storageMode) {
            "in_memory" -> {
                logger.info { "Using in-memory conversation storage (data will not persist across restarts)" }
                InMemoryConversationStorage()
            }
            "database" -> {
                logger.info { "Using database conversation storage" }
                DatabaseConversationStorage(
                    conversationRepository.orElseThrow {
                        IllegalStateException("Database storage requires JPA repositories. Enable database auto-configuration.")
                    },
                    messageRepository.orElseThrow {
                        IllegalStateException("Database storage requires JPA repositories. Enable database auto-configuration.")
                    }
                )
            }
            else -> {
                logger.warn { "Unknown storage mode '$storageMode', defaulting to in-memory" }
                InMemoryConversationStorage()
            }
        }
    }
}
