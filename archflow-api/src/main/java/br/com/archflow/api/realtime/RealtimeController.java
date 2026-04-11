package br.com.archflow.api.realtime;

/**
 * Controller contract for the realtime voice WebSocket.
 *
 * <p>The frontend connects to {@code /archflow/realtime/{tenantId}/{personaId}}
 * and exchanges bidirectional JSON frames with the chosen provider
 * (OpenAI Realtime, Gemini Live, etc). This interface is framework-
 * agnostic — a Spring WebFlux/WebSocket implementation lives in the
 * Spring Boot bootstrap module and delegates to a
 * {@link br.com.archflow.langchain4j.realtime.spi.RealtimeAdapter}.
 *
 * <h3>Wire protocol</h3>
 *
 * <p><b>Client → Server</b> JSON frames:
 * <pre>
 *   { "type": "audio", "data": { "pcm16": "...base64...", "sampleRate": 24000 } }
 *   { "type": "text",  "data": { "text": "..." } }
 *   { "type": "stop" }
 * </pre>
 *
 * <p><b>Server → Client</b> JSON frames mirror the
 * {@link br.com.archflow.langchain4j.realtime.spi.RealtimeMessage} envelope:
 * <pre>
 *   { "type": "ready",       "data": { "sessionId": "rt_..." } }
 *   { "type": "transcript",  "data": { "speaker": "user|agent", "text": "...", "final": bool } }
 *   { "type": "audio",       "data": { "pcm16": "...base64...", "sampleRate": 24000 } }
 *   { "type": "agent_done" }
 *   { "type": "error",       "data": { "message": "..." } }
 * </pre>
 *
 * <p>The binding framework is responsible for:
 * <ul>
 *   <li>Authenticating the WebSocket upgrade (JWT / API key).</li>
 *   <li>Resolving the {@code tenantId}/{@code personaId} path params.</li>
 *   <li>Opening a {@code RealtimeSession} via the adapter registry.</li>
 *   <li>Forwarding client frames into {@code sendAudio}/{@code sendText}.</li>
 *   <li>Serializing outbound {@code RealtimeMessage}s back to the socket.</li>
 *   <li>Closing the session on disconnect or stop frame.</li>
 * </ul>
 */
public interface RealtimeController {

    /**
     * Handle a realtime session upgrade. Implementations return the
     * framework-specific WebSocket handler object (e.g.
     * {@code org.springframework.web.socket.WebSocketHandler}).
     *
     * @param tenantId  the authenticated tenant
     * @param personaId the persona selected by the user
     * @return handler object opaque to callers
     */
    Object handleUpgrade(String tenantId, String personaId);
}
