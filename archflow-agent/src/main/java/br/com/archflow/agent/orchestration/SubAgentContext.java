package br.com.archflow.agent.orchestration;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ImmutableExecutionContext;
import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recorta o {@link ExecutionContext} que um sub-agente recebe.
 *
 * <p>O {@link CatalogAgentWorker} repassava o contexto do fluxo <b>inteiro</b> a
 * cada sub-agente: toda variável, a saída de todo step anterior, a proposta de
 * aprovação pendente e a conversa acumulada. Um supervisor restrito continuava
 * vazando o conjunto de trabalho completo por delegação — a restrição de
 * catálogo impedia o sub-agente de <em>chamar</em> o que não devia, mas não de
 * <em>ler</em> o que não devia.
 *
 * <h2>O que sai por default</h2>
 * As variáveis do namespace {@value #INTERNAL_PREFIX} — estado do próprio motor
 * (proposta e payload de aprovação, decisor, checkpoint de steps concluídos,
 * conversa serializada). Nenhum agente pode depender delas legitimamente: o
 * prefixo existe justamente para marcar "isto é interno". Variáveis de domínio
 * (ex.: {@code research_data}) continuam passando, porque agentes reais as leem.
 *
 * <h2>Memória</h2>
 * O sub-agente recebe uma {@link MessageWindowChatMemory} <b>nova e vazia</b>.
 * Compartilhar a do fluxo vazaria nos dois sentidos: ele leria a conversa
 * inteira e os turnos dele poluiriam o diálogo principal. Uma subtarefa é um
 * trabalho discreto, não a continuação de uma conversa.
 *
 * <h2>Escrita</h2>
 * O contexto é imutável. O contrato do worker devolve o resultado pelo
 * {@code Result}, não por mutação de contexto — então nada se perde, e um
 * sub-agente deixa de conseguir alterar o estado do fluxo que o chamou.
 */
public final class SubAgentContext {

    /** Prefixo das variáveis que pertencem ao motor, não ao domínio. */
    public static final String INTERNAL_PREFIX = "__archflow.";

    private static final int SUB_AGENT_MEMORY_WINDOW = 20;

    private SubAgentContext() {
    }

    /** Recorte default: sem as variáveis internas do motor, com memória própria. */
    public static ExecutionContext of(ExecutionContext parent) {
        return of(parent, null);
    }

    /**
     * @param allowedKeys quando informado, <b>só</b> estas variáveis passam
     *                    (allowlist fechada). {@code null} aplica o recorte
     *                    default; lista vazia entrega o sub-agente sem variável
     *                    nenhuma.
     */
    public static ExecutionContext of(ExecutionContext parent, Collection<String> allowedKeys) {
        if (parent == null) {
            return null;
        }
        Map<String, Object> variables = filterVariables(parent.getVariables(), allowedKeys);
        return new ImmutableExecutionContext(
                parent.getTenantId() != null ? parent.getTenantId() : "SYSTEM",
                parent.getUserId(),
                parent.getSessionId(),
                parent.getRequestId(),
                MessageWindowChatMemory.builder().maxMessages(SUB_AGENT_MEMORY_WINDOW).build(),
                // O estado vai com as MESMAS variáveis filtradas: deixar o
                // original passaria por getState().getVariables() exatamente o
                // que acabou de ser retirado.
                narrowState(parent.getState(), variables),
                variables,
                parent.getMetrics());
    }

    private static Map<String, Object> filterVariables(Map<String, Object> source,
                                                       Collection<String> allowedKeys) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (allowedKeys != null) {
            Set<String> allowed = allowedKeys.stream()
                    .filter(k -> k != null && !k.isBlank())
                    .map(k -> k.trim().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            Map<String, Object> narrowed = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (key != null && allowed.contains(key.trim().toLowerCase(Locale.ROOT))) {
                    narrowed.put(key, value);
                }
            });
            return Map.copyOf(narrowed);
        }
        Map<String, Object> narrowed = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !key.startsWith(INTERNAL_PREFIX)) {
                narrowed.put(key, value);
            }
        });
        return Map.copyOf(narrowed);
    }

    private static FlowState narrowState(FlowState state, Map<String, Object> variables) {
        if (state == null) {
            return null;
        }
        return FlowState.builder()
                .tenantId(state.getTenantId())
                .flowId(state.getFlowId())
                .status(state.getStatus())
                .currentStepId(state.getCurrentStepId())
                .variables(variables)
                .metrics(state.getMetrics())
                .build();
    }
}
