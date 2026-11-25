package br.com.archflow.standalone;

import br.com.archflow.api.decision.DecisionEndpointGuard;
import br.com.archflow.api.flow.CatalogComponents;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.ExecutableNodesValidator;
import br.com.archflow.api.flow.PortableFlowStepFactory;
import br.com.archflow.api.flow.RunInputSeeding;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.decision.HttpDecisionProvider;
import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.validation.DefaultFlowValidator;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.langchain4j.provider.TenantModelPolicy;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.Map;
import java.util.Objects;

/**
 * Transforma o documento de workflow em passos executáveis fora do servidor — no runner standalone e no
 * {@code EmbeddedWorkflowRunner} do SDK (RCHFLW-37).
 *
 * <p><b>O mesmo leitor do servidor.</b> O documento passa pelo {@link DefaultWorkflowDeserializer} e pela
 * {@link PortableFlowStepFactory} do archflow-flow-runtime — o código que o servidor usa —, então um nó vira
 * o mesmo passo aqui e lá. Antes, o runner entregava ao motor o {@code SerializableStep} do transporte, cujo
 * {@code execute} só sabia falhar: todo fluxo terminava FAILED no primeiro passo.</p>
 *
 * <p><b>O que é do servidor não roda aqui, e diz por quê.</b> A fábrica não recebe os passos do servidor
 * (aprovação com fila, orquestração, {@code mcp-agent}, base de conhecimento), e sabe o que mais falta aqui:
 * o nó {@code decision} que cita um provedor que só o servidor registra ({@code llm},
 * {@code langchain4j-decisions}) e o nó de dados (vector store, embedding, memória, RAG) sem adapter — o
 * standalone não traz os adapters de dados. Esses nós viram passos de recusa, e {@link #materialize} falha
 * <i>antes do primeiro passo</i> com "o nó X precisa do servidor archflow (motivo)" — junto com os tipos da
 * paleta que não têm execução. Nenhum passo anterior roda, nenhuma chamada paga ao modelo sai.</p>
 *
 * <p><b>Chaves só do ambiente</b> ({@link StandaloneEnvironment}): a chave escrita num nó é descartada.</p>
 */
public final class StandaloneFlowMaterializer {

    private final DefaultWorkflowDeserializer deserializer;
    private final FlowValidator validator = new ExecutableNodesValidator(new DefaultFlowValidator());

    private StandaloneFlowMaterializer(DefaultWorkflowDeserializer deserializer) {
        this.deserializer = deserializer;
    }

    /**
     * @param plugins     os componentes dos plugins carregados ({@code ArchFlowAgent.getPluginCatalog()}); os
     *                    nós de componente resolvem aqui
     * @param environment de onde vêm chaves, destinos e o padrão de LLM
     */
    public static StandaloneFlowMaterializer create(ComponentCatalog plugins, StandaloneEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        PortableFlowStepFactory factory = new PortableFlowStepFactory(catalogOf(plugins), null,
                environment.keyResolver());
        factory.setInlineApiKeys(false);
        factory.setBaseUrlPolicy(environment.baseUrlPolicy());
        factory.setProviderPreferences(environment.providerPreferences(), environment.providerPreferencesRequired());
        factory.setPlatformDefaultLLM(environment.platformDefaultLLM());
        // Os provedores embutidos, com o http-decisions levando a preferência de provedor e passando pela guarda
        // de destino — como no servidor. Os que dependem do servidor (llm, langchain4j-decisions) não existem aqui:
        // a fábrica recusa, antes do primeiro passo, o nó que cite um deles.
        factory.setDecisionSupport(DecisionProviders.withDefaults()
                        .register(new DecisionEndpointGuard(new HttpDecisionProvider(
                                environment.providerPreferences(), environment.providerPreferencesRequired()),
                                environment.baseUrlPolicy(), TenantModelPolicy.UNRESTRICTED)),
                environment.decisionKeys());
        // Sem configuration.allowedComponents o fluxo não é restringido — o default do servidor.
        return new StandaloneFlowMaterializer(new DefaultWorkflowDeserializer(factory, false));
    }

    /**
     * O fluxo pronto para o motor.
     *
     * @param document o documento como o servidor o guarda ({@code {id, metadata, configuration, steps[]}})
     * @throws FlowValidationException quando o fluxo não pode rodar aqui — um erro por nó do servidor e por
     *                                 tipo sem execução, com os nós nomeados
     */
    public Flow materialize(Map<String, Object> document) throws FlowValidationException {
        Flow flow = deserializer.toFlow(Objects.requireNonNull(document, "document"));
        validator.validate(flow);
        return flow;
    }

    /**
     * O contexto de uma execução, montado como o servidor monta o dele: a janela de 20 mensagens e a entrada
     * semeada pela mesma regra ({@link RunInputSeeding} — cada chave vira variável e, sem chave {@code input},
     * o objeto inteiro vira o {@code input}). Sem isto a entrada ia só para o estado inicial do motor e nenhum
     * passo a lia.
     *
     * <p>Tenant, usuário e sessão vêm das variáveis {@code tenantId}, {@code userId} e {@code sessionId} da
     * entrada, como no caminho que o runner usava antes ({@code DefaultFlowEngine.startFlow}): fora do servidor
     * não há autenticação que os diga, e {@code --var tenantId=acme} já escolhia o tenant da execução (o que
     * os passos e o estado do motor veem; as chaves do ambiente são da plataforma e valem para qualquer
     * tenant). Sem elas, o tenant padrão e a sessão com o id da execução.</p>
     */
    public static ExecutionContext executionContext(String executionId, Map<String, Object> input) {
        Map<String, Object> vars = input == null ? Map.of() : input;
        ExecutionContext context = new DefaultExecutionContext(
                text(vars.get("tenantId"), DefaultTenant.ID), text(vars.get("userId"), null),
                text(vars.get("sessionId"), executionId),
                MessageWindowChatMemory.builder().maxMessages(20).build());
        RunInputSeeding.seedVariables(context, input);
        return context;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * Os componentes dos plugins, inicializados ao entrar — o que o servidor faz ao copiá-los para o catálogo
     * dele. Sem plugins, um catálogo vazio: o nó de componente falha dizendo "component not found".
     */
    private static ComponentCatalog catalogOf(ComponentCatalog plugins) {
        ComponentCatalog catalog = new DefaultComponentCatalog();
        if (plugins == null) {
            return catalog;
        }
        for (var meta : plugins.listComponents()) {
            AIComponent component = plugins.getComponent(meta.id()).orElse(null);
            if (component != null) {
                CatalogComponents.initializeOnRegistration(component);
                catalog.register(component);
            }
        }
        return catalog;
    }
}
