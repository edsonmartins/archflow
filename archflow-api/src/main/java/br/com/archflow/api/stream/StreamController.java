package br.com.archflow.api.stream;

/**
 * Controller para streaming SSE por tenant/session.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/stream/{tenantId}/{sessionId} — Abre um stream SSE
 *       para receber eventos em tempo real. Linktor, dashboard e outros
 *       clientes subscrevem aqui.</li>
 *   <li>GET /api/stream/{sessionId} — o mesmo, no tenant da própria
 *       requisição (resolvido pelo servidor).</li>
 * </ul>
 *
 * <p>O stream é isolado por tenant — eventos de um tenant não vazam
 * para subscribers de outro. Isso vale também do lado de quem assina: um
 * {@code tenantId} diferente do tenant autenticado é recusado com 403
 * (RCHFLW-49); a personificação verificada de um superadmin é o único
 * caminho para o canal de outro tenant.
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
     * @return Objeto SSE do framework (SseEmitter, Flux, etc.), ou a recusa (403)
     *         quando o tenant não é o da requisição
     */
    Object stream(String tenantId, String sessionId);
}
