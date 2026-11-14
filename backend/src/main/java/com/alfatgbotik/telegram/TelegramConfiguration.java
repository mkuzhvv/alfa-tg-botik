package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelegramProperties.class)
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
public class TelegramConfiguration {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(15))
            .build();

    @Bean
    public TelegramClient telegramClient(TelegramProperties properties) {
        return new OkHttpTelegramClient(httpClient, properties.getToken());
    }

    @Bean
    public TelegramConnectionCheck telegramConnectionCheck(TelegramClient telegramClient) {
        return new TelegramConnectionCheck(telegramClient);
    }

    @Bean
    public TelegramUpdateHandler telegramUpdateHandler(UserService users, TelegramClient telegramClient) {
        return new TelegramUpdateHandler(users, telegramClient);
    }

    @Bean(destroyMethod = "close")
    public TelegramPollingService telegramPollingService(TelegramClient telegramClient,
                                                        TelegramUpdateHandler updateHandler) {
        return new TelegramPollingService(telegramClient, updateHandler);
    }

    @PreDestroy
    public void close() {
        httpClient.dispatcher().cancelAll();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
