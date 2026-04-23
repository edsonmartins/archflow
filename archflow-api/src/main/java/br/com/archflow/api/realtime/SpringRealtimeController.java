package br.com.archflow.api.realtime;

import br.com.archflow.langchain4j.realtime.spi.RealtimeAdapter;
import br.com.archflow.langchain4j.realtime.spi.RealtimeException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * Spring-specific realtime controller that adapts one frontend WebSocket
 * connection to one {@link br.com.archflow.langchain4j.realtime.spi.RealtimeSession}.
 */
public class SpringRealtimeController implements RealtimeController {

    private final RealtimeAdapter realtimeAdapter;

    public SpringRealtimeController(RealtimeAdapter realtimeAdapter) {
        this.realtimeAdapter = realtimeAdapter;
    }

    @Override
    public WebSocketHandler handleUpgrade(String tenantId, String personaId) {
        return new TextWebSocketHandler() {
            private RealtimeSessionBridge bridge;

            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                try {
                    bridge = new RealtimeSessionBridge(realtimeAdapter.openSession(tenantId, personaId));
                    bridge.outbound(frame -> send(session, frame));
                } catch (RealtimeException e) {
                    send(session, "{\"type\":\"error\",\"data\":{\"message\":\"" + escape(e.getMessage()) + "\"}}");
                    session.close();
                }
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                if (bridge != null) {
                    bridge.onClientFrame(message.getPayload());
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
                if (bridge != null) {
                    bridge.close();
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                if (bridge != null) {
                    bridge.close();
                }
                if (session.isOpen()) {
                    session.close();
                }
            }
        };
    }

    private static void send(WebSocketSession session, String frame) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(frame));
                }
            }
        } catch (IOException ignored) {
            // Best-effort delivery; disconnect handling will reconcile.
        }
    }

    private static String escape(String message) {
        return message == null ? "Realtime error" : message.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
