package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-116 no motor de verdade: a condição que o "Gerar com IA" montou ({@code ticket.urgente == true})
 * mandava o pedido urgente e o comum para o mesmo ramo ({@code false}), sem erro nem aviso. Agora o fluxo é
 * recusado antes do primeiro passo, nomeando o nó e o campo — e a forma do avaliador separa os dois pedidos.
 */
@DisplayName("condição inválida: recusada antes do 1º passo; a válida escolhe o ramo pelo dado")
class CondicaoInvalidaAntesDoPrimeiroPassoTest {

    private static final String URGENTE = "URGENTE: o sistema caiu e nenhum cliente consegue pagar";
    private static final String COMUM = "Queria saber o horário de atendimento";

    private static DefaultFlowStepFactory factory() {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString())).thenReturn(Optional.empty());
        return new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);
    }

    private static Map<String, Object> no(String id, String type, String label, Map<String, Object> config,
                                          List<Map<String, Object>> arestas) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("label", label);
        node.put("configuration", config);
        node.put("connections", arestas);
        return node;
    }

    /** Receber Ticket → É Urgente? → true: Escalar para Humano / false: Responder. */
    private static Flow fluxo(String expressao) {
        return new DefaultWorkflowDeserializer(factory()).toFlow(Map.of("id", "triagem", "metadata",
                Map.of("name", "triagem"), "steps", List.of(
                        no("receber", "input", "Receber Ticket", Map.of(), List.of(Map.of("targetId", "decide"))),
                        no("decide", "condition", "É Urgente?", Map.of("conditionExpression", expressao),
                                List.of(Map.of("targetId", "humano", "branch", "true"),
                                        Map.of("targetId", "responder", "branch", "false"))),
                        no("humano", "output", "Escalar para Humano", Map.of(), List.of()),
                        no("responder", "output", "Responder", Map.of(), List.of()))));
    }

    private static DefaultExecutionContext contexto(String flowId, String entrada) {
        DefaultExecutionContext ctx = new DefaultExecutionContext("t1", "u1", flowId,
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", entrada);
        return ctx;
    }

    private static FlowEngine engine() {
        return FlowEngineFactory.create(new InMemoryFlowRepository(), null, null, new InMemoryStateManager());
    }

    @Test
    @DisplayName("a condição gerada pelo assistente é recusada antes do primeiro passo, com o nó e o campo")
    void condicaoGeradaRecusadaNoInicio() {
        Flow flow = fluxo("ticket.urgente == true");
        DefaultExecutionContext ctx = contexto(flow.getId(), URGENTE);

        assertThatThrownBy(() -> engine().execute(flow, ctx).get(15, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .rootCause()
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("o nó \"É Urgente?\" (decide), campo conditionExpression:")
                .hasMessageContaining("«ticket.urgente» não é um valor");
        // Nenhum passo rodou: a Entrada, que vem antes da condição, não deixou saída.
        assertThat(ctx.get("receber")).isEmpty();
        assertThat(ctx.get("decide")).isEmpty();
    }

    @Test
    @DisplayName("referência a passo inexistente não é recusada no servidor: resolve se a entrada do run trouxer a chave")
    void passoInexistenteSoAvisaNoServidor() throws Exception {
        // Fluxos criados por API (VendaX Core, YAML, standalone) leem chaves da entrada sem declará-las na
        // Entrada. Recusar ${classificar.urgente} aqui quebraria esses fluxos; quem impede é o editor.
        Flow flow = fluxo("${classificar.urgente} == true");

        DefaultExecutionContext comChave = contexto(flow.getId(), URGENTE);
        comChave.set("classificar", Map.of("urgente", true));
        FlowResult r = engine().execute(flow, comChave).get(15, TimeUnit.SECONDS);

        assertThat(r.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(comChave.get("humano")).isPresent();
        assertThat(comChave.get("responder")).isEmpty();
    }

    @Test
    @DisplayName("na forma do avaliador, o pedido urgente vai ao humano e o comum segue para a resposta")
    void formaDoAvaliadorSeparaOsPedidos() throws Exception {
        Flow flow = fluxo("${receber} contains 'URGENTE'");

        DefaultExecutionContext urgente = contexto(flow.getId(), URGENTE);
        FlowResult r1 = engine().execute(flow, urgente).get(15, TimeUnit.SECONDS);
        DefaultExecutionContext comum = contexto(flow.getId(), COMUM);
        FlowResult r2 = engine().execute(flow, comum).get(15, TimeUnit.SECONDS);

        assertThat(r1.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(r2.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(urgente.get("humano")).isPresent();
        assertThat(urgente.get("responder")).isEmpty();
        assertThat(comum.get("responder")).isPresent();
        assertThat(comum.get("humano")).isEmpty();
    }
}
