package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.api.admin.observability.ObservabilityDtos.SpanDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.error.ExecutionErrorType;
import br.com.archflow.model.flow.FlowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TraceStoreRecorder")
class TraceStoreRecorderTest {

    private InMemoryTraceStore store;
    private TraceStoreRecorder recorder;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore();
        recorder = new TraceStoreRecorder(store);
    }

    // ── helpers ─────────────────────────────────────────────────

    private static FlowResult mockResult(ExecutionStatus status, List<ExecutionError> errors) {
        FlowResult result = mock(FlowResult.class);
        when(result.getStatus()).thenReturn(status);
        when(result.getErrors()).thenReturn(errors);
        when(result.getOutput()).thenReturn(Optional.empty());
        when(result.getMetrics()).thenReturn(mock(ExecutionMetrics.class));
        return result;
    }

    private static ExecutionError error(String message) {
        return ExecutionError.of("ERR-001", message, ExecutionErrorType.EXECUTION);
    }

    // ── tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("onFlowStart is a no-op — store remains empty")
    void onFlowStartIsNoOp() {
        recorder.onFlowStart("flow-a", "tenant-1", "persona-1");

        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("onFlowEnd with COMPLETED status records trace with status OK")
    void onFlowEndCompletedMapsToOk() {
        FlowResult result = mockResult(ExecutionStatus.COMPLETED, List.of());

        recorder.onFlowEnd("flow-ok", "tenant-1", result, 250L);

        assertThat(store.size()).isEqualTo(1);
        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-1", null, null, null, null, null);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("onFlowEnd with FAILED status records trace with status ERROR and captures first error message")
    void onFlowEndFailedMapsToErrorWithMessage() {
        FlowResult result = mockResult(ExecutionStatus.FAILED,
                List.of(error("connection timed out"), error("secondary error")));

        recorder.onFlowEnd("flow-fail", "tenant-2", result, 500L);

        assertThat(store.size()).isEqualTo(1);
        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-2", null, null, null, null, null);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status()).isEqualTo("ERROR");
        assertThat(summaries.get(0).error()).isEqualTo("connection timed out");
    }

    @Test
    @DisplayName("onFlowEnd with non-COMPLETED status (CANCELLED) records trace as ERROR")
    void onFlowEndNonCompletedMapsToError() {
        FlowResult result = mockResult(ExecutionStatus.CANCELLED, List.of());

        recorder.onFlowEnd("flow-cancel", "tenant-3", result, 100L);

        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-3", null, null, null, null, null);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("onFlowEnd records correct flowId, tenantId, and durationMs in the trace detail")
    void onFlowEndRecordsCorrectMetadata() {
        FlowResult result = mockResult(ExecutionStatus.COMPLETED, List.of());

        recorder.onFlowEnd("flow-meta", "tenant-meta", result, 750L);

        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-meta", null, null, null, null, null);
        assertThat(summaries).hasSize(1);

        String traceId = summaries.get(0).traceId();
        TraceDetailDto detail = store.findById(traceId).orElseThrow();

        assertThat(detail.flowId()).isEqualTo("flow-meta");
        assertThat(detail.tenantId()).isEqualTo("tenant-meta");
        assertThat(detail.durationMs()).isEqualTo(750L);
    }

    @Test
    @DisplayName("onFlowEnd creates a root span named 'flow.execute' with kind 'INTERNAL'")
    void onFlowEndCreatesRootSpan() {
        FlowResult result = mockResult(ExecutionStatus.COMPLETED, List.of());

        recorder.onFlowEnd("flow-span", "tenant-span", result, 300L);

        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-span", null, null, null, null, null);
        String traceId = summaries.get(0).traceId();
        TraceDetailDto detail = store.findById(traceId).orElseThrow();

        assertThat(detail.spans()).hasSize(1);
        SpanDto rootSpan = detail.spans().get(0);
        assertThat(rootSpan.name()).isEqualTo("flow.execute");
        assertThat(rootSpan.kind()).isEqualTo("INTERNAL");
        assertThat(rootSpan.parentSpanId()).isNull();
        assertThat(rootSpan.durationMs()).isEqualTo(300L);
    }

    @Test
    @DisplayName("onFlowEnd with null tenantId records trace without tenantId")
    void onFlowEndWithNullTenantId() {
        FlowResult result = mockResult(ExecutionStatus.COMPLETED, List.of());

        recorder.onFlowEnd("flow-notenant", null, result, 120L);

        assertThat(store.size()).isEqualTo(1);
        // Query all (no tenantId filter) to locate the trace
        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> all =
                store.query(null, null, null, null, null, null);
        assertThat(all).hasSize(1);

        TraceDetailDto detail = store.findById(all.get(0).traceId()).orElseThrow();
        assertThat(detail.tenantId()).isNull();
        assertThat(detail.flowId()).isEqualTo("flow-notenant");
    }

    @Test
    @DisplayName("multiple onFlowEnd calls produce multiple independent traces")
    void multipleCallsProduceMultipleTraces() {
        FlowResult ok = mockResult(ExecutionStatus.COMPLETED, List.of());
        FlowResult fail = mockResult(ExecutionStatus.FAILED, List.of(error("boom")));

        recorder.onFlowEnd("flow-1", "tenant-multi", ok, 100L);
        recorder.onFlowEnd("flow-2", "tenant-multi", fail, 200L);
        recorder.onFlowEnd("flow-3", "tenant-multi", ok, 300L);

        assertThat(store.size()).isEqualTo(3);

        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-multi", null, null, null, null, null);
        assertThat(summaries).hasSize(3);
        assertThat(summaries).extracting(
                br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto::status)
                .containsExactlyInAnyOrder("OK", "ERROR", "OK");
    }

    @Test
    @DisplayName("traceId generated by onFlowEnd starts with 'trace-'")
    void traceIdStartsWithTracePrefix() {
        FlowResult result = mockResult(ExecutionStatus.COMPLETED, List.of());

        recorder.onFlowEnd("flow-id-check", "tenant-prefix", result, 50L);

        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-prefix", null, null, null, null, null);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).traceId()).startsWith("trace-");
    }

    @Test
    @DisplayName("root span attributes contain flow.id and tenant.id when tenantId is provided")
    void rootSpanContainsFlowAndTenantAttributes() {
        FlowResult result = mockResult(ExecutionStatus.COMPLETED, List.of());

        recorder.onFlowEnd("flow-attrs", "tenant-attrs", result, 400L);

        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> summaries =
                store.query("tenant-attrs", null, null, null, null, null);
        TraceDetailDto detail = store.findById(summaries.get(0).traceId()).orElseThrow();

        SpanDto rootSpan = detail.spans().get(0);
        assertThat(rootSpan.attributes()).containsEntry("flow.id", "flow-attrs");
        assertThat(rootSpan.attributes()).containsEntry("tenant.id", "tenant-attrs");
    }

    @Test
    @DisplayName("root span attributes do not contain tenant.id when tenantId is null")
    void rootSpanOmitsTenantAttributeWhenNull() {
        FlowResult result = mockResult(ExecutionStatus.COMPLETED, List.of());

        recorder.onFlowEnd("flow-no-tenant-attr", null, result, 150L);

        List<br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto> all =
                store.query(null, null, null, null, null, null);
        TraceDetailDto detail = store.findById(all.get(0).traceId()).orElseThrow();

        SpanDto rootSpan = detail.spans().get(0);
        assertThat(rootSpan.attributes()).containsKey("flow.id");
        assertThat(rootSpan.attributes()).doesNotContainKey("tenant.id");
    }

    // ── RCHFLW-177: pausa para aprovação ───────────────────────────

    @Test
    @DisplayName("PAUSED (à espera de aprovação) vira trace PAUSED, não ERROR")
    void pausaNaoEErro() {
        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.PAUSED, List.of()), 14L);

        assertThat(store.query("tenant-1", null, null, null, null, null))
                .singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(TraceStoreRecorder.STATUS_PAUSED));
    }

    @Test
    @DisplayName("pausa e retomada da mesma execução: um trace só, que termina OK, com as durações somadas")
    void retomadaSubstituiAPausa() {
        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.PAUSED, List.of()), 14L);
        String traceDaPausa = store.query("tenant-1", null, null, null, null, null).get(0).traceId();
        java.time.Instant inicio = store.findById(traceDaPausa).orElseThrow().startedAt();

        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.COMPLETED, List.of()), 30L);

        assertThat(store.query("tenant-1", null, null, null, null, null)).singleElement().satisfies(t -> {
            assertThat(t.traceId()).isEqualTo(traceDaPausa);
            assertThat(t.status()).isEqualTo("OK");
            assertThat(t.durationMs()).isEqualTo(44L);
            assertThat(t.startedAt()).isEqualTo(inicio);
        });
    }

    @Test
    @DisplayName("outra execução não reaproveita a pausa de ninguém")
    void execucoesDiferentes() {
        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.PAUSED, List.of()), 14L);
        recorder.onFlowEnd("exec-2", "tenant-1", mockResult(ExecutionStatus.COMPLETED, List.of()), 30L);

        assertThat(store.query("tenant-1", null, null, null, null, null))
                .extracting(br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto::status)
                .containsExactlyInAnyOrder("PAUSED", "OK");
    }

    @Test
    @DisplayName("pausa encerrada (recusa, prazo) vira ERROR com o motivo, no mesmo trace — não fica aguardando")
    void pausaEncerrada() {
        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.PAUSED, List.of()), 14L);
        String traceDaPausa = store.query("tenant-1", null, null, null, null, null).get(0).traceId();

        recorder.encerrarPausa("tenant-1", "exec-1", "Recusado na aprovação 'Revisão' por bob");

        assertThat(store.query("tenant-1", null, null, null, null, null)).singleElement().satisfies(t -> {
            assertThat(t.traceId()).isEqualTo(traceDaPausa);
            assertThat(t.status()).isEqualTo(TraceStoreRecorder.STATUS_ERROR);
            assertThat(t.error()).isEqualTo("Recusado na aprovação 'Revisão' por bob");
            assertThat(t.durationMs()).isEqualTo(14L);
        });
        assertThat(store.findById(traceDaPausa).orElseThrow().spans().get(0).status())
                .isEqualTo(TraceStoreRecorder.STATUS_ERROR);
    }

    @Test
    @DisplayName("encerrar uma execução que já tem desfecho não muda nada")
    void encerrarDesfechoNaoMuda() {
        recorder.onFlowEnd("exec-1", "tenant-1",
                mockResult(ExecutionStatus.FAILED, List.of(error("passo quebrou"))), 14L);

        recorder.encerrarPausa("tenant-1", "exec-1", "outro motivo");

        assertThat(store.query("tenant-1", null, null, null, null, null)).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo(TraceStoreRecorder.STATUS_ERROR);
            assertThat(t.error()).isEqualTo("passo quebrou");
        });
    }

    @Test
    @DisplayName("recusada antes de a suspensão gravar a pausa: a pausa chega já encerrada")
    void recusaAntesDaPausa() {
        recorder.encerrarPausa("tenant-1", "exec-1", "Recusado na aprovação 'Revisão'");

        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.PAUSED, List.of()), 14L);

        assertThat(store.query("tenant-1", null, null, null, null, null)).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo(TraceStoreRecorder.STATUS_ERROR);
            assertThat(t.error()).isEqualTo("Recusado na aprovação 'Revisão'");
        });
    }

    @Test
    @DisplayName("aprovada enquanto a suspensão desenrolava: a retomada termina antes e a pausa atrasada "
            + "entra no mesmo trace, que continua OK")
    void suspensaoAtrasada() {
        // A retomada (10 ms) termina primeiro; a travessia suspensa (50 ms) começou antes dela e chega depois.
        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.COMPLETED, List.of()), 10L);

        recorder.onFlowEnd("exec-1", "tenant-1", mockResult(ExecutionStatus.PAUSED, List.of()), 50L);

        assertThat(store.query("tenant-1", null, null, null, null, null)).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo(TraceStoreRecorder.STATUS_OK);
            assertThat(t.durationMs()).isEqualTo(60L);
        });
    }

    @Test
    @DisplayName("o mesmo id de execução em outro tenant não substitui a pausa de ninguém")
    void pausaPorTenant() {
        recorder.onFlowEnd("exec-1", "tenant-a", mockResult(ExecutionStatus.PAUSED, List.of()), 14L);

        recorder.onFlowEnd("exec-1", "tenant-b", mockResult(ExecutionStatus.COMPLETED, List.of()), 30L);

        assertThat(store.query("tenant-a", null, null, null, null, null)).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(TraceStoreRecorder.STATUS_PAUSED));
        assertThat(store.query("tenant-b", null, null, null, null, null)).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo(TraceStoreRecorder.STATUS_OK);
            assertThat(t.tenantId()).isEqualTo("tenant-b");
        });
    }

    // ── RCHFLW-195 item 6: o trace sabe qual é o fluxo ────────────────

    @Test
    @DisplayName("com o registro da execução, o trace guarda o id do fluxo e a busca acha pelo nome e pelos ids")
    void fluxoDoRegistro() {
        recorder = new TraceStoreRecorder(store, (tenant, exec) -> "exec-0f9d58ff".equals(exec)
                ? java.util.Map.of("workflowId", "wf-98fb0ece", "workflowName", "QA2 obs triagem")
                : null);

        recorder.onFlowEnd("exec-0f9d58ff", "tenant-1", mockResult(ExecutionStatus.COMPLETED, List.of()), 10L);

        TraceDetailDto t = store.findById(store.query("tenant-1", null, null, null, null, null).get(0).traceId())
                .orElseThrow();
        assertThat(t.flowId()).isEqualTo("wf-98fb0ece");
        assertThat(t.executionId()).isEqualTo("exec-0f9d58ff");
        assertThat(t.attributes()).containsEntry(TraceStoreRecorder.ATTR_FLOW_NAME, "QA2 obs triagem");
        for (String busca : List.of("qa2 obs triagem", "wf-98fb0ece", "exec-0f9d58ff")) {
            assertThat(store.query("tenant-1", null, null, busca, null, null)).as(busca).hasSize(1);
        }
    }
}
