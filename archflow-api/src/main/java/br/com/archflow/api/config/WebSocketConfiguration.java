package br.com.archflow.api.config;

import br.com.archflow.api.realtime.SpringRealtimeController;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registers the realtime voice WebSocket endpoint used by the frontend.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebSocketConfiguration.class);

    /** Strict match on the registered pattern; rejects trailing slashes and extra segments. */
    private static final Pattern REALTIME_PATH = Pattern.compile(
            "^/api/realtime/([^/]+)/([^/]+)$");

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
                try {
                    WebSocketHandler delegate = realtimeController.handleUpgrade(
                            path(session, "tenantId"), path(session, "personaId"));
                    session.getAttributes().put(SpringRealtimeController.class.getName(), delegate);
                    delegate.afterConnectionEstablished(session);
                } catch (Exception e) {
                    log.warn("Realtime handler initialization failed; closing session {}",
                            session.getId(), e);
                    try {
                        session.close(CloseStatus.SERVER_ERROR
                                .withReason(trimCloseReason(e.getMessage())));
                    } catch (Exception closeErr) {
                        log.debug("Secondary error closing failed session", closeErr);
                    }
                    throw e;
                }
            }

            @Override
            protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                             org.springframework.web.socket.TextMessage message) throws Exception {
                handler(session).handleMessage(session, message);
            }

            @Override
            public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session,
                                              org.springframework.web.socket.CloseStatus status) throws Exception {
                // If afterConnectionEstablished failed, no delegate was stored.
                // Silently skip cleanup in that case — nothing to release.
                Object delegate = session.getAttributes().get(SpringRealtimeController.class.getName());
                if (delegate instanceof WebSocketHandler webSocketHandler) {
                    webSocketHandler.afterConnectionClosed(session, status);
                }
            }

            @Override
            public void handleTransportError(org.springframework.web.socket.WebSocketSession session,
                                             Throwable exception) throws Exception {
                Object delegate = session.getAttributes().get(SpringRealtimeController.class.getName());
                if (delegate instanceof WebSocketHandler webSocketHandler) {
                    webSocketHandler.handleTransportError(session, exception);
                } else {
                    log.debug("Transport error on session {} with no delegate yet",
                            session.getId(), exception);
                }
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
                String path = request.getURI().getPath();
                Matcher m = REALTIME_PATH.matcher(path);
                if (!m.matches()) {
                    log.warn("Rejecting realtime handshake with unexpected path: {}", path);
                    response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
                    return false;
                }
                String tenantId = m.group(1);
                String personaId = m.group(2);
                if (tenantId.isBlank() || personaId.isBlank()) {
                    log.warn("Rejecting realtime handshake with blank tenant/persona: {}", path);
                    response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
                    return false;
                }
                attributes.put("tenantId", tenantId);
                attributes.put("personaId", personaId);
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
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalStateException("missing path variable: " + name);
    }

    /** CloseStatus reasons are capped at ~123 bytes; truncate defensively. */
    private static String trimCloseReason(String raw) {
        if (raw == null) return "";
        return raw.length() > 100 ? raw.substring(0, 100) : raw;
    }
}
