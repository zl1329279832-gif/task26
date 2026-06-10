package com.factory.repair.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageProducer {

    private final LocalMessageQueue messageQueue;

    public <T> void publish(Topic topic, String eventType, T payload) {
        Message<T> message = Message.of(topic, eventType, payload);
        messageQueue.publish(message);
        log.debug("消息已发布: topic={}, eventType={}, messageId={}", topic, eventType, message.getMessageId());
    }
}
