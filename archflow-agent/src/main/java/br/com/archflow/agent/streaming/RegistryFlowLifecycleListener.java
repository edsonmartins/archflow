package br.com.archflow.agent.streaming;

import br.com.archflow.agent.streaming.domain.FlowEvent;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link FlowLifecycleListener} that updates the {@link RunningFlowsRegistry}
 * and broadcasts SSE events via the {@link EventStreamRegistry}.
 *
 * <p>Each callback:
 * <ol>
 *   <li>Mutates the {@code RunningFlowsRegistry} to reflect current state.</li>
 *   <li>Broadcasts to the flow-specific channel ({@code executionId = flowId}).</li>
 *   <li>Delivers to the execution's tenant channel ({@code tenantId + ":" + flowId}) — the one
 *       {@code GET /api/stream/{executionId}} subscribes to.</li>
 *   <li>Broadcasts to the admin channel ({@code executionId = "__admin__:" + tenantId})
 *       so the Running Flows dashboard receives updates without subscribing individually.</li>
 * </ol>
 *
 * <p><b>Canal do tenant da execução (RCHFLW-50).</b> O stream SSE só abre canais
 * {@code tenant:sessão}, e só do tenant de quem assina; o canal nu {@code flowId} não tem assinante
 * por HTTP. Sem a entrega em {@code tenantId:flowId} a tela de eventos ao vivo conectava na execução
 * e só recebia o {@code connected}. O tenant é o do {@link ExecutionContext#getTenantId()} — o da
 * requisição que criou a execução —, nunca a variável {@code "tenantId"}, que é dado de entrada de
 * quem chama a API: lida dela, o chamador escolheria em que tenant os eventos (e a linha do Running)
 * apareceriam.
 *
 * <p>All broadcasts are wrapped in try/catch — a stream error never propagates
 * back to the execution engine.
 */
public class RegistryFlowLifecycleListener implements FlowLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(RegistryFlowLifecycleListener.class);

    private final EventStreamRegistry streamRegistry;
    private final RunningFlowsRegistry runningFlowsRegistry;

    /**
     * Índice com que cada passo foi anunciado no STEP_STARTED, para o evento de fim
     * repetir o mesmo número. O índice do início vem do contador de travessia do
     * executor; recalcular no fim pela posição em {@code flow.getSteps()} dava outro
     * número sempre que a ordem de declaração difere da de execução (ramos de
     * decision/switch/condition) — o cliente não conseguia casar início e fim.
     */
    private final Map<StepKey, Integer> startedIndexes = new ConcurrentHashMap<>();

    private record StepKey(String flowId, String stepId) {}

    public RegistryFlowLifecycleListener(EventStreamRegistry streamRegistry,
                                         RunningFlowsRegistry runningFlowsRegistry) {
        this.streamRegistry = streamRegistry;
        this.runningFlowsRegistry = runningFlowsRegistry;
    }

    @Override
    public void onFlowStarted(Flow flow, ExecutionContext context, int stepCount) {
        String flowId = flow.getId();
        String tenantId = tenantIdOf(context);
        try {
            runningFlowsRegistry.flowStarted(flowId, tenantId, stepCount);

            ArchflowEvent event = FlowEvent.flowStarted(flowId, tenantId, stepCount, flowId);
            publish(tenantId, flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onFlowStarted listener error (swallowed)", flowId, e);
        }
    }

    @Override
    public void onFlowCompleted(Flow flow, ExecutionContext context,
                                FlowResult result, long durationMs) {
        String flowId = flow.getId();
        String tenantId = tenantIdOf(context);
        try {
            runningFlowsRegistry.flowEnded(flowId);
            forgetStartedIndexes(flowId);

            // O motor chama onFlowCompleted para todo FlowResult que volta sem exceção, inclusive
            // um passo que falhou sem aresta de erro. Anunciar isso como flow_completed era estado
            // falso para quem consome o stream (RCHFLW-33): FAILED vira flow_failed com o motivo
            // registrado no resultado; os demais desfechos levam o status no flow_completed.
            ExecutionStatus status = result != null ? result.getStatus() : null;
            ArchflowEvent event = status == ExecutionStatus.FAILED
                    ? FlowEvent.flowFailed(flowId, tenantId, failureMessage(result),
                            failureType(result), durationMs, flowId)
                    : FlowEvent.flowCompleted(flowId, tenantId,
                            status != null ? status.name() : null, durationMs, flowId);
            publish(tenantId, flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onFlowCompleted listener error (swallowed)", flowId, e);
        }
    }

    @Override
    public void onFlowFailed(Flow flow, ExecutionContext context,
                             Throwable error, long durationMs) {
        String flowId = flow.getId();
        String tenantId = tenantIdOf(context);
        try {
            runningFlowsRegistry.flowEnded(flowId);
            forgetStartedIndexes(flowId);

            String msg = error != null ? error.getMessage() : "unknown";
            String type = error != null ? error.getClass().getSimpleName() : "unknown";
            ArchflowEvent event = FlowEvent.flowFailed(flowId, tenantId, msg, type, durationMs, flowId);
            publish(tenantId, flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onFlowFailed listener error (swallowed)", flowId, e);
        }
    }

    @Override
    public void onStepStarted(Flow flow, FlowStep step, ExecutionContext context,
                              int stepIndex, int stepCount) {
        String flowId = flow.getId();
        String stepId = step.getId();
        try {
            runningFlowsRegistry.stepStarted(flowId, stepId, stepIndex);
            if (stepId != null) {
                startedIndexes.put(new StepKey(flowId, stepId), stepIndex);
            }

            String stepName = step.getId(); // use ID as label — full name not always available
            ArchflowEvent event = FlowEvent.stepStarted(
                    flowId, stepId, stepName, stepIndex, stepCount, flowId);
            publish(tenantIdOf(context), flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onStepStarted listener error (swallowed)", flowId, e);
        }
    }

    @Override
    public void onStepCompleted(Flow flow, FlowStep step, ExecutionContext context,
                                long durationMs) {
        String flowId = flow.getId();
        try {
            // Registry update not needed — the flow stays active; next stepStarted will update
            int stepIndex = finishedStepIndex(flow, step);
            ArchflowEvent event = FlowEvent.stepCompleted(flowId, step.getId(),
                    stepIndex, durationMs, flowId);
            publish(tenantIdOf(context), flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onStepCompleted listener error (swallowed)", flowId, e);
        }
    }

    @Override
    public void onStepFailed(Flow flow, FlowStep step, ExecutionContext context,
                             Throwable error, long durationMs) {
        String flowId = flow.getId();
        try {
            int stepIndex = finishedStepIndex(flow, step);
            String msg = error != null ? error.getMessage() : "unknown";
            String type = error != null ? error.getClass().getSimpleName() : "unknown";
            ArchflowEvent event = FlowEvent.stepFailed(flowId, step.getId(),
                    stepIndex, msg, type, durationMs, flowId);
            publish(tenantIdOf(context), flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onStepFailed listener error (swallowed)", flowId, e);
        }
    }

    @Override
    public void onStepSkipped(Flow flow, FlowStep step, ExecutionContext context) {
        String flowId = flow.getId();
        try {
            int stepIndex = finishedStepIndex(flow, step);
            ArchflowEvent event = FlowEvent.stepSkipped(flowId, step.getId(), stepIndex, flowId);
            publish(tenantIdOf(context), flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onStepSkipped listener error (swallowed)", flowId, e);
        }
    }

    @Override
    public void onStepSuspended(Flow flow, FlowStep step, ExecutionContext context,
                                long durationMs) {
        String flowId = flow.getId();
        try {
            // Mesmo índice do STEP_STARTED: para o cliente, a suspensão fecha o início do passo.
            int stepIndex = finishedStepIndex(flow, step);
            ArchflowEvent event = FlowEvent.stepSuspended(flowId, step.getId(),
                    stepIndex, durationMs, flowId);
            publish(tenantIdOf(context), flowId, event);
        } catch (Exception e) {
            log.warn("[{}] onStepSuspended listener error (swallowed)", flowId, e);
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** Os três canais de um evento de fluxo/passo; ver o javadoc da classe. */
    private void publish(String tenantId, String flowId, ArchflowEvent event) {
        safeBroadcast(flowId, event);
        safeTenantDelivery(tenantId, flowId, event);
        safeAdminBroadcast(tenantId, event);
    }

    private void safeBroadcast(String executionId, ArchflowEvent event) {
        try {
            streamRegistry.broadcast(executionId, event);
        } catch (Exception e) {
            log.debug("broadcast to {} failed (swallowed): {}", executionId, e.getMessage());
        }
    }

    /**
     * {@code deliver}, e não {@code broadcast}: os ouvintes globais já receberam o evento no canal
     * do fluxo, e o publicador protobuf o mandaria de novo.
     */
    private void safeTenantDelivery(String tenantId, String flowId, ArchflowEvent event) {
        if (tenantId == null || flowId == null) return;
        String channel = EventStreamRegistry.tenantChannel(tenantId, flowId);
        try {
            streamRegistry.deliver(channel, event);
        } catch (Exception e) {
            log.debug("tenant delivery to {} failed (swallowed): {}", channel, e.getMessage());
        }
    }

    private void safeAdminBroadcast(String tenantId, ArchflowEvent event) {
        if (tenantId == null) return;
        String adminChannel = "__admin__:" + tenantId;
        try {
            streamRegistry.broadcast(adminChannel, event);
        } catch (Exception e) {
            log.debug("admin broadcast to {} failed (swallowed): {}", adminChannel, e.getMessage());
        }
    }

    /**
     * O tenant da execução: o do contexto, que quem a criou preencheu com o tenant da requisição
     * (RCHFLW-13). A variável {@code "tenantId"} não serve — chega do input de quem chama a API; ver
     * o javadoc da classe.
     */
    private String tenantIdOf(ExecutionContext context) {
        if (context == null) return null;
        try {
            return context.getTenantId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Índice do passo que terminou: o mesmo do STEP_STARTED correspondente. Só cai na
     * posição em {@code flow.getSteps()} quando não houve início registrado (ex.: um
     * chamador que anuncia o fim sem o início) — o comportamento anterior.
     */
    private int finishedStepIndex(Flow flow, FlowStep step) {
        String stepId = step.getId();
        if (stepId != null) {
            Integer started = startedIndexes.remove(new StepKey(flow.getId(), stepId));
            if (started != null) {
                return started;
            }
        }
        return currentStepIndex(flow, step);
    }

    /** Fim do fluxo: descarta índices de passos que nunca anunciaram o fim (evita vazamento). */
    private void forgetStartedIndexes(String flowId) {
        startedIndexes.keySet().removeIf(k -> Objects.equals(k.flowId(), flowId));
    }

    /**
     * Motivo de um FAILED sem exceção: as mensagens dos erros que o motor pôs no resultado (os
     * passos que falharam). Sem nenhuma, o mesmo "unknown" do {@link #onFlowFailed} com erro nulo.
     */
    private static String failureMessage(FlowResult result) {
        List<ExecutionError> errors = result.getErrors();
        if (errors == null || errors.isEmpty()) {
            return "unknown";
        }
        String joined = errors.stream()
                .map(ExecutionError::message)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.joining("; "));
        return joined.isEmpty() ? "unknown" : joined;
    }

    /**
     * Tipo do primeiro erro do resultado — não há exceção cujo nome de classe usar. Por isso o
     * {@code errorType} deste flow_failed é um nome de {@code ExecutionErrorType}, e o do
     * {@link #onFlowFailed} é um nome de classe: vocabulários diferentes, documentados em
     * {@link FlowEvent#flowFailed}.
     */
    private static String failureType(FlowResult result) {
        List<ExecutionError> errors = result.getErrors();
        if (errors == null || errors.isEmpty() || errors.get(0).type() == null) {
            return "unknown";
        }
        return errors.get(0).type().name();
    }

    private int currentStepIndex(Flow flow, FlowStep step) {
        var steps = flow.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getId().equals(step.getId())) return i;
        }
        return -1;
    }
}
