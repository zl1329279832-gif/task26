package com.maintenance.config;

import com.maintenance.websocket.MaintenanceWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired(required = false)
    private MaintenanceWebSocketHandler maintenanceWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (maintenanceWebSocketHandler != null) {
            registry.addHandler(maintenanceWebSocketHandler, "/ws/maintenance")
                    .setAllowedOrigins("*");
        }
    }
}
