package com.factory.repair.mq.consumer;

import com.factory.repair.mq.LocalMessageQueue;
import com.factory.repair.mq.Message;
import com.factory.repair.mq.MessageConsumer;
import com.factory.repair.mq.Topic;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkOrderEventConsumer implements MessageConsumer {

    private final LocalMessageQueue messageQueue;

    @PostConstruct
    public void register() {
        messageQueue.registerConsumer(this);
    }

    @Override
    public Topic subscribedTopic() {
        return Topic.WORK_ORDER_EVENT;
    }

    @Override
    public void consume(Message<?> message) {
        String eventType = message.getEventType();
        log.info("处理工单事件: eventType={}, messageId={}", eventType, message.getMessageId());

        switch (eventType) {
            case "STATUS_CHANGED" -> handleStatusChanged(message);
            case "DISPATCH_TIMEOUT" -> handleDispatchTimeout(message);
            case "URGENT_CREATED" -> handleUrgentCreated(message);
            default -> log.debug("未处理的工单事件类型: {}", eventType);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleStatusChanged(Message<?> message) {
        if (message.getPayload() instanceof Map) {
            Map<String, Object> payload = (Map<String, Object>) message.getPayload();
            log.info("工单状态变更: orderId={}, oldStatus={}, newStatus={}",
                    payload.get("workOrderId"), payload.get("oldStatus"), payload.get("newStatus"));
        }
    }

    private void handleDispatchTimeout(Message<?> message) {
        log.warn("派工超时事件: {}", message.getPayload());
    }

    private void handleUrgentCreated(Message<?> message) {
        log.warn("紧急工单创建: {}", message.getPayload());
    }
}
