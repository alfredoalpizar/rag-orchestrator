package com.alfredoalpizar.rag.config

import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.net.URI
import java.time.Duration

@Configuration
class DynamoDBConfig {

    private val logger = KotlinLogging.logger {}

    @Bean
    fun dynamoDbAsyncClient(): DynamoDbAsyncClient {
        val builder = DynamoDbAsyncClient.builder()
            .region(Region.of(Environment.DYNAMODB_REGION))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(10))
                    .apiCallAttemptTimeout(Duration.ofSeconds(5))
                    .build()
            )

        if (Environment.DYNAMODB_ENDPOINT != null) {
            logger.info { "Configuring DynamoDB client with endpoint: ${Environment.DYNAMODB_ENDPOINT}" }
            builder.endpointOverride(URI.create(Environment.DYNAMODB_ENDPOINT))
        } else {
            logger.info { "Configuring DynamoDB client with AWS endpoints (region: ${Environment.DYNAMODB_REGION})" }
        }

        return builder.build().also {
            logger.info { "DynamoDB client initialized" }
        }
    }
}
