package br.com.archflow.api.config;

import br.com.archflow.api.realtime.SpringRealtimeController;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the realtime voice WebSocket endpoint used by the frontend.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final SpringRealtimeController realtimeController;

    public WebSocketConfiguration(SpringRealtimeController realtimeController) {
        this.realtimeController = realtimeController;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(delegatingHandler(), "/api/realtime/{tenantId}/{personaId}")
                .addInterceptors(pathVariablesInterceptor())
                .setAllowedOriginPatterns("*");
    }

    private WebSocketHandler delegatingHandler() {
        return new org.springframework.web.socket.handler.AbstractWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session) throws Exception {
                WebSocketHandler delegate = realtimeController.handleUpgrade(path(session, "tenantId"), path(session, "personaId"));
                session.getAttributes().put(SpringRealtimeController.class.getName(), delegate);
                delegate.afterConnectionEstablished(session);
            }

            @Override
            protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                             org.springframework.web.socket.TextMessage message) throws Exception {
                handler(session).handleMessage(session, message);
            }

            @Override
            public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session,
                                              org.springframework.web.socket.CloseStatus status) throws Exception {
                handler(session).afterConnectionClosed(session, status);
            }

            @Override
            public void handleTransportError(org.springframework.web.socket.WebSocketSession session,
                                             Throwable exception) throws Exception {
                handler(session).handleTransportError(session, exception);
            }
        };
    }

    private static HandshakeInterceptor pathVariablesInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                           org.springframework.http.server.ServerHttpResponse response,
                                           WebSocketHandler wsHandler,
                                           java.util.Map<String, Object> attributes) {
                String[] parts = request.getURI().getPath().split("/");
                if (parts.length >= 5) {
                    attributes.put("tenantId", parts[parts.length - 2]);
                    attributes.put("personaId", parts[parts.length - 1]);
                }
                return true;
            }

            @Override
            public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                       org.springframework.http.server.ServerHttpResponse response,
                                       WebSocketHandler wsHandler,
                                       Exception exception) {
                // No-op.
            }
        };
    }

    private static WebSocketHandler handler(org.springframework.web.socket.WebSocketSession session) {
        Object handler = session.getAttributes().get(SpringRealtimeController.class.getName());
        if (handler instanceof WebSocketHandler webSocketHandler) {
            return webSocketHandler;
        }
        throw new IllegalStateException("missing realtime delegate");
    }

    private static String path(org.springframework.web.socket.WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalStateException("missing path variable: " + name);
    }
}
