package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.persistence.ConversationRepository;
import com.alfatgbotik.identity.service.ProfileVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String TECHNICAL_REPLY = "Сейчас не удалось получить ответ от ИИ-помощника. Попробуй отправить вопрос немного позже.";
    private final ConversationRepository conversations;
    private final ConsultationPipeline pipeline;
    private final ConversationCompletionService completion;
    private final AssistantExecution execution;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ReentrantLock[] userLocks = new ReentrantLock[64];

    public ConversationService(ConversationRepository conversations, ConsultationPipeline pipeline,
                               ConversationCompletionService completion, AssistantExecution execution,
                               PlatformTransactionManager manager, Clock clock) {
        this.conversations = conversations;
        this.pipeline = pipeline;
        this.completion = completion;
        this.execution = execution;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        for (int i = 0; i < userLocks.length; i++) userLocks[i] = new ReentrantLock(true);
    }

    public String consult(UUID userId, UUID requestId, String message) {
        if (message == null || message.isBlank() || message.codePointCount(0, message.length()) > 4000) {
            return "Напиши непустой вопрос длиной до 4000 символов.";
        }
        var deadline = execution.deadline();
        ReentrantLock lock = userLocks[Math.floorMod(userId.hashCode(), userLocks.length)];
        boolean acquired = false;
        boolean started = false;
        long beganAt = System.nanoTime();
        try {
            acquired = lock.tryLock(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) return TECHNICAL_REPLY;
            var turn = transactions.execute(status -> conversations.begin(userId, requestId, message, clock.instant()));
            if (!turn.message().equals(message)) throw new AssistantFailure("REQUEST_CONFLICT");
            if (!turn.status().equals("PROCESSING")) return turn.reply();
            started = true;
            var result = execution.run(deadline, () -> pipeline.answer(userId, message, deadline));
            completion.complete(userId, requestId, result);
            log.info("Consultation completed (requestId={}, status={}, elapsedMs={})", requestId,
                    result.response().status(), (System.nanoTime() - beganAt) / 1_000_000);
            return result.text();
        } catch (Exception exception) {
            String code = exception instanceof AssistantFailure failure ? failure.code()
                    : exception instanceof ProfileVersionConflictException || exception instanceof OptimisticLockingFailureException
                    ? "PROFILE_VERSION_CONFLICT" : exception instanceof InterruptedException ? "TIMEOUT" : "INTERNAL_ERROR";
            String reply = switch (code) {
                case "CONTEXT_TOO_LARGE" -> "Вопрос и контекст не помещаются в запрос к модели. Сократи вопрос и попробуй ещё раз.";
                case "PROFILE_VERSION_CONFLICT" -> "Профиль уже изменился во время подготовки ответа. Новые сведения не сохранены. Отправь сообщение ещё раз.";
                default -> TECHNICAL_REPLY;
            };
            if (started) transactions.executeWithoutResult(status -> conversations.fail(requestId, reply, code, clock.instant()));
            log.warn("Consultation failed (requestId={}, code={})", requestId, code);
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return reply;
        } finally {
            if (acquired) lock.unlock();
        }
    }

    public void recoverInterrupted() {
        transactions.executeWithoutResult(status -> conversations.recoverInterrupted(TECHNICAL_REPLY, clock.instant()));
    }
}
