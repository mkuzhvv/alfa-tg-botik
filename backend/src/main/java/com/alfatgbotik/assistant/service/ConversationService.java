package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.integration.AssistantPrompt;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.persistence.ConversationRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String TECHNICAL_REPLY = "Сейчас не удалось получить ответ от ИИ-помощника. Попробуй отправить вопрос немного позже.";
    private final ConversationRepository conversations;
    private final ConsultationPipeline pipeline;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration timeout;
    private final ReentrantLock[] userLocks = new ReentrantLock[64];
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("assistant-model-", 0).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public ConversationService(ConversationRepository conversations, ConsultationPipeline pipeline,
                               PlatformTransactionManager manager, Clock clock,
                               @Value("${assistant.timeout:60s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Assistant timeout must be between 0 and 60 seconds");
        }
        this.conversations = conversations;
        this.pipeline = pipeline;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.timeout = timeout;
        for (int i = 0; i < userLocks.length; i++) {
            userLocks[i] = new ReentrantLock(true);
        }
    }

    public String consult(UUID userId, UUID requestId, String message) {
        if (message == null || message.isBlank() || message.codePointCount(0, message.length()) > 4000) {
            return "Напиши непустой вопрос длиной до 4000 символов.";
        }
        ConsultationDeadline deadline = new ConsultationDeadline(timeout);
        ReentrantLock lock = userLocks[Math.floorMod(userId.hashCode(), userLocks.length)];
        boolean acquired = false;
        Future<ConsultationPipeline.Result> future = null;
        boolean started = false;
        long beganAt = System.nanoTime();
        try {
            acquired = lock.tryLock(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                return TECHNICAL_REPLY;
            }
            var turn = transactions.execute(status -> conversations.begin(userId, requestId, message, clock.instant()));
            if (!turn.message().equals(message)) {
                throw new AssistantFailure("REQUEST_CONFLICT");
            }
            if (!turn.status().equals("PROCESSING")) {
                return turn.reply();
            }
            started = true;
            future = executor.submit(() -> pipeline.answer(userId, message, deadline));
            ConsultationPipeline.Result result = future.get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
            transactions.executeWithoutResult(status -> conversations.complete(requestId, result.response(), result.text(),
                    result.modelName(), result.modelRevision(), AssistantPrompt.VERSION, result.datasetVersion(), clock.instant()));
            log.info("Consultation completed (requestId={}, status={}, elapsedMs={})", requestId,
                    result.response().status(), (System.nanoTime() - beganAt) / 1_000_000);
            return result.text();
        } catch (Exception exception) {
            if (future != null) {
                future.cancel(true);
                executor.purge();
            }
            String code = failureCode(exception);
            String reply = code.equals("CONTEXT_TOO_LARGE")
                    ? "Вопрос и контекст не помещаются в запрос к модели. Сократи вопрос и попробуй ещё раз."
                    : TECHNICAL_REPLY;
            if (started) {
                transactions.executeWithoutResult(status -> conversations.fail(requestId, reply, code, clock.instant()));
            }
            log.warn("Consultation failed (requestId={}, code={})", requestId, code);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return reply;
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    public void recoverInterrupted() {
        transactions.executeWithoutResult(status -> conversations.recoverInterrupted(TECHNICAL_REPLY, clock.instant()));
    }

    private String failureCode(Exception exception) {
        Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
        if (cause instanceof AssistantFailure failure) {
            return failure.code();
        }
        if (cause instanceof TimeoutException || cause instanceof InterruptedException) {
            return "TIMEOUT";
        }
        return cause instanceof RejectedExecutionException ? "BUSY" : "INTERNAL_ERROR";
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
