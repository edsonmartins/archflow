package br.com.archflow.api.web.workflow;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.model.tenant.DefaultTenant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-188, item 16: cancelar uma execução com um LLM Chat em voo gravava CANCELLED com o instante do pedido, mas o
 * passo que estava rodando terminava (limitação documentada) e o desfecho da travessia chegava depois: o
 * {@code completedAt} passava para o fim do passo, a duração era gravada (14 s numa execução cancelada em 0,5 s) e o
 * passo virava COMPLETED dentro da execução CANCELLED. A execução cancelada é o registro que não muda mais.
 *
 * <p>Os dois stores: produção usa o JDBC, e o {@code mutateExecutionIf} e as saídas antecipadas do
 * {@code recordStep}/{@code recordDuration} são código próprio dele. H2 em modo PostgreSQL com o DDL das migrações
 * V6_3/V6_12_2, como o {@code JdbcVersoesEmProducaoTest}: o Postgres de verdade precisa de Docker.</p>
 */
@DisplayName("Execução cancelada: o registro não é reescrito depois")
class ExecucaoCanceladaNaoMudaTest {

    static Stream<Supplier<WorkflowRuntimeStore>> stores() {
        return Stream.of(InMemoryWorkflowRuntimeStore::new, ExecucaoCanceladaNaoMudaTest::jdbc);
    }

    private static WorkflowRuntimeStore jdbc() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cancelada-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE workflow_documents (
                        id VARCHAR(64) PRIMARY KEY, document TEXT NOT NULL, updated_at TIMESTAMP NOT NULL,
                        tenant_id VARCHAR(64) NOT NULL)
                    """);
            st.execute("""
                    CREATE TABLE workflow_executions (
                        id VARCHAR(64) PRIMARY KEY, workflow_id VARCHAR(64) NOT NULL, record TEXT NOT NULL,
                        updated_at TIMESTAMP NOT NULL, tenant_id VARCHAR(64) NOT NULL)
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new JdbcWorkflowRuntimeStore(ds);
    }

    private static String nova(WorkflowRuntimeStore store) {
        return String.valueOf(store.createExecution(DefaultTenant.ID, "wf-1", "Fluxo", null).get("id"));
    }

    @ParameterizedTest
    @MethodSource("stores")
    @DisplayName("o passo em voo sai CANCELLED; o fim tardio da travessia não muda status, completedAt, duração, passo")
    void canceladaNaoMuda(Supplier<WorkflowRuntimeStore> novo) throws Exception {
        WorkflowRuntimeStore store = novo.get();
        String id = nova(store);
        store.recordStep(id, "entrada", Map.of("status", "COMPLETED"));
        store.recordStep(id, "llm", Map.of("status", "RUNNING", "startedAt", "2026-11-26T04:05:53Z"));

        store.cancelExecution(id);
        Map<String, Object> noCancelar = copia(store.getExecution(DefaultTenant.ID, id));
        assertThat(noCancelar.get("status")).isEqualTo("CANCELLED");
        assertThat(noCancelar.get("completedAt")).isNotNull();
        assertThat(passo(noCancelar, "llm").get("status")).as("o passo em voo sai com a execução")
                .isEqualTo("CANCELLED");
        assertThat(passo(noCancelar, "entrada").get("status")).isEqualTo("COMPLETED");

        Thread.sleep(5);
        // A chamada ao LLM termina e a travessia desenrola: StepRecordingListener e o whenComplete do /execute.
        store.recordStep(id, "llm", Map.of("status", "COMPLETED", "durationMs", 14000));
        store.recordStep(id, "resposta", Map.of("status", "RUNNING"));
        store.recordDuration(id, 14223);
        store.completeExecution(id, "CANCELLED", null);
        store.completeExecution(id, "COMPLETED", null);

        assertThat(store.getExecution(DefaultTenant.ID, id)).isEqualTo(noCancelar);
    }

    @ParameterizedTest
    @MethodSource("stores")
    @DisplayName("a duração da cancelada é a do pedido (startedAt → cancelar), não vazia nem a do fim da travessia")
    void duracaoDoPedido(Supplier<WorkflowRuntimeStore> novo) throws Exception {
        WorkflowRuntimeStore store = novo.get();
        String id = nova(store);
        Thread.sleep(20);

        store.cancelExecution(id);
        store.recordDuration(id, 14223);

        Map<String, Object> registro = store.getExecution(DefaultTenant.ID, id);
        long esperado = Duration.between(Instant.parse(String.valueOf(registro.get("startedAt"))),
                Instant.parse(String.valueOf(registro.get("completedAt")))).toMillis();
        assertThat(registro.get("duration")).as("a duração do pedido").isNotNull();
        assertThat(((Number) registro.get("duration")).longValue()).isEqualTo(esperado).isBetween(20L, 14222L);
    }

    @ParameterizedTest
    @MethodSource("stores")
    @DisplayName("retomada depois de um cancelar: a marca sai, os passos e o desfecho chegam, o fluxo não fica preso")
    void retomadaTiraAMarca(Supplier<WorkflowRuntimeStore> novo) {
        WorkflowRuntimeStore store = novo.get();
        String id = nova(store);
        store.recordStep(id, "gate", Map.of("status", "PAUSED", "suspendedAt", "2026-11-26T04:05:53Z"));
        store.cancelExecution(id);

        // A fila de aprovações decide (o pedido vem do StateManager) e retoma.
        store.markResumed(id);
        store.recordStep(id, "gate", Map.of("status", "COMPLETED"));
        store.recordStep(id, "resposta", Map.of("status", "COMPLETED"));
        store.recordDuration(id, 10);
        store.completeExecution(id, "COMPLETED", null);

        Map<String, Object> registro = store.getExecution(DefaultTenant.ID, id);
        assertThat(registro.get("status")).isEqualTo("COMPLETED");
        assertThat(registro.get(WorkflowRuntimeStore.CANCELLED_AT)).isNull();
        assertThat(passo(registro, "resposta").get("status")).isEqualTo("COMPLETED");
        assertThat(WorkflowRuntimeStore.activeExecutionIds(List.of(registro))).as("o fluxo pode ser excluído")
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("stores")
    @DisplayName("cancelar com o motor sem a travessia (desenrolou num portão): sem marca; "
            + "o PAUSED e o desfecho chegam")
    void cancelarSemTravessiaNoMotor(Supplier<WorkflowRuntimeStore> novo) {
        WorkflowRuntimeStore store = novo.get();
        FlowEngine engine = mock(FlowEngine.class);
        SpringExecutionController controller = new SpringExecutionController(store, engine, null, null);
        String id = nova(store);
        // O motor já gravou AWAITING_APPROVAL e soltou a travessia; o whenComplete do /execute ainda não gravou.
        store.recordStep(id, "gate", Map.of("status", "PAUSED", "suspendedAt", "2026-11-26T04:05:53Z"));
        doThrow(new FlowNotFoundException(id)).when(engine).cancel(id);

        controller.cancel(id);
        assertThat(store.getExecution(DefaultTenant.ID, id).get(WorkflowRuntimeStore.CANCELLED_AT)).isNull();

        store.completeExecution(id, "PAUSED", null);
        assertThat(store.getExecution(DefaultTenant.ID, id).get("status")).as("o pedido segue na fila")
                .isEqualTo("PAUSED");
        store.markResumed(id);
        store.recordStep(id, "gate", Map.of("status", "COMPLETED"));
        store.completeExecution(id, "COMPLETED", null);
        assertThat(store.getExecution(DefaultTenant.ID, id).get("status")).isEqualTo("COMPLETED");
    }

    @ParameterizedTest
    @MethodSource("stores")
    @DisplayName("o CANCELLED do motor (a recusa que parou a travessia) não congela: o STOPPED da fila vence")
    void canceladoPeloMotorNaoCongela(Supplier<WorkflowRuntimeStore> novo) {
        WorkflowRuntimeStore store = novo.get();
        String id = nova(store);
        store.completeExecution(id, "CANCELLED", null);
        store.recordStep(id, "gate", Map.of("status", "REJECTED", "decidedBy", "diretor"));
        store.completeExecution(id, "STOPPED", "Rejected");

        Map<String, Object> registro = store.getExecution(DefaultTenant.ID, id);
        assertThat(registro.get("status")).isEqualTo("STOPPED");
        assertThat(passo(registro, "gate")).containsEntry("decidedBy", "diretor");
    }

    @ParameterizedTest
    @MethodSource("stores")
    @DisplayName("cancelar uma execução que já terminou não muda nada")
    void cancelarDepoisDoFim(Supplier<WorkflowRuntimeStore> novo) {
        WorkflowRuntimeStore store = novo.get();
        String id = nova(store);
        store.completeExecution(id, "COMPLETED", null);

        store.cancelExecution(id);

        assertThat(store.getExecution(DefaultTenant.ID, id)).containsEntry("status", "COMPLETED")
                .doesNotContainKey(WorkflowRuntimeStore.CANCELLED_AT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copia(Map<String, Object> execucao) {
        Map<String, Object> out = new LinkedHashMap<>(execucao);
        List<Map<String, Object>> passos = new ArrayList<>();
        ((List<Map<String, Object>>) execucao.get("steps")).forEach(p -> passos.add(new LinkedHashMap<>(p)));
        out.put("steps", passos);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> passo(Map<String, Object> execucao, String stepId) {
        return ((List<Map<String, Object>>) execucao.get("steps")).stream()
                .filter(p -> stepId.equals(p.get("stepId"))).findFirst().orElseThrow();
    }
}
