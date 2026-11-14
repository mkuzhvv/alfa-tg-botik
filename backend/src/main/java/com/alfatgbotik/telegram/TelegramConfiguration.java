package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.telegram.persistence.TelegramUpdateRepository;
import com.alfatgbotik.telegram.service.TelegramUpdateProcessor;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.Clock;

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
    public TelegramCommandRegistration telegramCommandRegistration(TelegramClient telegramClient) {
        return new TelegramCommandRegistration(telegramClient);
    }

    @Bean
    public TelegramUpdateHandler telegramUpdateHandler(UserService users) {
        return new TelegramUpdateHandler(users);
    }

    @Bean
    public TelegramReplySender telegramReplySender(TelegramClient telegramClient) {
        return new TelegramReplySender(telegramClient);
    }

    @Bean
    public TelegramUpdateProcessor telegramUpdateProcessor(TelegramUpdateRepository updates,
                                                          TelegramUpdateHandler handler,
                                                          TelegramReplySender sender,
                                                          PlatformTransactionManager transactionManager) {
        return new TelegramUpdateProcessor(updates, handler, sender, transactionManager, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public TelegramPollingService telegramPollingService(TelegramClient telegramClient,
                                                        TelegramUpdateProcessor processor,
                                                        TelegramConnectionCheck connectionCheck) {
        return new TelegramPollingService(telegramClient, processor, connectionCheck);
    }

    @PreDestroy
    public void close() {
        httpClient.dispatcher().cancelAll();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
