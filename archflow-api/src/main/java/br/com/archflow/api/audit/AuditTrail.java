package br.com.archflow.api.audit;

import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Produtor central de eventos de auditoria da API (fase 5.6 do plano de
 * homologação): controllers chamam {@link #record} nos pontos críticos
 * (login, CRUD/execute de workflow, chaves de API, config admin, tenants)
 * e o evento vai para o {@link AuditRepository} configurado.
 *
 * <p>Contrato de robustez:
 * <ul>
 *   <li><b>no-op sem repositório</b> — o {@code AuditRepository} só existe
 *       quando {@code archflow.persistence.jdbc.enabled=true}; em dev o
 *       evento é simplesmente descartado (DEBUG);</li>
 *   <li><b>nunca lança</b> — falha de auditoria não pode derrubar a
 *       operação de negócio; erros são logados como WARN.</li>
 * </ul>
 *
 * <p>Quando o chamador não informa o ator explicitamente, o userId/username
 * são resolvidos dos request attributes populados pelo
 * {@link JwtAuthenticationFilter}; o tenant vem de
 * {@link TenantContext#currentTenantId()} e entra no contexto do evento sob a
 * chave {@code tenant}.
 */
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    /** Chave do tenant dentro de {@link AuditEvent#getContext()}. */
    public static final String CONTEXT_TENANT = "tenant";

    private final Supplier<AuditRepository> repository;

    /**
     * @param repository fornecedor do repositório; pode retornar {@code null}
     *                   (auditoria vira no-op). Avaliado a cada evento para
     *                   respeitar wiring tardio via {@code ObjectProvider}.
     */
    public AuditTrail(Supplier<AuditRepository> repository) {
        this.repository = repository != null ? repository : () -> null;
    }

    /** Instância inerte — default seguro para construtores legados e testes. */
    public static AuditTrail noop() {
        return new AuditTrail(() -> null);
    }

    /**
     * Registra um evento resolvendo o ator dos request attributes atuais.
     *
     * @param action       ação auditada (obrigatória)
     * @param resourceType tipo do recurso (ex.: {@code workflow}, {@code apikey})
     * @param resourceId   identificador do recurso (pode ser {@code null})
     * @param success      resultado da operação
     * @param errorMessage mensagem de erro quando {@code success=false} (opcional)
     * @param details      contexto adicional (opcional)
     */
    public void record(AuditAction action, String resourceType, String resourceId,
                       boolean success, String errorMessage, Map<String, String> details) {
        String[] actor = currentActor();
        record(actor[0], actor[1], action, resourceType, resourceId, success, errorMessage, details);
    }

    /**
     * Registra um evento com ator explícito (login, endpoints que recebem o
     * userId por header). Nunca lança.
     */
    public void record(String userId, String username, AuditAction action, String resourceType,
                       String resourceId, boolean success, String errorMessage,
                       Map<String, String> details) {
        try {
            AuditRepository repo = repository.get();
            if (repo == null) {
                log.debug("Audit repository absent; dropping audit event {} on {}/{}",
                        action, resourceType, resourceId);
                return;
            }
            Map<String, String> context = new HashMap<>();
            context.put(CONTEXT_TENANT, TenantContext.currentTenantId());
            if (details != null) {
                details.forEach((k, v) -> {
                    if (k != null && v != null) {
                        context.put(k, v);
                    }
                });
            }
            AuditEvent.Builder builder = AuditEvent.builder()
                    .action(action)
                    .userId(userId)
                    .username(username)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .context(context);
            if (errorMessage != null) {
                builder.errorMessage(errorMessage);
            }
            // success por último: errorMessage() recalcula o flag e o valor
            // explícito do chamador deve prevalecer.
            builder.success(success);
            repo.save(builder.build());
        } catch (RuntimeException e) {
            log.warn("Failed to record audit event {} on {}/{}: {}",
                    action, resourceType, resourceId, e.toString());
        }
    }

    /** {userId, username} do request atual; {null, null} fora de um request. */
    private static String[] currentActor() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                Object userId = sra.getRequest().getAttribute(JwtAuthenticationFilter.ATTR_USER_ID);
                Object username = sra.getRequest().getAttribute(JwtAuthenticationFilter.ATTR_USERNAME);
                return new String[] {
                        userId instanceof String s && !s.isBlank() ? s : null,
                        username instanceof String s && !s.isBlank() ? s : null
                };
            }
        } catch (RuntimeException e) {
            log.debug("Could not resolve audit actor from request context: {}", e.toString());
        }
        return new String[] {null, null};
    }
}
