package br.com.archflow.api.realtime;

import br.com.archflow.langchain4j.realtime.spi.RealtimeAdapter;
import br.com.archflow.langchain4j.realtime.spi.RealtimeException;
import br.com.archflow.langchain4j.realtime.spi.RealtimeMessage;
import br.com.archflow.langchain4j.realtime.spi.RealtimeSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Deterministic in-memory realtime adapter used by the local Spring API.
 *
 * <p>It exists to provide a stable WebSocket target for frontend and E2E
 * validation without depending on provider credentials or external
 * network access. The session emits a ready event on connect and, once it
 * receives the first audio frame, returns a small transcript exchange
 * tailored to the selected persona.
 */
public class DevRealtimeAdapter implements RealtimeAdapter {

    @Override
    public String providerId() {
        return "dev";
    }

    @Override
    public void configure(Map<String, Object> properties) {
        // No-op: the dev adapter has no external configuration.
    }

    @Override
    public RealtimeSession openSession(String tenantId, String personaId) throws RealtimeException {
        return new DevRealtimeSession(tenantId, personaId);
    }

    @Override
    public void shutdown() {
        // No shared resources to release.
    }

    private static final class DevRealtimeSession implements RealtimeSession {
        private final String sessionId = "rt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        private final String tenantId;
        private final String personaId;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean bootstrapped = new AtomicBoolean(false);
        private volatile Consumer<RealtimeMessage> messageListener = message -> {};
        private volatile Consumer<RealtimeSessionStatus> statusListener = status -> {};

        private DevRealtimeSession(String tenantId, String personaId) {
            this.tenantId = tenantId;
            this.personaId = personaId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public String tenantId() {
            return tenantId;
        }

        @Override
        public String personaId() {
            return personaId;
        }

        @Override
        public void sendAudio(byte[] pcm16, int sampleRate) {
            if (closed.get()) {
                throw new IllegalStateException("session already closed");
            }
            if (bootstrapped.compareAndSet(false, true)) {
                emit(RealtimeMessage.transcript("user", promptForPersona(personaId), true));
                emit(RealtimeMessage.transcript("agent", replyForPersona(personaId, tenantId), true));
                emit(RealtimeMessage.agentDone());
            }
        }

        @Override
        public void sendText(String text) {
            if (closed.get()) {
                throw new IllegalStateException("session already closed");
            }
            emit(RealtimeMessage.transcript("user", text, true));
            emit(RealtimeMessage.transcript("agent", "Dev realtime received: " + text, true));
            emit(RealtimeMessage.agentDone());
        }

        @Override
        public void onMessage(Consumer<RealtimeMessage> listener) {
            this.messageListener = listener != null ? listener : message -> {};
            emit(RealtimeMessage.ready(sessionId));
        }

        @Override
        public void onStatus(Consumer<RealtimeSessionStatus> listener) {
            this.statusListener = listener != null ? listener : status -> {};
            this.statusListener.accept(RealtimeSessionStatus.OPEN);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                statusListener.accept(RealtimeSessionStatus.CLOSED);
            }
        }

        private void emit(RealtimeMessage message) {
            if (!closed.get()) {
                messageListener.accept(message);
            }
        }

        private static String promptForPersona(String personaId) {
            return switch (personaId) {
                case "order_tracking" -> "quero rastrear meu pedido";
                case "complaint" -> "preciso registrar uma reclamacao";
                case "sales" -> "quero conhecer os planos disponiveis";
                default -> "ola archflow";
            };
        }

        private static String replyForPersona(String personaId, String tenantId) {
            return switch (personaId) {
                case "order_tracking" -> "Pedido localizado para o tenant " + tenantId + ". Status: em transito.";
                case "complaint" -> "Reclamacao recebida. Vou abrir um atendimento para " + tenantId + ".";
                case "sales" -> "Posso apresentar os planos Professional e Enterprise para " + tenantId + ".";
                default -> "Sessao realtime dev ativa para " + tenantId + ".";
            };
        }
    }
}
