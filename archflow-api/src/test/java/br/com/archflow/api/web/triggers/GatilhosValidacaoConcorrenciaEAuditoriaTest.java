package br.com.archflow.api.web.triggers;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.TenantScope;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import br.com.archflow.api.triggers.store.InMemoryScheduledTriggerStore;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-191: a API de gatilhos aceitava nome vazio e agente que não existe (201, e o agente só falhava no
 * disparo); mostrava o texto cru do Quartz, em inglês, para o cron errado; deixava o segundo Salvar de um
 * formulário antigo desfazer em silêncio a edição de outra pessoa ({@code If-Match: "bogus"} passava com 200); e
 * nada ia para a auditoria. Pelo HTTP, com o registro e o Quartz de verdade atrás.
 */
@DisplayName("Gatilhos agendados: validação, edição concorrente e auditoria (RCHFLW-191)")
class GatilhosValidacaoConcorrenciaEAuditoriaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditRepository auditoria = mock(AuditRepository.class);
    private Scheduler scheduler;
    private ScheduledTriggerControllerImpl gatilhos;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "gatilhos-validacao-" + UUID.randomUUID());
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        scheduler = factory.getScheduler();
        scheduler.start();
        Set<String> catalogo = Set.of("conversational-agent");
        gatilhos = new ScheduledTriggerControllerImpl(scheduler, new InMemoryAgentInvocationQueue(), null,
                new InMemoryScheduledTriggerStore(), catalogo::contains);
        mvc = MockMvcBuilders.standaloneSetup(
                        new SpringScheduledTriggerController(gatilhos, new TenantScope(false),
                                new AuditTrail(() -> auditoria)))
                .addFilters(new ImpersonationFilter(false))
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown(false);
    }

    private static String corpo(String nome, String cron, String agente, String payload) {
        return "{\"name\":\"" + nome + "\",\"cronExpression\":\"" + cron + "\",\"tenantId\":\"tenant-a\","
                + "\"agentId\":\"" + agente + "\",\"payload\":" + payload + ",\"enabled\":true}";
    }

    private ResultActions criar(String corpo) throws Exception {
        return mvc.perform(post("/api/admin/triggers").contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode criado() throws Exception {
        return json(criar(corpo("Triagem reembolso", "0 0 3 * * ?", "conversational-agent", "{}"))
                .andExpect(status().isCreated()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "nome vazio         | ''      | 0 0 9 ? * MON-FRI | conversational-agent  | TRIGGER_NAME_REQUIRED",
        "nome em branco     | '   '   | 0 0 9 ? * MON-FRI | conversational-agent  | TRIGGER_NAME_REQUIRED",
        "agente inexistente | Triagem | 0 0 9 ? * MON-FRI | agente-que-nao-existe | TRIGGER_AGENT_UNKNOWN",
        "cron de 5 campos   | Triagem | 0 9 * * *         | conversational-agent  | TRIGGER_CRON_INVALID",
        "dia e semana com * | Triagem | 0 0 9 * * *       | conversational-agent  | TRIGGER_CRON_INVALID",
        "dia em português   | Triagem | 0 0 9 ? * SEG-SEX | conversational-agent  | TRIGGER_CRON_INVALID",
        "30 de fevereiro    | Triagem | 0 0 0 30 2 ?      | conversational-agent  | TRIGGER_CRON_NEVER_FIRES",
        "ano que já passou  | Triagem | 0 0 9 * * ? 2020  | conversational-agent  | TRIGGER_CRON_NEVER_FIRES",
    })
    @DisplayName("criar recusado: 400 com o código que a tela traduz, e nada gravado")
    void criarRecusado(String caso, String nome, String cron, String agente, String codigo) throws Exception {
        JsonNode resposta = json(criar(corpo(nome, cron, agente, "{}")).andExpect(status().isBadRequest()));

        assertThat(resposta.path("code").asText()).as(caso).isEqualTo(codigo);
        assertThat(gatilhos.list()).as(caso).isEmpty();
    }

    @Test
    @DisplayName("editar para nome vazio ou agente inexistente: 400, e o gatilho fica como estava")
    void editarRecusado() throws Exception {
        String id = criado().path("id").asText();

        mvc.perform(put("/api/admin/triggers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("", "0 0 3 * * ?", "conversational-agent", "{}")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/triggers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Triagem", "0 0 3 * * ?", "agente-que-nao-existe", "{}")))
                .andExpect(status().isBadRequest());

        assertThat(gatilhos.get(id).name()).isEqualTo("Triagem reembolso");
        assertThat(gatilhos.get(id).agentId()).isEqualTo("conversational-agent");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"[1,2]", "\"texto solto\""})
    @DisplayName("payload que não é objeto: 400 com mensagem e código, não um Bad Request mudo")
    void payloadQueNaoEObjeto(String payload) throws Exception {
        JsonNode resposta = json(criar(corpo("Triagem", "0 0 3 * * ?", "conversational-agent", payload))
                .andExpect(status().isBadRequest()));

        assertThat(resposta.path("code").asText()).isEqualTo("TRIGGER_BODY_INVALID");
        assertThat(resposta.path("error").asText()).contains("payload");
    }

    @Test
    @DisplayName("edição concorrente: o If-Match antigo recebe 409 e o cron e o payload de B ficam")
    void edicaoConcorrente() throws Exception {
        JsonNode lidoPorA = criado();
        String id = lidoPorA.path("id").asText();
        String versaoDeA = lidoPorA.path("updatedAt").asText();
        assertThat(versaoDeA).isNotBlank();

        mvc.perform(put("/api/admin/triggers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"" + versaoDeA + "\"")
                        .content(corpo("Triagem reembolso", "0 15 22 ? * FRI", "conversational-agent",
                                "{\"de\":\"B\"}")))
                .andExpect(status().isOk());

        JsonNode conflito = json(mvc.perform(put("/api/admin/triggers/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"" + versaoDeA + "\"")
                        .content(corpo("Renomeado por A", "0 0 3 * * ?", "conversational-agent", "{}")))
                .andExpect(status().isConflict()));

        assertThat(conflito.path("code").asText()).isEqualTo("TRIGGER_CONFLICT");
        assertThat(conflito.path("updatedAt").asText()).isNotEqualTo(versaoDeA);
        assertThat(gatilhos.get(id).cronExpression()).isEqualTo("0 15 22 ? * FRI");
        assertThat(gatilhos.get(id).payload()).containsEntry("de", "B");
        assertThat(gatilhos.get(id).name()).isEqualTo("Triagem reembolso");
    }

    @Test
    @DisplayName("If-Match que não é uma versão (\"bogus\") não casa com nenhuma: 409")
    void ifMatchInvalido() throws Exception {
        String id = criado().path("id").asText();

        mvc.perform(put("/api/admin/triggers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"bogus\"")
                        .content(corpo("Outro", "0 0 3 * * ?", "conversational-agent", "{}")))
                .andExpect(status().isConflict());

        assertThat(gatilhos.get(id).name()).isEqualTo("Triagem reembolso");
    }

    @Test
    @DisplayName("a lista traz o próximo disparo e o fuso em que o cron é lido")
    void proximoDisparoEFuso() throws Exception {
        criado();

        JsonNode item = json(mvc.perform(get("/api/admin/triggers")).andExpect(status().isOk())).get(0);

        assertThat(item.path("nextFireTime").isNull()).isFalse();
        assertThat(item.path("nextFireTime").asText()).isNotBlank();
        assertThat(item.path("timeZone").asText()).isEqualTo(java.util.TimeZone.getDefault().getID());
    }

    @Test
    @DisplayName("criar, editar, disparar e excluir entram na auditoria, com o id do gatilho")
    void auditoria() throws Exception {
        String id = criado().path("id").asText();
        mvc.perform(put("/api/admin/triggers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Triagem reembolso", "0 0 4 * * ?", "conversational-agent", "{}")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/triggers/" + id + "/fire")).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/triggers/" + id)).andExpect(status().isNoContent());

        ArgumentCaptor<AuditEvent> eventos = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditoria, atLeastOnce()).save(eventos.capture());
        List<AuditEvent> registrados = eventos.getAllValues();
        assertThat(registrados)
                .extracting(AuditEvent::getAction, AuditEvent::getResourceType, AuditEvent::getResourceId)
                .containsExactly(
                        tuple(AuditAction.CREATE, "trigger", id),
                        tuple(AuditAction.UPDATE, "trigger", id),
                        tuple(AuditAction.AGENT_EXECUTE, "trigger", id),
                        tuple(AuditAction.DELETE, "trigger", id));
        assertThat(registrados.get(2).getContext()).containsKey("requestId");
        assertThat(registrados.get(1).getContext()).containsEntry("cronExpression", "0 0 4 * * ?");
    }

    @Test
    @DisplayName("uma recusa não entra na auditoria como se tivesse gravado")
    void recusaNaoAudita() throws Exception {
        criar(corpo("", "0 0 3 * * ?", "conversational-agent", "{}")).andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(auditoria);
    }
}
