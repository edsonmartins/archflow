package br.com.archflow.api.decision;

import br.com.archflow.decision.DecisionCall;
import br.com.archflow.decision.DecisionException;
import br.com.archflow.decision.DecisionProvider;
import br.com.archflow.decision.DecisionRequest;
import br.com.archflow.decision.DecisionResult;
import br.com.archflow.decision.HttpDecisionProvider;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.LLMProvider;
import br.com.archflow.langchain4j.provider.TenantModelPolicy;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Embrulha um provedor de decisão que aceita destino nas opções do nó ({@code endpoint} no
 * {@code http-decisions}, {@code baseUrl} no {@code langchain4j-decisions}).
 *
 * <p>A chave do nó de decisão vem sempre da governança — {@code DecisionKeys}: tenant, depois
 * {@code archflow.decision.keys.<ref>}, depois a da plataforma — nunca do documento. O destino, porém,
 * vinha das {@code options} do nó sem validação, e o provedor mandava {@code Authorization: Bearer}
 * para ele. Era o caminho ativo do RCHFLW-42: bastava a chave de plataforma de decisão configurada
 * para qualquer editor de fluxo, de qualquer tenant, apontar o {@code endpoint} para si e recebê-la.
 *
 * <p>A guarda fica no hospedeiro, e não no {@code archflow-decision}, porque a lista de destinos é do
 * deployment ({@link BaseUrlPolicy}) e aquele módulo não depende do hub. A recusa é uma
 * {@link DecisionException} não repetível: a rota escala (ou o {@code fallbacks} é tentado), e
 * nenhuma requisição sai.
 *
 * <p>A mesma guarda aplica os modelos permitidos do tenant ({@link TenantModelPolicy}, RCHFLW-79). Os
 * dois provedores mandam o {@code model} do nó (ou do degrau de {@code cascade}/{@code consensus}) pago
 * com a chave governada; sem esta conferência, um tenant restrito escolhia qualquer modelo escrevendo
 * um nó de decisão — a restrição valia para chat e agentes e se contornava por aqui. O provedor
 * {@code llm} não precisa dela: passa pelo {@code DefaultLLMConfigResolver}, que já confere.
 */
public final class DecisionEndpointGuard implements DecisionProvider {

    /** As opções de destino que os provedores embutidos leem. */
    static final List<String> URL_OPTIONS = List.of("endpoint", "baseUrl");

    /**
     * Endereços oficiais de referências de chave que não são providers de LLM. O {@code typesafe} é
     * o padrão do {@code langchain4j-decisions} (o {@code TypeSafeDecisionModel} fala com
     * {@code api.typesafe.ai} quando não há {@code baseUrl}); sem isto a referência não teria "oficial"
     * nenhum e toda configuração com {@code baseUrl} seria recusada.
     */
    static final Map<String, String> OFICIAIS_DE_DECISAO = Map.of("typesafe", "https://api.typesafe.ai");

    private final DecisionProvider delegate;
    private final BaseUrlPolicy policy;
    private final TenantModelPolicy models;

    /** Só a guarda de destino, sem restrição de modelo (testes, hosts sem cadastro de tenants). */
    public DecisionEndpointGuard(DecisionProvider delegate, BaseUrlPolicy policy) {
        this(delegate, policy, TenantModelPolicy.UNRESTRICTED);
    }

    public DecisionEndpointGuard(DecisionProvider delegate, BaseUrlPolicy policy, TenantModelPolicy models) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = policy != null ? policy : BaseUrlPolicy.officialOnly();
        this.models = models != null ? models : TenantModelPolicy.UNRESTRICTED;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public Optional<String> keyRef(Map<String, Object> options) {
        return delegate.keyRef(options);
    }

    @Override
    public boolean requiresKey() {
        return delegate.requiresKey();
    }

    @Override
    public DecisionResult decide(DecisionRequest request, DecisionCall call) throws DecisionException {
        // A referência da chave diz de qual fornecedor ela é — é o oficial DELE que vale.
        String dono = delegate.keyRef(call.options()).orElse(delegate.id());
        try {
            // Antes do destino e com ou sem chave: o tenant é o da execução (call.tenantId()), e o
            // modelo é o que vai sair — "nenhum tenant" e lista vazia não restringem.
            models.check(call.tenantId(), dono, call.model());
        } catch (IllegalStateException e) {
            throw new DecisionException(e.getMessage(), false);
        }
        if (call.apiKey() != null && !call.apiKey().isBlank()) {
            for (String campo : URL_OPTIONS) {
                if (call.options().get(campo) instanceof String url && !url.isBlank()
                        && !permitido(dono, url)) {
                    throw new DecisionException(BaseUrlPolicy.message(dono, url, "options." + campo), false);
                }
            }
        }
        return delegate.decide(request, call);
    }

    /**
     * Uma referência que é provider de LLM ({@code openrouter}, {@code openai}) só vai ao oficial dele
     * ou à lista do operador — a chave da OpenAI não vai para o OpenRouter. Uma referência que não é
     * provider ({@code typesafe}, ou uma do operador) não tem oficial no {@link LLMProvider}: vale o
     * oficial de decisão dela ({@link #OFICIAIS_DE_DECISAO}) e o OpenRouter, que serve os dois
     * protocolos de decisão e é a configuração documentada ({@code backend: typesafe},
     * {@code baseUrl: https://openrouter.ai/api}). Nenhum dos dois é um host que um editor de fluxo
     * controle — o vazamento do RCHFLW-42 era para um servidor do próprio editor.
     */
    private boolean permitido(String dono, String url) {
        if (policy.isAllowed(dono, url)) {
            return true;
        }
        if (dono == null || LLMProvider.fromId(dono).isPresent()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String oficial = OFICIAIS_DE_DECISAO.get(dono.toLowerCase(Locale.ROOT));
        return (oficial != null && BaseUrlPolicy.of(List.of(oficial)).isAllowed(dono, url))
                || HttpDecisionProvider.ehOpenRouter(uri);
    }
}
