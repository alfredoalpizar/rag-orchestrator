package com.alfredoalpizar.rag.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class WebClientConfig {

    @Bean
    fun deepSeekWebClient(properties: DeepSeekProperties): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(properties.timeoutSeconds, TimeUnit.SECONDS))
                    .addHandlerLast(WriteTimeoutHandler(properties.timeoutSeconds, TimeUnit.SECONDS))
            }

        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Authorization", "Bearer ${properties.apiKey}")
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    @Bean
    fun chromaDBWebClient(properties: ChromaDBProperties): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(properties.timeoutSeconds, TimeUnit.SECONDS))
                    .addHandlerLast(WriteTimeoutHandler(properties.timeoutSeconds, TimeUnit.SECONDS))
            }

        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
