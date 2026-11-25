package br.com.archflow.agent.orchestration;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.domain.Task;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.orchestration.Result;
import br.com.archflow.orchestration.Usage;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestration {@link br.com.archflow.orchestration.Worker} that picks the best-matching agent for each
 * subtask <em>at runtime</em> via {@link ComponentQueryRouter} and invokes it —
 * the dynamic "best agent for the next task" selection that distinguishes
 * archflow's catalog from a hand-wired pipeline (ADR-0002 D4 / design-0003).
 *
 * <p>Input is the subtask description (as produced by a {@code Planner}); output
 * is the agent's raw output. Token/cost {@link Usage} is read from the agent
 * Result's metadata ({@code tokensUsed} / {@code costUsd}) when present, so the
 * orchestrator's budget enforcement works for agents that report it.
 *
 * <h2>Como a subtarefa chega ao agente (RCHFLW-61)</h2>
 * Antes o worker montava {@code Task.of("orchestration.subtask", {description})}: um tipo que nenhum
 * agente do catálogo aceita e um parâmetro que nenhum lê. Toda subtarefa voltava "Unsupported task
 * type" e a orquestração convergia com zero confirmados, sem dizer por quê. Agora:
 * <ul>
 *   <li>um {@link AIAgent} recebe uma {@code Task} do tipo padrão que ele mesmo declara
 *       ({@link AIAgent#supportedTaskTypes()}), com o texto em {@code description}, {@code request} e
 *       {@code query} — os nomes que os agentes leem. Agente que não declara tipo nenhum é recusado
 *       com esse motivo: adivinhar um tipo repetiria o defeito com outro nome;</li>
 *   <li>um componente {@code AGENT} que não é {@code AIAgent} (o {@code mcp-agent}) é executado pelo
 *       {@link AIComponent#execute}, que é o caminho dele num fluxo, com a operação padrão que ele
 *       declara;</li>
 *   <li>toda falha sai como {@link Result#fail} com o id do componente e o motivo — nunca exceção
 *       solta nem sucesso vazio.</li>
 * </ul>
 *
 * <h2>Quem atendeu e quanto gastou (RCHFLW-64, RCHFLW-62)</h2>
 * Cada subtarefa gera um {@link OrchestrationListener#onDispatched} com o componente escolhido, o score
 * do roteador e se deu certo — em todo caminho, inclusive "nenhum agente casou". O uso é o que o
 * componente informa em {@code tokensUsed}/{@code costUsd} no {@code Result} de domínio, inclusive numa
 * falha (ele pode ter gastado antes de falhar). Componente que rodou e não informou {@code tokensUsed}
 * gera {@link OrchestrationListener#onUsageUnknown} e debita zero: o aviso diz que o total é um piso, em
 * vez de um número inventado.
 */
public final class CatalogAgentWorker implements SubtaskWorker {

    /**
     * Dica de operação padrão nas propriedades dos metadados — a mesma de {@code CatalogComponents}
     * (archflow-api), que este módulo não enxerga. Sem a dica vale {@link #LEGACY_OPERATION}, a mesma
     * regra de lá; divergir faria o componente rodar de um jeito no fluxo e de outro na orquestração.
     */
    static final String DEFAULT_OPERATION_HINT = "defaultOperation";
    static final String LEGACY_OPERATION = "execute";

    /** Chave de {@code Result.metadata()} com os tokens gastos — o contrato que o worker lê. */
    static final String TOKENS_USED = "tokensUsed";
    static final String COST_USD = "costUsd";

    private final ComponentQueryRouter router;
    private final ComponentCatalog catalog;
    private final ExecutionContext context;
    private final OrchestrationListener listener;

    /**
     * O contexto recebido é <b>recortado</b> por {@link SubAgentContext}: antes,
     * cada sub-agente recebia o contexto do fluxo inteiro — toda variável, a
     * saída de todo step anterior, a proposta de aprovação pendente e a conversa
     * acumulada. A restrição de catálogo impedia o sub-agente de <em>chamar</em>
     * o que não devia, mas não de <em>ler</em> o que não devia.
     */
    public CatalogAgentWorker(ComponentQueryRouter router, ComponentCatalog catalog, ExecutionContext context) {
        this(router, catalog, context, null);
    }

    /**
     * @param allowedVariables allowlist fechada de variáveis para os sub-agentes;
     *                         {@code null} aplica o recorte default (sem o
     *                         namespace interno do motor)
     */
    public CatalogAgentWorker(ComponentQueryRouter router, ComponentCatalog catalog,
                              ExecutionContext context,
                              java.util.Collection<String> allowedVariables) {
        this(router, catalog, context, allowedVariables, OrchestrationListener.NOOP);
    }

    /**
     * @param listener quem recebe o despacho de cada subtarefa e o aviso de uso não informado — o mesmo
     *                 listener do supervisor; sem ele o agente escolhido se perdia (RCHFLW-64)
     */
    public CatalogAgentWorker(ComponentQueryRouter router, ComponentCatalog catalog,
                              ExecutionContext context,
                              java.util.Collection<String> allowedVariables,
                              OrchestrationListener listener) {
        this.router = router;
        this.catalog = catalog;
        this.context = SubAgentContext.of(context, allowedVariables);
        this.listener = listener == null ? OrchestrationListener.NOOP : listener;
    }

    /**
     * O que a execução da subtarefa deu, além do resultado: se o componente chegou a ser chamado (só então
     * pode ter gastado) e se informou o uso.
     */
    private record Run(Result<Object> result, boolean invoked, boolean usageReported) {

        static Run refused(String error) {
            return new Run(Result.fail(error), false, false);
        }
    }

    @Override
    public Result<Object> apply(int round, String subtask) {
        Optional<ComponentQueryRouter.ScoredComponent> scored;
        try {
            scored = router.route(subtask, ComponentType.AGENT);
        } catch (RuntimeException e) {
            // Fora deste try a exceção subia ao orquestrador, que a convertia em falha sem o evento de
            // despacho: a subtarefa sumia do trace, do SSE e do estado (RCHFLW-64). Ninguém foi escolhido.
            listener.onDispatched(round, subtask, null, null, false);
            return Result.fail("o roteador falhou ao escolher um agente para a subtarefa: " + messageOf(e));
        }
        if (scored.isEmpty()) {
            listener.onDispatched(round, subtask, null, null, false);
            return Result.fail("no agent matched subtask: " + subtask);
        }
        String componentId = scored.get().componentId();
        Run run = run(componentId, subtask);
        listener.onDispatched(round, subtask, componentId, scored.get().score(), run.result().ok());
        if (run.invoked() && !run.usageReported()) {
            listener.onUsageUnknown(round, subtask, componentId);
        }
        return run.result();
    }

    private Run run(String componentId, String subtask) {
        AIComponent component;
        try {
            component = catalog.getComponent(componentId).orElse(null);
        } catch (RuntimeException e) {
            // Mesma razão do roteador: a exceção não pode pular o onDispatched. O componente não chegou a
            // ser chamado, então não houve gasto a relatar.
            return Run.refused(componentId + ": falha ao obter o componente do catálogo — " + messageOf(e));
        }
        if (component == null) {
            return Run.refused(componentId + ": componente não está no catálogo");
        }
        try {
            return component instanceof AIAgent agent
                    ? runAgent(componentId, agent, subtask)
                    : runComponent(componentId, component, subtask);
        } catch (Exception e) {
            // O componente é código de terceiro: uma exceção dele é a falha DESTA subtarefa, com o
            // motivo dele — não pode derrubar as irmãs nem virar um "failed" sem texto. Ele rodou e não
            // disse quanto gastou: uso desconhecido.
            return new Run(Result.fail(componentId + ": " + messageOf(e)), true, false);
        }
    }

    private Run runAgent(String componentId, AIAgent agent, String subtask) {
        List<String> types = agent.supportedTaskTypes();
        if (types == null || types.isEmpty()) {
            return Run.refused(componentId + ": o agente não declara tipos de tarefa (supportedTaskTypes), "
                    + "então não há como despachar a subtarefa num formato que ele aceite");
        }
        Task task = Task.of(types.get(0), subtaskParameters(subtask));
        br.com.archflow.model.ai.domain.Result result = agent.executeTask(task, context);
        if (result == null) {
            return new Run(Result.fail(componentId + ": o agente não devolveu resultado"), true, false);
        }
        return fromDomain(componentId, result);
    }

    private Run runComponent(String componentId, AIComponent component, String subtask) throws Exception {
        ComponentMetadata metadata = component.getMetadata();
        String operation = defaultOperation(metadata);
        List<String> declared = declaredOperations(metadata);
        if (!declared.isEmpty() && !declared.contains(operation)) {
            return Run.refused(componentId + ": não é AIAgent e não declara a operação '" + operation
                    + "'; operações: " + String.join(", ", declared));
        }
        Object output = component.execute(operation, subtask, context);
        if (output == null) {
            // Sem saída não há o que verificar; "sucesso" com null chegaria ao verificador como "null".
            return new Run(Result.fail(componentId + ": o componente não devolveu saída"), true, false);
        }
        // Um Result de domínio sem sucesso é falha, como em CatalogComponents.requireSuccess — tratá-lo
        // como saída comum levaria um fracasso para a verificação.
        if (output instanceof br.com.archflow.model.ai.domain.Result r) {
            return fromDomain(componentId, r);
        }
        // Saída crua não tem onde informar uso.
        return new Run(Result.success(output, Usage.ZERO), true, false);
    }

    /**
     * O {@code Result} de domínio em resultado do orquestrador, com o uso informado — também na falha: um
     * agente que gastou e falhou gastou, e o {@code Result.fail} do núcleo debitaria zero.
     */
    private static Run fromDomain(String componentId, br.com.archflow.model.ai.domain.Result result) {
        Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
        boolean reported = metadata.get(TOKENS_USED) instanceof Number;
        Usage usage = new Usage(asLong(metadata.get(TOKENS_USED)), asDouble(metadata.get(COST_USD)));
        Result<Object> converted = result.success()
                ? Result.success(result.output(), usage)
                : new Result<>(null, false, componentId + ": " + messagesOf(result), usage);
        return new Run(converted, true, reported);
    }

    /** O texto da subtarefa nos nomes que os agentes do catálogo leem. */
    private static Map<String, Object> subtaskParameters(String subtask) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("description", subtask);
        parameters.put("request", subtask);
        parameters.put("query", subtask);
        return parameters;
    }

    private static String defaultOperation(ComponentMetadata metadata) {
        Object hint = metadata == null || metadata.properties() == null
                ? null : metadata.properties().get(DEFAULT_OPERATION_HINT);
        return hint != null && !hint.toString().isBlank() ? hint.toString() : LEGACY_OPERATION;
    }

    private static List<String> declaredOperations(ComponentMetadata metadata) {
        if (metadata == null || metadata.operations() == null) {
            return List.of();
        }
        return metadata.operations().stream()
                .map(ComponentMetadata.OperationMetadata::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private static String messagesOf(br.com.archflow.model.ai.domain.Result result) {
        List<String> messages = result.messages();
        return messages == null || messages.isEmpty() ? "falhou sem mensagem" : String.join("; ", messages);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
