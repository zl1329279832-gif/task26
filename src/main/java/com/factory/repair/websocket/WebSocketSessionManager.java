package com.factory.repair.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class WebSocketSessionManager {

    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> allSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void register(Long userId, WebSocketSession session) {
        userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
        allSessions.put(session.getId(), session);
        log.info("WebSocket连接注册: userId={}, sessionId={}, 当前连接数={}", userId, session.getId(), allSessions.size());
    }

    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        }
        allSessions.remove(session.getId());
        log.info("WebSocket连接注销: userId={}, sessionId={}", userId, session.getId());
    }

    public void unregisterBySession(WebSocketSession session) {
        allSessions.remove(session.getId());
        userSessions.forEach((userId, sessions) -> {
            if (sessions.remove(session) && sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        });
    }

    public void sendToUser(Long userId, WsMessage message) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("用户不在线: userId={}", userId);
            return;
        }
        String json = toJson(message);
        for (WebSocketSession session : sessions) {
            sendText(session, json);
        }
    }

    public void broadcast(WsMessage message) {
        String json = toJson(message);
        for (WebSocketSession session : allSessions.values()) {
            sendText(session, json);
        }
    }

    public int getOnlineCount() {
        return allSessions.size();
    }

    private void sendText(WebSocketSession session, String text) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(text));
            } catch (IOException e) {
                log.error("WebSocket消息发送失败: sessionId={}", session.getId(), e);
            }
        }
    }

    private String toJson(WsMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("WsMessage序列化失败", e);
            return "{}";
        }
    }
}
