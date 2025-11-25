package com.alfredoalpizar.rag.config

import com.alfredoalpizar.rag.config.ConversationProperties.StorageMode
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
 *
 * Uses the enum value directly, so it works with any case variation:
 * - in-memory, IN_MEMORY, in_memory all work
 * - database, DATABASE, Database all work
 */
@Configuration
class ConversationStorageConfig {

    private val logger = KotlinLogging.logger {}

    @Bean
    fun conversationStorage(
        properties: ConversationProperties,
        conversationRepository: Optional<ConversationRepository>,
        messageRepository: Optional<ConversationMessageRepository>
    ): ConversationStorage {
        return when (properties.storageMode) {
            StorageMode.IN_MEMORY -> {
                logger.info { "Using in-memory conversation storage (data will not persist across restarts)" }
                InMemoryConversationStorage()
            }
            StorageMode.DATABASE -> {
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
        }
    }
}
