package br.com.archflow.api.flow;

import br.com.archflow.api.web.workflow.WorkflowRuntimeStore;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Persists per-step lifecycle data (status, timings, errors) into the
 * execution record of the {@link WorkflowRuntimeStore}, so
 * {@code GET /api/executions/{id}} can serve a step-by-step drill-down
 * instead of only the flow-level status. The flow id equals the
 * execution id in both execution paths ({@code /execute} and AG-UI).
 *
 * <p>Also records the flow's wall-clock {@code duration}, which the
 * store's {@code completeExecution} does not set.
 *
 * <p>Um passo concluído também grava {@code output}: a saída que o executor
 * deixou em {@code step.<id>.output} no contexto (o {@code handleSuccess} roda
 * antes do {@code onStepCompleted}). Antes disso a saída só existia no contexto
 * em memória e o detalhe da execução/aba "Saída" do editor ficavam vazios.
 * Ela vai como <b>texto</b> (JSON serializado, ou a própria string) e
 * truncada em {@link #MAX_OUTPUT_CHARS}: o {@code JdbcWorkflowRuntimeStore}
 * regrava o registro inteiro como JSON a cada evento, então um objeto
 * arbitrário (não serializável, ou de megabytes) aqui quebraria ou incharia
 * todos os {@code recordStep} seguintes da execução.
 */
public final class StepRecordingListener implements FlowLifecycleListener {

    /** Limite da saída gravada por passo — é diagnóstico, não o resultado oficial. */
    static final int MAX_OUTPUT_CHARS = 4096;

    static final String TRUNCATED_SUFFIX = "…(truncado)";

    // Mapper próprio e tolerante: a saída de um passo pode carregar beans sem
    // getters; falhar aqui não pode derrubar o registro do passo.
    private static final ObjectMapper OUTPUT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final WorkflowRuntimeStore store;

    public StepRecordingListener(WorkflowRuntimeStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void onStepStarted(Flow flow, FlowStep step, ExecutionContext context,
                              int stepIndex, int stepCount) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("type", step.getType() != null ? step.getType().name() : null);
        patch.put("stepIndex", stepIndex);
        patch.put("stepCount", stepCount);
        patch.put("status", "RUNNING");
        patch.put("startedAt", Instant.now().toString());
        store.recordStep(flow.getId(), step.getId(), patch);
    }

    @Override
    public void onStepCompleted(Flow flow, FlowStep step, ExecutionContext context, long durationMs) {
        Map<String, Object> patch = finished("COMPLETED", durationMs, null);
        String output = outputOf(step, context);
        if (output != null) {
            patch.put("output", output);
        }
        store.recordStep(flow.getId(), step.getId(), patch);
    }

    @Override
    public void onStepFailed(Flow flow, FlowStep step, ExecutionContext context,
                             Throwable error, long durationMs) {
        store.recordStep(flow.getId(), step.getId(),
                finished("FAILED", durationMs, error != null ? error.getMessage() : null));
    }

    @Override
    public void onStepSkipped(Flow flow, FlowStep step, ExecutionContext context) {
        store.recordStep(flow.getId(), step.getId(), finished("SKIPPED", null, null));
    }

    @Override
    public void onFlowCompleted(Flow flow, ExecutionContext context, FlowResult result, long durationMs) {
        store.recordDuration(flow.getId(), durationMs);
    }

    @Override
    public void onFlowFailed(Flow flow, ExecutionContext context, Throwable error, long durationMs) {
        store.recordDuration(flow.getId(), durationMs);
    }

    /**
     * Saída do passo como texto truncado, ou {@code null} quando não há (ou
     * quando nem a leitura nem a serialização funcionam) — gravar o status do
     * passo é mais importante que gravar a saída.
     */
    static String outputOf(FlowStep step, ExecutionContext context) {
        if (context == null || step == null || step.getId() == null) {
            return null;
        }
        try {
            Object value = context.get("step." + step.getId() + ".output").orElse(null);
            if (value == null) {
                return null;
            }
            return serialize(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Serializa já limitado: o {@link BoundedWriter} para de aceitar texto depois de
     * {@link #MAX_OUTPUT_CHARS} e aborta o Jackson. Serializar tudo e cortar depois custava, na
     * thread do fluxo e a cada passo, o tamanho inteiro da saída (documentos de RAG, payload de
     * ferramenta de megabytes) só para jogar quase tudo fora — e um grafo cíclico recursava até o
     * guarda de StackOverflow do Jackson. Com o limite, o custo fica plano: um ciclo também escreve
     * texto a cada nível, então estoura o limite bem antes da pilha.
     */
    static String serialize(Object value) {
        if (value instanceof CharSequence text) {
            return text.length() <= MAX_OUTPUT_CHARS
                    ? text.toString()
                    : text.subSequence(0, MAX_OUTPUT_CHARS) + TRUNCATED_SUFFIX;
        }
        BoundedWriter writer = new BoundedWriter(MAX_OUTPUT_CHARS);
        try {
            OUTPUT_MAPPER.writeValue(writer, value);
        } catch (Exception | StackOverflowError e) {
            // O aborto do writer chega aqui embrulhado ou não, conforme o serializador — por isso
            // decide pela flag, não pelo tipo da exceção.
            if (!writer.overflowed()) {
                return fallbackText(value);
            }
        }
        return writer.overflowed() ? writer.text() + TRUNCATED_SUFFIX : writer.text();
    }

    /** {@code toString} como último recurso — também limitado e sem poder derrubar o registro. */
    private static String fallbackText(Object value) {
        try {
            String text = String.valueOf(value);
            return text.length() <= MAX_OUTPUT_CHARS ? text : text.substring(0, MAX_OUTPUT_CHARS) + TRUNCATED_SUFFIX;
        } catch (RuntimeException | StackOverflowError e) {
            return "<" + value.getClass().getName() + ">";
        }
    }

    /**
     * Writer que guarda até {@code limit} caracteres e então lança, interrompendo a serialização.
     * Não é thread-safe — é criado por chamada.
     */
    static final class BoundedWriter extends Writer {

        private final StringBuilder buffer = new StringBuilder();
        private final int limit;
        private boolean overflowed;

        BoundedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (overflowed) {
                throw new IOException("saída do passo excedeu " + limit + " caracteres");
            }
            int room = limit - buffer.length();
            if (len > room) {
                buffer.append(cbuf, off, room);
                overflowed = true;
                throw new IOException("saída do passo excedeu " + limit + " caracteres");
            }
            buffer.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            // nada a descarregar: o texto fica no buffer
        }

        @Override
        public void close() {
            // idem
        }

        boolean overflowed() {
            return overflowed;
        }

        String text() {
            return buffer.toString();
        }
    }

    private static Map<String, Object> finished(String status, Long durationMs, String error) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", status);
        patch.put("finishedAt", Instant.now().toString());
        if (durationMs != null) {
            patch.put("durationMs", durationMs);
        }
        if (error != null) {
            patch.put("error", error);
        }
        return patch;
    }
}
