package com.factory.repair.mq.consumer;

import com.factory.repair.mq.LocalMessageQueue;
import com.factory.repair.mq.Message;
import com.factory.repair.mq.MessageConsumer;
import com.factory.repair.mq.Topic;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SparePartEventConsumer implements MessageConsumer {

    private final LocalMessageQueue messageQueue;

    @PostConstruct
    public void register() {
        messageQueue.registerConsumer(this);
    }

    @Override
    public Topic subscribedTopic() {
        return Topic.SPARE_PART_EVENT;
    }

    @Override
    public void consume(Message<?> message) {
        String eventType = message.getEventType();
        log.info("处理备件事件: eventType={}, messageId={}", eventType, message.getMessageId());

        switch (eventType) {
            case "LOW_STOCK_ALERT" -> log.warn("备件低库存预警: {}", message.getPayload());
            case "RESERVATION_EXPIRED" -> log.info("备件占用过期: {}", message.getPayload());
            default -> log.debug("未处理的备件事件类型: {}", eventType);
        }
    }
}
