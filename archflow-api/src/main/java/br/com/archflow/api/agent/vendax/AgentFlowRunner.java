package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Executa o <b>documento de fluxo</b> que veio no invoke, sem saber que agente é.
 *
 * <p>É o caminho genérico que a {@code ADR-025} D-1 pede: um documento entra, o motor o percorre, a
 * saída volta. Não há {@code case} por agente, nome de tool nem esquema de negócio aqui — o que o
 * fluxo faz está inteiramente no documento, e quem o escreveu foi o cliente da plataforma.</p>
 *
 * <p><b>A entrada é a variável {@code input} do contexto</b>, não um nó de entrada. O nó {@code input}
 * da paleta é de desenho e não tem componente no motor; quem carrega o dado entre passos é o
 * {@code ComponentStep}, que lê {@code input}, executa e reescreve {@code input} para o passo
 * seguinte.</p>
 */
public class AgentFlowRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentFlowRunner.class);

    /** Teto de segurança quando a política do invoke não declara um. */
    private static final long TIMEOUT_PADRAO_MS = 120_000;

    /** Chave que o {@code ComponentStep} lê como entrada do passo. */
    private static final String ENTRADA = "input";

    /**
     * O que o fluxo produziu.
     *
     * @param texto    saída do último passo, já reduzida a texto
     * @param suspenso o fluxo parou esperando decisão humana — não é conclusão, e o chamador não
     *                 pode tratar {@code texto} como resposta final
     */
    public record Saida(String texto, boolean suspenso) {}

    private final WorkflowDeserializer deserializer;
    private final ObjectProvider<FlowEngine> flowEngine;
    private final FlowRepository flowRepository;
    private final McpAgentHost mcpAgentHost;

    /**
     * O motor vem por {@link ObjectProvider} porque o grafo dele é
     * {@code FlowEngine → FlowRepository → WorkflowDeserializer → FlowStepFactory}: injetar direto
     * arrisca fechar o ciclo no perfil JDBC, que é o de produção.
     */
    public AgentFlowRunner(WorkflowDeserializer deserializer, ObjectProvider<FlowEngine> flowEngine,
                           FlowRepository flowRepository, McpAgentHost mcpAgentHost) {
        this.deserializer = deserializer;
        this.flowEngine = flowEngine;
        this.flowRepository = flowRepository;
        this.mcpAgentHost = mcpAgentHost;
    }

    public Saida executar(VendaxInvoke invoke, Map<String, Object> fluxo, String entrada) {
        // Id único por execução: o motor indexa fluxos ativos por id, e duas execuções do mesmo
        // documento (reentrega do Core, retentativa) colidiriam se compartilhassem o dele.
        String execucaoId = "vendax-" + invoke.agent() + "-" + UUID.randomUUID();

        Map<String, Object> documento = new HashMap<>(fluxo);
        documento.put("id", execucaoId);

        Flow flow = deserializer.toFlow(documento);
        // Registrar é o que permite retomar depois de uma suspensão por aprovação: sem isto, o
        // fluxo suspende de forma durável e não há como continuá-lo.
        flowRepository.save(flow);

        ExecutionContext contexto = new DefaultExecutionContext(
                invoke.tenantId(), "vendax", execucaoId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        contexto.set(ENTRADA, entrada == null ? "" : entrada);
        McpAgentHost.inject(contexto, mcpAgentHost);

        FlowResult resultado;
        try {
            resultado = flowEngine.getObject()
                    .execute(flow, contexto)
                    .get(timeoutDe(invoke), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execução do fluxo interrompida", e);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao executar o fluxo: " + causaDe(e), e);
        }

        if (resultado.getStatus() == ExecutionStatus.FAILED) {
            throw new IllegalStateException("Fluxo falhou: " + errosDe(resultado));
        }

        Object saida = resultado.getOutput().orElse(null);
        log.debug("Fluxo {} concluiu com status {} (conv={})",
                execucaoId, resultado.getStatus(), invoke.conversationId());
        return reduzir(saida);
    }

    /**
     * A saída do último passo como texto.
     *
     * <p>Um passo de componente devolve mapa; o de MCP traz {@code text} e {@code suspended}. Tratar
     * um laço suspenso como conclusão entregaria ao Core uma resposta parcial com cara de final, e
     * a aprovação pendente sumiria sem ninguém a ver — por isso a suspensão sobe explícita.</p>
     */
    @SuppressWarnings("unchecked")
    private Saida reduzir(Object saida) {
        if (saida instanceof Map<?, ?> mapa) {
            Map<String, Object> m = (Map<String, Object>) mapa;
            boolean suspenso = Boolean.TRUE.equals(m.get("suspended"));
            Object texto = m.get("text");
            if (texto == null) {
                texto = m.get(ENTRADA);
            }
            return new Saida(texto == null ? null : String.valueOf(texto), suspenso);
        }
        return new Saida(saida == null ? null : String.valueOf(saida), false);
    }

    private long timeoutDe(VendaxInvoke invoke) {
        var def = invoke.definicao();
        if (def == null || def.politica() == null) {
            return TIMEOUT_PADRAO_MS;
        }
        Object ms = def.politica().get("timeout_ms");
        if (ms instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        return TIMEOUT_PADRAO_MS;
    }

    private String errosDe(FlowResult resultado) {
        if (resultado.getErrors() == null || resultado.getErrors().isEmpty()) {
            return "sem erro registrado";
        }
        return resultado.getErrors().stream()
                .map(ExecutionError::message)
                .collect(Collectors.joining("; "));
    }

    private static String causaDe(Exception e) {
        Throwable causa = e.getCause() != null ? e.getCause() : e;
        return causa.getMessage() == null ? causa.getClass().getSimpleName() : causa.getMessage();
    }
}
