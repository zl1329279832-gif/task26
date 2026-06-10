package com.factory.repair.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message<T> {
    private String messageId;
    private Topic topic;
    private String eventType;
    private T payload;
    private LocalDateTime createdAt;
    private int retryCount;
    private int maxRetries;

    public static <T> Message<T> of(Topic topic, String eventType, T payload) {
        Message<T> msg = new Message<>();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setTopic(topic);
        msg.setEventType(eventType);
        msg.setPayload(payload);
        msg.setCreatedAt(LocalDateTime.now());
        msg.setRetryCount(0);
        msg.setMaxRetries(3);
        return msg;
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public void incrementRetry() {
        retryCount++;
    }
}
