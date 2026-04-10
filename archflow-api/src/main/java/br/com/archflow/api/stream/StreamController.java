package br.com.archflow.api.stream;

/**
 * Controller para streaming SSE por tenant/session.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /archflow/stream/{tenantId}/{sessionId} — Abre um stream SSE
 *       para receber eventos em tempo real. Linktor, dashboard e outros
 *       clientes subscrevem aqui.</li>
 * </ul>
 *
 * <p>O stream é isolado por tenant — eventos de um tenant não vazam
 * para subscribers de outro.
 *
 * <p>Implementações concretas devem retornar o tipo SSE do framework
 * utilizado (ex: SseEmitter no Spring Boot, Flux no WebFlux).
 */
public interface StreamController {

    /**
     * Abre um stream SSE para o tenant/session especificados.
     *
     * @param tenantId  ID do tenant
     * @param sessionId ID da sessão
     * @return Objeto SSE do framework (SseEmitter, Flux, etc.)
     */
    Object stream(String tenantId, String sessionId);
}
