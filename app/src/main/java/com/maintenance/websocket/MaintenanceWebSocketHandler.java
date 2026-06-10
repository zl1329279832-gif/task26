package com.maintenance.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.entity.Fault;
import com.maintenance.service.TechnicianService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class MaintenanceWebSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TechnicianService technicianService;

    public MaintenanceWebSocketHandler(TechnicianService technicianService) {
        this.technicianService = technicianService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String technicianId = extractTechnicianId(session);
        if (technicianId != null) {
            sessions.put(technicianId, session);
            log.info("WebSocket连接建立, technicianId={}, sessionId={}", technicianId, session.getId());

            Map<String, Object> welcomeMsg = new HashMap<>();
            welcomeMsg.put("type", "CONNECTED");
            welcomeMsg.put("message", "连接成功");
            welcomeMsg.put("timestamp", System.currentTimeMillis());

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(welcomeMsg)));
        } else {
            log.warn("WebSocket连接缺少technicianId参数, sessionId={}", session.getId());
            session.close(CloseStatus.BAD_DATA);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String technicianId = extractTechnicianId(session);
        if (technicianId != null) {
            sessions.remove(technicianId);
            log.info("WebSocket连接关闭, technicianId={}, sessionId={}, status={}", technicianId, session.getId(), status);

            try {
                technicianService.updateAvailability(Long.parseLong(technicianId), "OFFLINE");
                log.info("技术员状态已更新为OFFLINE, technicianId={}", technicianId);
            } catch (Exception e) {
                log.error("更新技术员离线状态失败, technicianId={}", technicianId, e);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到WebSocket消息, sessionId={}, payload={}", session.getId(), payload);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msgMap = objectMapper.readValue(payload, Map.class);
            String type = (String) msgMap.get("type");

            if ("PING".equals(type)) {
                Map<String, Object> pongMsg = new HashMap<>();
                pongMsg.put("type", "PONG");
                pongMsg.put("timestamp", System.currentTimeMillis());
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pongMsg)));
                log.debug("回复心跳PONG, sessionId={}", session.getId());

            } else if ("STATUS".equals(type)) {
                String availability = (String) msgMap.get("availability");
                String technicianId = extractTechnicianId(session);
                if (technicianId != null && availability != null) {
                    technicianService.updateAvailability(Long.parseLong(technicianId), availability);
                    log.info("技术员状态更新, technicianId={}, availability={}", technicianId, availability);

                    Map<String, Object> ackMsg = new HashMap<>();
                    ackMsg.put("type", "STATUS_UPDATED");
                    ackMsg.put("availability", availability);
                    ackMsg.put("timestamp", System.currentTimeMillis());
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ackMsg)));
                }
            } else {
                log.warn("未知消息类型, type={}, sessionId={}", type, session.getId());
            }
        } catch (Exception e) {
            log.error("处理WebSocket消息异常, sessionId={}", session.getId(), e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误, sessionId={}, error={}", session.getId(), exception.getMessage());
        if (session.isOpen()) {
            session.close();
        }
        String technicianId = extractTechnicianId(session);
        if (technicianId != null) {
            sessions.remove(technicianId);
        }
    }

    public void sendToTechnician(Long technicianId, String messageType, Object data) {
        String key = String.valueOf(technicianId);
        WebSocketSession session = sessions.get(key);

        if (session == null || !session.isOpen()) {
            log.warn("维修人员离线, 无法发送WebSocket消息, technicianId={}, messageType={}", technicianId, messageType);
            return;
        }

        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", messageType);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            log.info("WebSocket消息已发送, technicianId={}, messageType={}", technicianId, messageType);
        } catch (IOException e) {
            log.error("发送WebSocket消息失败, technicianId={}, messageType={}", technicianId, messageType, e);
        }
    }

    public void broadcast(String messageType, Object data) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", messageType);
        message.put("data", data);
        message.put("timestamp", System.currentTimeMillis());

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("序列化广播消息失败, messageType={}", messageType, e);
            return;
        }

        TextMessage textMessage = new TextMessage(json);
        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                    successCount++;
                } catch (IOException e) {
                    log.error("广播消息发送失败, technicianId={}", entry.getKey(), e);
                    failCount++;
                }
            } else {
                failCount++;
            }
        }

        log.info("广播消息完成, messageType={}, success={}, fail={}", messageType, successCount, failCount);
    }

    public void sendEmergencyAlert(Fault fault, String alertMessage) {
        Map<String, Object> alertData = new HashMap<>();
        alertData.put("fault", fault);
        alertData.put("message", alertMessage);
        alertData.put("faultLevel", fault.getFaultLevel());

        log.warn("发送紧急故障提醒广播, faultId={}, faultLevel={}", fault.getId(), fault.getFaultLevel());
        broadcast("EMERGENCY_ALERT", alertData);
    }

    public boolean isOnline(Long technicianId) {
        String key = String.valueOf(technicianId);
        WebSocketSession session = sessions.get(key);
        return session != null && session.isOpen();
    }

    private String extractTechnicianId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String query = session.getUri().getQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if ("technicianId".equals(pair[0]) && pair.length == 2) {
                return pair[1];
            }
        }
        return null;
    }
}
