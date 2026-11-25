package br.com.archflow.agent.execution;

import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.engine.execution.FlowControl;
import br.com.archflow.engine.execution.FlowExecutor;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.error.ExecutionErrorType;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.metrics.StepMetrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Executor de fluxos padrão
 */
public class DefaultFlowExecutor implements FlowExecutor {
    private static final Logger logger = Logger.getLogger(DefaultFlowExecutor.class.getName());

    /** Default per-step execution timeout (ms). Prevents a misbehaving
     * plugin from blocking a flow thread indefinitely. */
    private static final long DEFAULT_STEP_TIMEOUT_MS = 10 * 60 * 1000L; // 10 min

    /**
     * Variável de contexto com os ids dos steps já concluídos (COMPLETED ou
     * SKIPPED). É persistida junto com o estado do fluxo e permite ao resume
     * pular steps concluídos em vez de reexecutar o fluxo inteiro.
     */
    public static final String COMPLETED_STEPS_KEY = "__archflow.completedSteps";

    /**
     * Variável que os steps leem como entrada e os componentes sobrescrevem com a saída — a mesma
     * chave do {@code ComponentStep.INPUT_KEY} e do {@code RunInputSeeding.INPUT_KEY} (archflow-api).
     * No modo grafo o executor a fixa antes de cada step com o dado do predecessor que o ativou.
     */
    public static final String INPUT_KEY = "input";

    /**
     * O dado que cada step passou adiante pelas arestas ({@code {idDoStep: dado}}): o {@link #INPUT_KEY}
     * logo depois de ele executar. Persistido com o estado para o resume entregar aos sucessores de um
     * step já concluído o que ele produziu, e não o que sobrou na variável global.
     */
    public static final String STEP_DATA_KEY = "__archflow.stepData";

    /** A entrada da execução, registrada na primeira travessia — é o que os steps-raiz recebem. */
    public static final String RUN_INPUT_KEY = "__archflow.runInput";

    private final ClassLoader pluginClassLoader;
    private final MetricsCollector metricsCollector;
    private final FlowLifecycleListener lifecycleListener;
    private final Map<String, StepExecution> activeExecutions;
    private final long stepTimeoutMs;
    /** Retry por step (null = sem retry, 1 tentativa). */
    private final br.com.archflow.agent.config.RetryConfig retryConfig;
    private final ConditionEvaluator conditionEvaluator;

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector) {
        this(pluginClassLoader, metricsCollector, FlowLifecycleListener.NO_OP, DEFAULT_STEP_TIMEOUT_MS, null);
    }

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector,
                               FlowLifecycleListener lifecycleListener) {
        this(pluginClassLoader, metricsCollector, lifecycleListener, DEFAULT_STEP_TIMEOUT_MS, null);
    }

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector,
                               FlowLifecycleListener lifecycleListener, long stepTimeoutMs) {
        this(pluginClassLoader, metricsCollector, lifecycleListener, stepTimeoutMs, null);
    }

    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector,
                               FlowLifecycleListener lifecycleListener, long stepTimeoutMs,
                               br.com.archflow.agent.config.RetryConfig retryConfig) {
        this(pluginClassLoader, metricsCollector, lifecycleListener, stepTimeoutMs, retryConfig, false);
    }

    /**
     * @param strictConditions quando {@code true}, uma condição de transição não
     *                         avaliável BLOQUEIA o caminho em vez de liberá-lo —
     *                         ver {@link ConditionEvaluator}
     */
    public DefaultFlowExecutor(ClassLoader pluginClassLoader, MetricsCollector metricsCollector,
                               FlowLifecycleListener lifecycleListener, long stepTimeoutMs,
                               br.com.archflow.agent.config.RetryConfig retryConfig,
                               boolean strictConditions) {
        this.conditionEvaluator = new ConditionEvaluator(strictConditions);
        this.pluginClassLoader = pluginClassLoader;
        this.metricsCollector = metricsCollector;
        this.lifecycleListener = lifecycleListener != null ? lifecycleListener : FlowLifecycleListener.NO_OP;
        this.activeExecutions = new ConcurrentHashMap<>();
        this.stepTimeoutMs = stepTimeoutMs > 0 ? stepTimeoutMs : DEFAULT_STEP_TIMEOUT_MS;
        this.retryConfig = retryConfig;
    }

    @Override
    public FlowResult execute(Flow flow, ExecutionContext context) {
        return execute(flow, context, FlowControl.NONE);
    }

    @Override
    public FlowResult execute(Flow flow, ExecutionContext context, FlowControl control) {
        String flowId = flow.getId();
        Thread.currentThread().setContextClassLoader(pluginClassLoader);

        try {
            logger.info("Iniciando execução do fluxo: " + flowId);
            metricsCollector.recordFlowStart(flowId);

            Traversal traversal = traverse(flow, context, control);
            List<StepResult> results = traversal.results;

            return new FlowResult() {
                @Override
                public ExecutionStatus getStatus() {
                    return traversal.status;
                }

                @Override
                public Optional<Object> getOutput() {
                    return traversal.output;
                }

                @Override
                public ExecutionMetrics getMetrics() {
                    return context.getMetrics();
                }

                @Override
                public List<ExecutionError> getErrors() {
                    return results.stream()
                            .filter(r -> r.getStatus().isError())
                            .flatMap(r -> r.getErrors().stream())
                            .map(e -> ExecutionError.of(
                                    e.code(),
                                    e.message(),
                                    ExecutionErrorType.EXECUTION,
                                    e.context().toString()
                            ))
                            .toList();
                }
            };

        } catch (Exception e) {
            logger.severe("Erro executando fluxo: " + flowId + " - " + e.getMessage());
            metricsCollector.recordFlowError(flowId, e);
            throw new RuntimeException("Erro executando fluxo: " + flowId, e);
        }
    }

    /** Resultado da travessia: passos executados, status final e a saída final do fluxo. */
    private record Traversal(List<StepResult> results, ExecutionStatus status, Optional<Object> output) {
    }

    /**
     * Percorre o fluxo. Dois modos:
     *
     * <ul>
     *   <li><b>Grafo</b> (algum step tem conexões): ver {@link GraphTraversal}.</li>
     *   <li><b>Linear</b> (nenhuma conexão): ver {@link #traverseLinear}.</li>
     * </ul>
     *
     * <p>O {@link FlowControl} é consultado entre steps <b>e ao fim da travessia</b>:
     * stop → CANCELLED, pause → PAUSED (o estado corrente permanece no contexto para
     * resume). A consulta final não é redundante: o sinal costuma chegar DURANTE um
     * step (o nó de aprovação pede a pausa de dentro dele; um cancelamento chega
     * enquanto o step roda). Se esse step era o último, a fila esvazia e, sem a
     * consulta final, o fluxo terminava COMPLETED — a aprovação sumia da fila e o
     * cancelamento já confirmado ao chamador virava sucesso (RCHFLW-14).
     */
    private Traversal traverse(Flow flow, ExecutionContext context, FlowControl control) {
        List<FlowStep> steps = flow.getSteps() != null ? flow.getSteps() : List.of();
        if (steps.isEmpty()) {
            return new Traversal(List.of(), ExecutionStatus.COMPLETED, Optional.empty());
        }
        boolean graphMode = steps.stream().anyMatch(s -> !connectionsOf(s).isEmpty());
        return graphMode
                ? new GraphTraversal(flow, steps, context, control).run()
                : traverseLinear(flow, steps, context, control);
    }

    /**
     * Modo linear (nenhuma conexão): a lista em ordem, cada step lendo o {@code input} que o anterior
     * deixou. As arestas são implícitas (i → i+1), então o último step executado é o terminal e a saída
     * do fluxo é a dele.
     *
     * <p>Um step que falha <b>interrompe o fluxo</b>, a mesma regra do modo grafo para uma falha sem
     * caminho de erro (aqui não existe caminho de erro). Antes o laço seguia para o próximo step, que
     * rodava sobre uma entrada que o step falho não produziu, e o fluxo só terminava FAILED depois de
     * executar todos — o usuário via três falhas em sequência onde o modo grafo teria parado na
     * primeira (RCHFLW-88).</p>
     */
    private Traversal traverseLinear(Flow flow, List<FlowStep> steps, ExecutionContext context,
                                     FlowControl control) {
        List<StepResult> results = new ArrayList<>();
        Set<String> completedInPriorRun = readCompletedSteps(context);
        int stepIndex = 0;
        for (FlowStep step : steps) {
            Optional<ExecutionStatus> signalled = controlSignal(flow, control);
            if (signalled.isPresent()) {
                return new Traversal(results, signalled.get(), lastOutput(results));
            }
            // Resume incremental: step concluído em execução anterior não reexecuta
            // (efeitos colaterais não duplicam).
            if (completedInPriorRun.contains(step.getId())) {
                logger.info("Step " + step.getId() + " já concluído em execução anterior; pulando (resume)");
                continue;
            }
            markCurrentStep(context, step);

            StepResult result = executeStepWithRetry(step, context, flow, stepIndex++, steps.size(), control);
            results.add(result);
            if (cancelledDuring(flow, result, control)) {
                return new Traversal(results, ExecutionStatus.CANCELLED, Optional.empty());
            }
            if (isCompletion(result)) {
                recordCompletedStep(context, step.getId());
            }
            // O step suspendeu o fluxo (portão de aprovação): para aqui, sem marcá-lo concluído —
            // na retomada ele roda de novo e encontra a decisão. Um cancelamento vence a pausa.
            if (result.getStatus() == StepStatus.PAUSED) {
                return new Traversal(results, controlSignal(flow, control).orElse(ExecutionStatus.PAUSED),
                        lastOutput(results));
            }
            if (result.getStatus().isError()) {
                logger.warning("Step " + step.getId() + " falhou no fluxo linear " + flow.getId()
                        + "; os steps seguintes não executam");
                return new Traversal(results, controlSignal(flow, control).orElse(ExecutionStatus.FAILED),
                        lastOutput(results));
            }
        }
        // A lista acabou, mas um sinal levantado durante o último step ainda vale.
        return new Traversal(results, controlSignal(flow, control).orElse(ExecutionStatus.COMPLETED),
                lastOutput(results));
    }

    private static Optional<Object> lastOutput(List<StepResult> results) {
        return results.isEmpty() ? Optional.empty() : results.get(results.size() - 1).getOutput();
    }

    private static boolean isCompletion(StepResult result) {
        return result.getStatus() == StepStatus.COMPLETED || result.getStatus() == StepStatus.SKIPPED;
    }

    /** Checkpoint para resume: marca o step corrente no estado antes de executar. */
    private static void markCurrentStep(ExecutionContext context, FlowStep step) {
        if (context.getState() != null) {
            context.getState().setCurrentStepId(step.getId());
        }
    }

    /** Como as arestas que saem de um step são resolvidas depois que ele termina. */
    private enum Outcome {
        /** Concluiu (ou foi pulado): valem as arestas normais cuja condição é verdadeira. */
        NORMAL,
        /** Falhou: valem as arestas de erro cuja condição é verdadeira. */
        ERROR,
        /** Não vai executar: todas as arestas que saem dele morrem. */
        DEAD
    }

    /** Uma conexão válida: está no step de origem e aponta para um step que existe. */
    private record Edge(String source, String target, StepConnection connection) {
    }

    /**
     * Step pronto para executar e as entradas que chegaram a ele: {@code null} para um step-raiz
     * (recebe a entrada da execução), senão {@code {idDoPredecessor: dado}} das arestas que o ativaram.
     */
    private record Ready(FlowStep step, Map<String, Object> arrivals) {
    }

    /** Entrada de um predecessor concluído numa execução anterior que não registrou o dado. */
    private static final Object NOT_RECORDED = new Object();

    /**
     * Modo grafo: executa a partir dos steps-raiz (sem conexão de entrada) e propaga pelas conexões de
     * saída — caminho normal em sucesso/skip, caminho de erro em falha — avaliando a condição de cada
     * conexão. Três regras dão o fluxo de dados por aresta:
     *
     * <ul>
     *   <li><b>Entrada por aresta (RCHFLW-35).</b> Antes de executar um step, o motor fixa
     *       {@link #INPUT_KEY} com o dado do predecessor cuja aresta o ativou. Todos liam e sobrescreviam
     *       a mesma variável global: num fan-out A→B, A→C, o C recebia a saída do B e o resultado mudava
     *       com a ordem das arestas, sem erro nenhum. O dado de um step é o {@code input} logo depois de
     *       ele executar: um componente o sobrescreve com a saída (e a grava também em
     *       {@code context.get(id)}); um nó de controle ou de aprovação o deixa intacto de propósito — ele
     *       decide o caminho, não transforma o dado — e então repassa o que recebeu. Ler
     *       {@code context.get(id)} de um nó {@code condition} entregaria ao próximo LLM o mapa
     *       {@code {expression, result, branch}} no lugar do texto. Um step-raiz recebe a entrada da
     *       execução.</li>
     *   <li><b>Join (RCHFLW-36).</b> Um step com várias arestas de entrada só executa depois que todos os
     *       predecessores que ainda podem executar resolveram: concluíram, ou tiveram o ramo descartado
     *       (condição/switch falsos, falha sem aquele caminho). "Ramo morto" se propaga — um step cujas
     *       arestas de entrada morreram todas não executa e mata as suas de saída —, senão um join depois
     *       de um {@code condition} esperaria para sempre o ramo que não foi tomado. Antes o step rodava
     *       na primeira chegada e o ramo mais longo ficava de fora. Com um único predecessor ativo o step
     *       recebe o dado dele direto; com mais de um, o mapa {@code {idDoPredecessor: dado}} dos que o
     *       ativaram.</li>
     *   <li><b>Saída final.</b> É a do step terminal que executou; com mais de um, o mapa
     *       {@code {idDoStep: saída}}. Antes era a do último step executado — num losango, o de um ramo
     *       qualquer, conforme a ordem da fila. Terminal é o step que concluiu e onde o caminho acabou:
     *       nenhum dos alvos das suas arestas (fora as de retorno) executou. Um step sem conexões é o caso
     *       estático; o caminho também acaba num step cuja única saída é uma aresta de erro que não
     *       disparou ({@code onError} do DSL), ou uma condição/rota que deu falso (o ramo opcional de
     *       notificação, a decisão que só leva ESCALATE a um tratador). Contar só os steps sem conexão
     *       nenhuma deixava esses fluxos COMPLETED com saída vazia — o VendaX via "não devolveu um JSON"
     *       de uma execução que deu certo. Um join alcançado por outro ramo não deixa terminal o
     *       predecessor cuja aresta para ele deu falso: o caminho seguiu até o join. A regra vale para o
     *       fluxo que chegou ao fim (COMPLETED ou FAILED); suspenso ou cancelado no meio, a saída continua
     *       sendo a do último step executado — no portão de aprovação é o {@code {requestId}} com que o
     *       chamador decide.</li>
     * </ul>
     *
     * <p>Arestas de retorno (as que fecham um ciclo, achadas numa busca em profundidade a partir das
     * raízes) não contam para o join e não reexecutam o alvo: cada step executa no máximo uma vez, como
     * antes. Arestas que saem de um step inalcançável a partir das raízes também não contam — ele nunca
     * executa e o join não pode esperá-lo.</p>
     *
     * <p>O dado de cada step fica em {@link #STEP_DATA_KEY} e a entrada da execução em
     * {@link #RUN_INPUT_KEY}, persistidos com o checkpoint: no resume, os steps concluídos antes não
     * reexecutam, mas os seus sucessores precisam do dado que eles passaram adiante.</p>
     */
    private final class GraphTraversal {
        private final Flow flow;
        private final List<FlowStep> steps;
        private final ExecutionContext context;
        private final FlowControl control;
        private final Map<String, FlowStep> byId = new LinkedHashMap<>();
        private final Map<String, List<Edge>> outgoing = new HashMap<>();
        private final List<String> roots = new ArrayList<>();
        private final Set<Edge> backEdges = new HashSet<>();
        /** Arestas de entrada ainda não resolvidas, por step. */
        private final Map<String, Integer> pending = new HashMap<>();
        /** Dados que já chegaram a cada step pelas arestas ativadas, na ordem de chegada. */
        private final Map<String, Map<String, Object>> arrivals = new HashMap<>();
        private final Deque<Ready> queue = new ArrayDeque<>();
        private final Set<String> completedInPriorRun;
        private final Map<String, Object> stepData;
        private final Object runInput;
        /** Saída de cada step concluído (nesta travessia ou numa anterior), na ordem de conclusão. */
        private final Map<String, Object> completedOutputs = new LinkedHashMap<>();
        /** Steps que a travessia alcançou e processou: executados, recusados ou concluídos antes. */
        private final Set<String> reached = new HashSet<>();
        private final List<StepResult> results = new ArrayList<>();
        private boolean unhandledFailure;
        private int stepIndex;

        GraphTraversal(Flow flow, List<FlowStep> steps, ExecutionContext context, FlowControl control) {
            this.flow = flow;
            this.steps = steps;
            this.context = context;
            this.control = control;
            this.completedInPriorRun = readCompletedSteps(context);
            this.stepData = readStepData(context);
            this.runInput = runInputOf(context, completedInPriorRun);
            buildGraph();
        }

        private void buildGraph() {
            steps.forEach(s -> byId.put(s.getId(), s));
            Set<String> targeted = new HashSet<>();
            for (FlowStep s : steps) {
                List<Edge> edges = new ArrayList<>();
                for (StepConnection c : connectionsOf(s)) {
                    targeted.add(c.getTargetId());
                    // As conexões ficam no step de ORIGEM (sourceId default = próprio id);
                    // ignora defensivamente uma conexão gravada no step errado.
                    String sourceId = c.getSourceId();
                    if (sourceId != null && !sourceId.isBlank() && !sourceId.equals(s.getId())) {
                        continue;
                    }
                    if (!byId.containsKey(c.getTargetId())) {
                        logger.warning("Conexão de " + s.getId() + " aponta para step inexistente: "
                                + c.getTargetId());
                        continue;
                    }
                    edges.add(new Edge(s.getId(), c.getTargetId(), c));
                }
                outgoing.put(s.getId(), edges);
            }
            steps.stream().map(FlowStep::getId).filter(id -> !targeted.contains(id)).forEach(roots::add);
            if (roots.isEmpty()) {
                throw new IllegalStateException(
                        "Fluxo sem step de entrada (todas as conexões formam ciclo): " + flow.getId());
            }
            Set<String> reachable = new HashSet<>();
            for (String root : roots) {
                if (!reachable.contains(root)) {
                    classify(root, reachable, new HashSet<>());
                }
            }
            for (String source : reachable) {
                for (Edge edge : outgoing.getOrDefault(source, List.of())) {
                    if (!backEdges.contains(edge)) {
                        pending.merge(edge.target(), 1, Integer::sum);
                    }
                }
            }
        }

        /** Busca em profundidade: marca os alcançáveis e as arestas que voltam a um step na pilha. */
        private void classify(String node, Set<String> visited, Set<String> onStack) {
            visited.add(node);
            onStack.add(node);
            for (Edge edge : outgoing.getOrDefault(node, List.of())) {
                if (onStack.contains(edge.target())) {
                    backEdges.add(edge);
                } else if (!visited.contains(edge.target())) {
                    classify(edge.target(), visited, onStack);
                }
            }
            onStack.remove(node);
        }

        Traversal run() {
            roots.forEach(id -> queue.add(new Ready(byId.get(id), null)));
            while (!queue.isEmpty()) {
                Optional<ExecutionStatus> signalled = controlSignal(flow, control);
                if (signalled.isPresent()) {
                    return finish(signalled.get());
                }
                Ready ready = queue.poll();
                FlowStep step = ready.step();
                // Resume incremental: step concluído em execução anterior não reexecuta (efeitos
                // colaterais não duplicam); apenas propaga pelas arestas — os outputs restaurados
                // alimentam as condições, e o dado registrado alimenta os sucessores.
                if (completedInPriorRun.contains(step.getId())) {
                    logger.info("Step " + step.getId() + " já concluído em execução anterior; pulando (resume)");
                    reached.add(step.getId());
                    completedOutputs.put(step.getId(),
                            context.get("step." + step.getId() + ".output").orElse(null));
                    resolveOutgoing(step, Outcome.NORMAL);
                    continue;
                }
                markCurrentStep(context, step);

                String missingInput = applyInput(ready);
                StepResult result = missingInput == null
                        ? executeStepWithRetry(step, context, flow, stepIndex++, steps.size(), control)
                        : refuse(step, missingInput, stepIndex++);
                results.add(result);
                reached.add(step.getId());
                if (cancelledDuring(flow, result, control)) {
                    return new Traversal(results, ExecutionStatus.CANCELLED, Optional.empty());
                }
                if (isCompletion(result)) {
                    recordCompletedStep(context, step.getId());
                }

                // O step suspendeu o fluxo (portão de aprovação à espera de uma pessoa): a travessia
                // para AQUI, sem resolver as arestas e sem marcar o step como concluído — na retomada
                // ele roda de novo, encontra a decisão e conclui com ela. Parar já, e não no topo da
                // próxima volta, também vale quando ele é o último step. Um cancelamento pedido no
                // meio do caminho continua vencendo a pausa.
                if (result.getStatus() == StepStatus.PAUSED) {
                    return finish(controlSignal(flow, control).orElse(ExecutionStatus.PAUSED));
                }

                if (missingInput != null) {
                    // A recusa é do motor, não uma falha do step: o caminho de erro não dispara. Ele
                    // receberia o dado que o step passaria adiante, ou seja, a variável global que sobrou —
                    // justamente o que a recusa existe para não entregar. O ramo morre e o fluxo termina
                    // FAILED com o motivo nos erros.
                    unhandledFailure = true;
                    resolveOutgoing(step, Outcome.DEAD);
                    continue;
                }
                recordStepData(step.getId(), context.get(INPUT_KEY).orElse(null));
                if (result.getStatus() == StepStatus.FAILED) {
                    if (resolveOutgoing(step, Outcome.ERROR) == 0) {
                        unhandledFailure = true;
                    }
                } else {
                    if (isCompletion(result)) {
                        completedOutputs.put(step.getId(), result.getOutput().orElse(null));
                    }
                    resolveOutgoing(step, Outcome.NORMAL);
                }
            }
            // A fila esvaziou, mas um sinal levantado durante o último step ainda vale.
            return finish(controlSignal(flow, control)
                    .orElse(unhandledFailure ? ExecutionStatus.FAILED : ExecutionStatus.COMPLETED));
        }

        /**
         * Fixa o {@code input} do step com o que chegou pelas arestas. Devolve o motivo quando um
         * predecessor concluído numa execução anterior não deixou o dado registrado (estado gravado por
         * uma versão do motor anterior ao registro): o step não executa sobre o {@code input} que
         * sobrou na variável global, que seria o de outro ramo — é o defeito que esta regra corrige.
         */
        private String applyInput(Ready ready) {
            Object input;
            if (ready.arrivals() == null) {
                input = runInput;
            } else {
                for (Map.Entry<String, Object> arrival : ready.arrivals().entrySet()) {
                    if (arrival.getValue() == NOT_RECORDED) {
                        return "Entrada do step " + ready.step().getId() + " indisponível: o step "
                                + arrival.getKey() + " concluiu numa execução anterior sem registrar o dado "
                                + "que passa adiante (estado gravado por uma versão anterior do motor). "
                                + "Execute o fluxo de novo.";
                    }
                }
                input = ready.arrivals().size() == 1
                        ? ready.arrivals().values().iterator().next()
                        : new LinkedHashMap<>(ready.arrivals());
            }
            // Sem dado a passar (execução sem entrada, ou predecessor que repassou nada), o step roda sem
            // input. Deixar a variável como estava entregaria a saída de outro ramo — o vazamento que esta
            // regra corrige: com duas raízes numa execução sem entrada, a segunda lia a saída da primeira.
            // O mapa de variáveis não aceita null, daí a remoção.
            if (input != null) {
                context.set(INPUT_KEY, input);
            } else {
                context.remove(INPUT_KEY);
            }
            return null;
        }

        /** O step não executa: falha com o motivo, pelos mesmos callbacks de uma falha real. */
        private StepResult refuse(FlowStep step, String reason, int index) {
            logger.severe(reason);
            IllegalStateException error = new IllegalStateException(reason);
            safeLifecycle(() -> lifecycleListener.onStepStarted(flow, step, context, index, steps.size()));
            safeLifecycle(() -> lifecycleListener.onStepFailed(flow, step, context, error, 0L));
            return createErrorResult(step, error);
        }

        /**
         * Resolve as arestas que saem de {@code step} e libera os alvos cujas entradas resolveram todas.
         *
         * @return quantas arestas foram ativadas (inclusive de retorno) — zero numa falha quer dizer
         *         falha sem caminho de erro
         */
        private int resolveOutgoing(FlowStep step, Outcome outcome) {
            int activated = 0;
            for (Edge edge : outgoing.getOrDefault(step.getId(), List.of())) {
                StepConnection conn = edge.connection();
                boolean active = outcome != Outcome.DEAD
                        && conn.isErrorPath() == (outcome == Outcome.ERROR)
                        && conditionEvaluator.evaluate(conn.getCondition().orElse(null), context);
                if (backEdges.contains(edge)) {
                    if (active) {
                        activated++;
                        logger.fine("Aresta " + edge.source() + " → " + edge.target()
                                + " fecha um ciclo; o alvo já executou e não reexecuta");
                    }
                    continue;
                }
                if (active) {
                    activated++;
                    arrivals.computeIfAbsent(edge.target(), k -> new LinkedHashMap<>())
                            .put(step.getId(), dataOf(step.getId()));
                }
                if (pending.merge(edge.target(), -1, Integer::sum) == 0) {
                    settle(edge.target());
                }
            }
            return activated;
        }

        /** Todas as entradas do step resolveram: executa se alguma o ativou, senão o ramo morre aqui. */
        private void settle(String stepId) {
            FlowStep target = byId.get(stepId);
            Map<String, Object> arrived = arrivals.remove(stepId);
            if (arrived == null || arrived.isEmpty()) {
                logger.info("Step " + stepId + " não executa: nenhuma aresta de entrada foi ativada (ramo morto)");
                resolveOutgoing(target, Outcome.DEAD);
            } else {
                queue.add(new Ready(target, arrived));
            }
        }

        private Object dataOf(String stepId) {
            return stepData.containsKey(stepId) ? stepData.get(stepId) : NOT_RECORDED;
        }

        private void recordStepData(String stepId, Object data) {
            stepData.put(stepId, data);
            // Cópia nova a cada step, como em recordCompletedStep: o checkpoint serializa o valor
            // enquanto a travessia segue.
            context.set(STEP_DATA_KEY, new HashMap<>(stepData));
        }

        private Traversal finish(ExecutionStatus status) {
            if (status != ExecutionStatus.COMPLETED && status != ExecutionStatus.FAILED) {
                // Suspenso ou cancelado no meio: o fluxo não chegou ao fim e não tem saída final. Fica a do
                // último step executado, como antes — no portão de aprovação, o {requestId} pendente.
                return new Traversal(results, status, lastOutput(results));
            }
            Map<String, Object> terminalOutputs = new LinkedHashMap<>();
            completedOutputs.forEach((id, out) -> {
                if (pathEndsAt(id)) {
                    terminalOutputs.put(id, out);
                }
            });
            Optional<Object> output;
            if (terminalOutputs.isEmpty()) {
                output = Optional.empty();
            } else if (terminalOutputs.size() == 1) {
                output = Optional.ofNullable(terminalOutputs.values().iterator().next());
            } else {
                output = Optional.of(terminalOutputs);
            }
            return new Traversal(results, status, output);
        }

        /** O caminho acabou no step: nenhum alvo das suas arestas (fora as de retorno) foi alcançado. */
        private boolean pathEndsAt(String stepId) {
            return outgoing.getOrDefault(stepId, List.of()).stream()
                    .filter(edge -> !backEdges.contains(edge))
                    .noneMatch(edge -> reached.contains(edge.target()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readStepData(ExecutionContext context) {
        Map<String, Object> data = new HashMap<>();
        context.get(STEP_DATA_KEY)
                .filter(v -> v instanceof Map)
                .ifPresent(v -> ((Map<Object, Object>) v).forEach((k, value) -> data.put(String.valueOf(k), value)));
        return data;
    }

    /**
     * A entrada da execução, que os steps-raiz recebem. Na primeira travessia é o {@code input} semeado
     * pelo chamador, e fica registrada: depois do primeiro step a variável já é a saída de alguém, e no
     * resume é a que sobrou da execução anterior.
     */
    private static Object runInputOf(ExecutionContext context, Set<String> completedInPriorRun) {
        Object recorded = context.get(RUN_INPUT_KEY).orElse(null);
        if (recorded == null && completedInPriorRun.isEmpty()) {
            recorded = context.get(INPUT_KEY).orElse(null);
            if (recorded != null) {
                context.set(RUN_INPUT_KEY, recorded);
            }
        }
        return recorded;
    }

    /**
     * Status imposto pelo sinal de controle, se houver. Um único lugar para as duas
     * consultas (topo do laço e fim da travessia) não divergirem na prioridade:
     * stop vence pause, e ambos vencem o status calculado pelos steps.
     */
    private static Optional<ExecutionStatus> controlSignal(Flow flow, FlowControl control) {
        if (control.isStopRequested()) {
            logger.info("Fluxo " + flow.getId() + " cancelado; interrompendo travessia");
            return Optional.of(ExecutionStatus.CANCELLED);
        }
        if (control.isPauseRequested()) {
            logger.info("Fluxo " + flow.getId() + " pausado; suspendendo travessia");
            return Optional.of(ExecutionStatus.PAUSED);
        }
        return Optional.empty();
    }

    /**
     * O cancelamento chegou enquanto o step rodava (RCHFLW-54): o resultado dele é descartado — não
     * entra no checkpoint de concluídos, não resolve arestas e não vira a saída do fluxo — e nenhum
     * step seguinte roda. O step não é interrompido no meio (a interrupção é cooperativa); quem
     * pediu para parar não pode receber como saída o que ele pediu para não acontecer. O portão de
     * aprovação que suspendeu fica de fora: o ramo do PAUSED já dá a vitória ao cancelamento e
     * mantém o {@code {requestId}} como saída, que a fila de aprovações usa.
     */
    private static boolean cancelledDuring(Flow flow, StepResult result, FlowControl control) {
        if (result.getStatus() == StepStatus.PAUSED || !control.isStopRequested()) {
            return false;
        }
        logger.info("Fluxo " + flow.getId() + " cancelado durante o step " + result.getStepId()
                + "; resultado descartado e travessia interrompida");
        return true;
    }

    private static List<StepConnection> connectionsOf(FlowStep step) {
        return step.getConnections() != null ? step.getConnections() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readCompletedSteps(ExecutionContext context) {
        return context.get(COMPLETED_STEPS_KEY)
                .filter(v -> v instanceof java.util.Collection)
                .map(v -> (Set<String>) ((java.util.Collection<?>) v).stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(HashSet::new)))
                .orElseGet(HashSet::new);
    }

    private static void recordCompletedStep(ExecutionContext context, String stepId) {
        // Lista (e não Set) para round-trip JSON estável na persistência
        List<String> completed = new ArrayList<>(context.get(COMPLETED_STEPS_KEY)
                .filter(v -> v instanceof java.util.Collection)
                .map(v -> ((java.util.Collection<?>) v).stream().map(String::valueOf).toList())
                .orElse(List.of()));
        if (!completed.contains(stepId)) {
            completed.add(stepId);
        }
        context.set(COMPLETED_STEPS_KEY, completed);
    }

    private void handleResult(String scopedKey, StepResult result) {
        String stepId = result.getStepId();
        logger.info("Processando resultado do step: " + stepId);

        StepExecution execution = scopedKey != null ? activeExecutions.get(scopedKey) : null;
        if (execution == null) {
            // Truly unknown stepId — either never registered or already
            // fully processed and cleaned up. Fail loudly so the caller
            // sees the mismatch. The idempotency guard below ensures we
            // do NOT reach here for a second legitimate delivery of a
            // step whose execution entry is still present.
            throw new IllegalStateException(
                    "Step execution not registered for stepId=" + stepId
                    + " (scopedKey=" + scopedKey + ")");
        }

        // Idempotency: if the step future's whenComplete fires AND the
        // executor's explicit timeout path also dispatches a result (or
        // a plugin misbehaves and completes twice), we must process
        // exactly once. CAS on a per-step AtomicBoolean gives us that
        // with zero locking.
        if (!execution.markHandled()) {
            logger.fine("Step " + stepId + " already handled; ignoring duplicate result");
            return;
        }

        try {
            // Registra métricas
            if (result.getMetrics() != null) {
                metricsCollector.recordStepMetrics(
                        execution.getFlowId(),
                        stepId,
                        result.getMetrics()
                );
            }

            // Processa resultado baseado no status
            switch (result.getStatus()) {
                case COMPLETED -> handleSuccess(execution, result);
                case FAILED -> handleFailure(execution, result);
                case SKIPPED -> handleSkipped(execution, result);
                case PAUSED -> handleSuspended(execution, result);
                default -> logger.warning("Status não tratado: " + result.getStatus());
            }

            // Remove execução ativa
            activeExecutions.remove(scopedKey);

        } catch (Exception e) {
            logger.severe("Erro processando resultado do step " + stepId + ": " + e.getMessage());
            execution.fail(e);
        }
    }

    // A propagação para os próximos steps é responsabilidade exclusiva da
    // travessia em traverse(); estes handlers só registram o resultado no
    // contexto. (A versão anterior também disparava executeSteps daqui, o que
    // duplicava execuções quando combinado com o laço principal.)

    private void handleSuccess(StepExecution execution, StepResult result) {
        logger.info("Step " + result.getStepId() + " concluído com sucesso");
        execution.getContext().set("step." + result.getStepId() + ".output",
                result.getOutput().orElse(null));
    }

    private void handleFailure(StepExecution execution, StepResult result) {
        logger.severe("Step " + result.getStepId() + " falhou: " +
                result.getErrors().stream()
                        .map(StepError::message)
                        .findFirst().orElse("Sem mensagem de erro"));
        execution.getContext().set("step." + result.getStepId() + ".error",
                result.getErrors());
    }

    private void handleSkipped(StepExecution execution, StepResult result) {
        logger.info("Step " + result.getStepId() + " ignorado");
    }

    /**
     * Step que suspendeu o fluxo: a saída (no portão de aprovação, o {@code requestId}) vai para
     * o contexto como a de um step concluído, para o detalhe da execução mostrar qual pedido está
     * aberto. Não é conclusão — quem decide isso é {@code traverse}, que não o marca concluído.
     */
    private void handleSuspended(StepExecution execution, StepResult result) {
        logger.info("Step " + result.getStepId() + " suspendeu o fluxo");
        execution.getContext().set("step." + result.getStepId() + ".output",
                result.getOutput().orElse(null));
    }

    /**
     * Executa o step honrando o {@code RetryConfig} (item 1.10 do plano):
     * um step FAILED é retentado até {@code maxAttempts} tentativas totais,
     * com backoff exponencial, a menos que pause/cancel seja sinalizado.
     */
    private StepResult executeStepWithRetry(FlowStep step, ExecutionContext context, Flow flow,
                                            int stepIndex, int stepCount, FlowControl control) {
        int maxAttempts = retryConfig != null ? Math.max(1, retryConfig.maxAttempts()) : 1;
        long delayMs = retryConfig != null ? retryConfig.initialDelay() : 0L;

        StepResult result = executeStep(step, context, flow, stepIndex, stepCount);
        for (int attempt = 2; attempt <= maxAttempts && result.getStatus() == StepStatus.FAILED; attempt++) {
            if (control.isStopRequested() || control.isPauseRequested()) {
                break;
            }
            logger.info("Step " + step.getId() + " falhou; retry " + (attempt - 1)
                    + "/" + (maxAttempts - 1) + " em " + delayMs + "ms");
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            delayMs = (long) (delayMs * retryConfig.backoffMultiplier());
            result = executeStep(step, context, flow, stepIndex, stepCount);
        }
        return result;
    }

    private StepResult executeStep(FlowStep step, ExecutionContext context, Flow flow,
                                   int stepIndex, int stepCount) {
        String stepId = step.getId();
        String scopedKey = flow.getId() + ":" + stepId;
        logger.info("Executando step: " + stepId + " (flow: " + flow.getId() + ")");

        long startNs = System.nanoTime();
        safeLifecycle(() -> lifecycleListener.onStepStarted(flow, step, context, stepIndex, stepCount));

        try {
            // Registra execução ativa com key scoped por flow para evitar
            // colisão entre flows concorrentes com step IDs iguais
            StepExecution execution = new StepExecution(flow, step, context);
            activeExecutions.put(scopedKey, execution);

            CompletableFuture<StepResult> stepFuture = step.execute(context)
                    .whenComplete((r, error) -> {
                        if (error != null) {
                            logger.severe("Erro executando step " + stepId + ": " + error.getMessage());
                            handleResult(scopedKey, createErrorResult(step, error));
                        } else {
                            handleResult(scopedKey, r);
                        }
                    });

            StepResult result;
            try {
                result = stepFuture.get(stepTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // Try to unblock the step; the whenComplete hook above will
                // fire with a CancellationException and clear activeExecutions
                // via handleResult. We return a synthetic error so the flow
                // engine can route to the error path.
                stepFuture.cancel(true);
                long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                safeLifecycle(() -> lifecycleListener.onStepFailed(flow, step, context, te, durationMs));
                return createErrorResult(step, te);
            }

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (result.getStatus() == StepStatus.SKIPPED) {
                safeLifecycle(() -> lifecycleListener.onStepSkipped(flow, step, context));
            } else if (result.getStatus() == StepStatus.PAUSED) {
                // Suspensão não é conclusão: pelo caminho de "concluído" o portão de aprovação
                // aparecia como ✓ no canvas e no registro enquanto esperava a decisão (RCHFLW-32).
                safeLifecycle(() -> lifecycleListener.onStepSuspended(flow, step, context, durationMs));
            } else if (result.getStatus().isError()) {
                Throwable cause = result.getErrors().isEmpty() ? null
                        : new RuntimeException(result.getErrors().get(0).message());
                safeLifecycle(() -> lifecycleListener.onStepFailed(flow, step, context, cause, durationMs));
            } else {
                safeLifecycle(() -> lifecycleListener.onStepCompleted(flow, step, context, durationMs));
            }
            return result;

        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            logger.severe("Erro executando step: " + stepId + " - " + e.getMessage());
            safeLifecycle(() -> lifecycleListener.onStepFailed(flow, step, context, e, durationMs));
            return createErrorResult(step, e);
        }
    }

    private void safeLifecycle(Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            logger.warning("FlowLifecycleListener callback failed (swallowed): " + e.getMessage());
        }
    }

    private StepResult createErrorResult(FlowStep step, Throwable error) {
        return new StepResult() {
            @Override
            public String getStepId() {
                return step.getId();
            }

            @Override
            public StepStatus getStatus() {
                return StepStatus.FAILED;
            }

            @Override
            public Optional<Object> getOutput() {
                return Optional.empty();
            }

            @Override
            public StepMetrics getMetrics() {
                return null;
            }

            @Override
            public List<StepError> getErrors() {
                return List.of(StepError.fromException(error, "STEP_EXECUTION_ERROR"));
            }
        };
    }

    /**
     * Classe interna para manter estado da execução de um step
     */
    private static class StepExecution {
        private final Flow flow;
        private final FlowStep step;
        private final ExecutionContext context;
        private final String flowId;
        private volatile boolean failed;
        /** CAS guard so handleResult runs at most once per step execution. */
        private final java.util.concurrent.atomic.AtomicBoolean handled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        public StepExecution(Flow flow, FlowStep step, ExecutionContext context) {
            this.flow = flow;
            this.step = step;
            this.context = context;
            this.flowId = context.getState().getFlowId();
        }

        /** @return {@code true} if this call is the first to mark the execution handled */
        public boolean markHandled() {
            return handled.compareAndSet(false, true);
        }

        public Flow getFlow() {
            return flow;
        }

        public FlowStep getStep() {
            return step;
        }

        public ExecutionContext getContext() {
            return context;
        }

        public String getFlowId() {
            return flowId;
        }

        public void fail(Throwable error) {
            this.failed = true;
            context.set("step." + step.getId() + ".error", error);
        }

        public boolean isFailed() {
            return failed;
        }
    }
}