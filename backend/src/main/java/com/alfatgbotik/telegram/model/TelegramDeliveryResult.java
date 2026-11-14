package com.alfatgbotik.telegram.model;

import java.time.Duration;

public record TelegramDeliveryResult(TelegramUpdateStatus status, String errorCode, Duration retryAfter) {

    public static TelegramDeliveryResult delivered() {
        return new TelegramDeliveryResult(TelegramUpdateStatus.DONE, null, Duration.ZERO);
    }

    public static TelegramDeliveryResult retry(String errorCode, Duration delay) {
        return new TelegramDeliveryResult(TelegramUpdateStatus.PENDING, errorCode, delay);
    }

    public static TelegramDeliveryResult failed(String errorCode) {
        return new TelegramDeliveryResult(TelegramUpdateStatus.FAILED, errorCode, Duration.ZERO);
    }

    public static TelegramDeliveryResult unknown() {
        return new TelegramDeliveryResult(TelegramUpdateStatus.DELIVERY_UNKNOWN, "TRANSPORT", Duration.ZERO);
    }
}
