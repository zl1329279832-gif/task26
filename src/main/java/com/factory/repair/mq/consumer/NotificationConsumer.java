package com.factory.repair.mq.consumer;

import com.factory.repair.mq.LocalMessageQueue;
import com.factory.repair.mq.Message;
import com.factory.repair.mq.MessageConsumer;
import com.factory.repair.mq.Topic;
import com.factory.repair.websocket.WebSocketSessionManager;
import com.factory.repair.websocket.WsMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer implements MessageConsumer {

    private final LocalMessageQueue messageQueue;
    private final WebSocketSessionManager sessionManager;

    @PostConstruct
    public void register() {
        messageQueue.registerConsumer(this);
    }

    @Override
    public Topic subscribedTopic() {
        return Topic.NOTIFICATION_EVENT;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void consume(Message<?> message) {
        String eventType = message.getEventType();
        log.info("处理通知事件: eventType={}, messageId={}", eventType, message.getMessageId());

        Object payload = message.getPayload();
        if (payload instanceof Map) {
            Map<String, Object> data = (Map<String, Object>) payload;
            String type = (String) data.getOrDefault("type", eventType);
            Long targetUserId = data.containsKey("targetUserId") ? ((Number) data.get("targetUserId")).longValue() : null;

            WsMessage wsMessage = new WsMessage(type, data, java.time.LocalDateTime.now().toString());

            if (targetUserId != null) {
                sessionManager.sendToUser(targetUserId, wsMessage);
            } else {
                sessionManager.broadcast(wsMessage);
            }
        }
    }
}
