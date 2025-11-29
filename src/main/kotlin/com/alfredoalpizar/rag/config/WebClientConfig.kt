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
    fun deepSeekWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(Environment.DEEPSEEK_TIMEOUT_SECONDS))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(Environment.DEEPSEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .addHandlerLast(WriteTimeoutHandler(Environment.DEEPSEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }

        return WebClient.builder()
            .baseUrl(Environment.DEEPSEEK_BASE_URL)
            .defaultHeader("Authorization", "Bearer ${Environment.DEEPSEEK_API_KEY}")
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    @Bean
    fun chromaDBWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(Environment.CHROMADB_TIMEOUT_SECONDS))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(Environment.CHROMADB_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .addHandlerLast(WriteTimeoutHandler(Environment.CHROMADB_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }

        return WebClient.builder()
            .baseUrl(Environment.CHROMADB_BASE_URL)
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    @Bean
    fun qwenWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(Environment.QWEN_TIMEOUT_SECONDS))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(Environment.QWEN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .addHandlerLast(WriteTimeoutHandler(Environment.QWEN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }

        return WebClient.builder()
            .baseUrl(Environment.FIREWORKS_BASE_URL)
            .defaultHeader("Authorization", "Bearer ${Environment.FIREWORKS_API_KEY}")
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
