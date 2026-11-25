package br.com.archflow.api.flow.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.ChatMemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * O contexto da execução como um adapter de chat o vê através da ponte: o mesmo, menos as chaves
 * que mudariam o modelo, a chave ou o destino da chamada.
 *
 * <p>Toda chave da entrada de uma execução vira variável do contexto ({@code RunInputSeeding}), e o
 * adapter do OpenRouter honra {@link ExecutionKeys#LLM_MODEL} como override do modelo (e
 * {@link ExecutionKeys#LLM_RESOLVED_CONFIG}, com {@code apiKey}/{@code baseUrl} próprios). Visto
 * pelo adapter, quem chamou {@code POST /api/workflows/{id}/execute} com {@code {"llm.model": "x"}}
 * escolhia qualquer modelo para todo nó de chat, pago com a chave do tenant ou da plataforma — e o
 * {@code LLM_RESOLVED_CONFIG} levaria a chave governada a um {@code baseUrl} que a
 * {@code BaseUrlPolicy} nunca viu. Num nó de chat do fluxo, provider/modelo são do documento e da
 * herança ({@link ChatNodeDefaults}), a chave é do resolver e o destino é da política; nenhum deles
 * vem de uma variável.
 *
 * <p>Esconde só a leitura. Escrita, memória, estado e métricas são os do contexto original — o
 * adapter continua gravando na mesma execução.
 */
final class ChatNodeContext implements ExecutionContext {

    /** Chaves que nenhum adapter de chat lê através da ponte. */
    static final Set<String> HIDDEN = Set.of(ExecutionKeys.LLM_MODEL, ExecutionKeys.LLM_RESOLVED_CONFIG);

    private final ExecutionContext delegate;

    private ChatNodeContext(ExecutionContext delegate) {
        this.delegate = delegate;
    }

    /** A visão filtrada de {@code context}; {@code null} continua {@code null}. */
    static ExecutionContext of(ExecutionContext context) {
        if (context == null || context instanceof ChatNodeContext) {
            return context;
        }
        return new ChatNodeContext(context);
    }

    @Override
    public Optional<Object> get(String key) {
        return HIDDEN.contains(key) ? Optional.empty() : delegate.get(key);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void set(String key, Object value) {
        delegate.set(key, value);
    }

    @Override
    public ChatMemory getChatMemory() {
        return delegate.getChatMemory();
    }

    @Override
    public ExecutionMetrics getMetrics() {
        return delegate.getMetrics();
    }

    @Override
    public FlowState getState() {
        return delegate.getState();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setState(FlowState state) {
        delegate.setState(state);
    }

    @Override
    public String getTenantId() {
        return delegate.getTenantId();
    }

    @Override
    public String getUserId() {
        return delegate.getUserId();
    }

    @Override
    public String getSessionId() {
        return delegate.getSessionId();
    }

    @Override
    public String getRequestId() {
        return delegate.getRequestId();
    }

    @Override
    public Map<String, Object> getVariables() {
        Map<String, Object> variables = new LinkedHashMap<>(delegate.getVariables());
        variables.keySet().removeAll(HIDDEN);
        return Collections.unmodifiableMap(variables);
    }

    @Override
    public ExecutionContext snapshot() {
        return of(delegate.snapshot());
    }
}
