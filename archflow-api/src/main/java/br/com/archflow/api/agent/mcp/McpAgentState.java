package br.com.archflow.api.agent.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrato serializável de um laço de tool-calling suspenso.
 *
 * <p>O laço mantinha tudo em heap: um crash no meio de oito turnos perdia o
 * raciocínio inteiro, e não havia como suspender para uma decisão humana
 * <em>dentro</em> de uma conversa — só entre steps do grafo. Era a fronteira que
 * decidia se um orquestrador durável externo era necessário.
 *
 * <p>Este record é essa fronteira. Ele é o que o {@link McpAgentStateStore}
 * persiste — e é exatamente o que um orquestrador externo persistiria no lugar
 * dele, então adotar um depois não muda o laço, só quem guarda o estado.
 *
 * <p>Detalhes que <b>precisam</b> estar aqui, e o motivo:
 * <ul>
 *   <li>{@code fenceNonce} — a regra da cerca vive na mensagem de sistema já
 *       persistida. Gerar um nonce novo na retomada faria o conteúdo cercado
 *       depois não casar com a regra de antes, e a cerca deixaria de valer;</li>
 *   <li>{@code messages} — o transcript em JSON canônico da LangChain4j (via
 *       {@code ChatMessageCodec}), o mesmo formato dos adapters de memória;</li>
 *   <li>{@code iteration} — a retomada continua a contagem, senão um fluxo
 *       suspenso e retomado várias vezes escaparia do {@code maxIterations};</li>
 *   <li>{@code pending} — a chamada que aguarda decisão. Sem ela a retomada não
 *       saberia o que executar.</li>
 * </ul>
 *
 * @param runId       identificador da execução (chave no store)
 * @param tenantId    tenant, para resolver modelo e chave na retomada
 * @param systemPrompt prompt de sistema original, já com a regra da cerca
 * @param fenceNonce  nonce da cerca desta execução
 * @param messages    transcript serializado, em ordem
 * @param toolCalls   chamadas já executadas, em ordem
 * @param iteration   turnos já consumidos
 * @param lastText    último texto do assistente
 * @param pending     chamada aguardando decisão humana
 * @param flowLLMConfig patch de LLM do fluxo, para retomada sem mudar de modelo
 * @param stepLLMConfig patch de LLM do passo, para retomada sem mudar de modelo
 * @param retomada    o que a retomada precisa reconstruir: servidor, allowlist, gate, teto de
 *                    voltas. Sem isto, o estado é um beco sem saída — ver {@link Retomada}
 * @param suspendedAt quando o laço suspendeu
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpAgentState(
        String runId,
        String tenantId,
        String systemPrompt,
        String fenceNonce,
        List<String> messages,
        List<SerializedToolCall> toolCalls,
        int iteration,
        String lastText,
        PendingApproval pending,
        Map<String, Object> flowLLMConfig,
        Map<String, Object> stepLLMConfig,
        Retomada retomada,
        Instant suspendedAt) {

    /**
     * O que a retomada precisa saber, em <b>dados</b> — as políticas do laço são funções, e função
     * não sobrevive a um restart.
     *
     * <p>Sem isto, quem retoma teria de adivinhar a allowlist. Adivinhar para mais devolve ao agente
     * tools que ele não tinha quando suspendeu (o gate viraria uma porta de entrada); adivinhar para
     * menos quebra a execução no meio. Por isso o laço <b>recusa suspender</b> sem estes dados: um
     * estado que ninguém consegue retomar é lixo durável, e foi o que aconteceu até 18/09/2026 —
     * {@code McpAgentRunner.resume} não tinha um único chamador.</p>
     *
     * @param serverRef            referência do servidor MCP, como o host a resolve ({@code null} =
     *                             o default do host)
     * @param toolsPermitidas      a allowlist; {@code null} significa "todas", que é diferente de
     *                             conjunto vazio ("nenhuma")
     * @param toolsComAprovacao     as tools sob gate humano — na retomada o gate continua valendo,
     *                             senão a segunda chamada passaria direto
     * @param saidaPorTool         tools que o passo exige como saída
     * @param tier                 tier de LLM pedido por quem acionou
     * @param maxIteracoes         teto de voltas do laço
     * @param codigosQueEncerram   códigos de erro que encerram o laço
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Retomada(String serverRef, Set<String> toolsPermitidas,
                           Set<String> toolsComAprovacao, Set<String> saidaPorTool,
                           String tier, int maxIteracoes, Set<Integer> codigosQueEncerram) {

        public Retomada {
            toolsPermitidas = toolsPermitidas == null ? null : Set.copyOf(toolsPermitidas);
            toolsComAprovacao = toolsComAprovacao == null ? Set.of() : Set.copyOf(toolsComAprovacao);
            saidaPorTool = saidaPorTool == null ? Set.of() : Set.copyOf(saidaPorTool);
            codigosQueEncerram = codigosQueEncerram == null ? Set.of() : Set.copyOf(codigosQueEncerram);
            if (maxIteracoes <= 0) {
                throw new IllegalArgumentException("maxIteracoes deve ser positivo");
            }
        }
    }

    public McpAgentState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        flowLLMConfig = flowLLMConfig == null ? Map.of() : Map.copyOf(flowLLMConfig);
        stepLLMConfig = stepLLMConfig == null ? Map.of() : Map.copyOf(stepLLMConfig);
    }

    /** Compatibilidade com estados e chamadores anteriores aos patches de LLM duráveis. */
    public McpAgentState(String runId, String tenantId, String systemPrompt, String fenceNonce,
                         List<String> messages, List<SerializedToolCall> toolCalls, int iteration,
                         String lastText, PendingApproval pending, Instant suspendedAt) {
        this(runId, tenantId, systemPrompt, fenceNonce, messages, toolCalls, iteration, lastText,
                pending, Map.of(), Map.of(), null, suspendedAt);
    }

    /** Compatibilidade com estados gravados antes de a retomada ser possível. */
    public McpAgentState(String runId, String tenantId, String systemPrompt, String fenceNonce,
                         List<String> messages, List<SerializedToolCall> toolCalls, int iteration,
                         String lastText, PendingApproval pending,
                         Map<String, Object> flowLLMConfig, Map<String, Object> stepLLMConfig,
                         Instant suspendedAt) {
        this(runId, tenantId, systemPrompt, fenceNonce, messages, toolCalls, iteration, lastText,
                pending, flowLLMConfig, stepLLMConfig, null, suspendedAt);
    }

    /**
     * Chamada de tool aguardando decisão humana.
     *
     * @param requestId  id que o decisor referencia
     * @param toolName   tool que o modelo quer chamar
     * @param arguments  argumentos que o modelo emitiu (já validados contra o schema)
     * @param toolCallId id da chamada no protocolo do modelo — a resposta tem de
     *                   citá-lo, senão o modelo não liga o resultado ao pedido
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingApproval(String requestId, String toolName,
                                  Map<String, Object> arguments, String toolCallId) {
        public PendingApproval {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** {@link McpAgentRunner.ToolCall} em forma serializável. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SerializedToolCall(String name, Map<String, Object> arguments,
                                     String resultText, boolean isError, ToolTrust trust) {
        public SerializedToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }

        static SerializedToolCall from(McpAgentRunner.ToolCall call) {
            return new SerializedToolCall(call.name(), call.arguments(),
                    call.resultText(), call.isError(), call.trust());
        }

        McpAgentRunner.ToolCall toToolCall() {
            return new McpAgentRunner.ToolCall(name, arguments, resultText, isError,
                    trust == null ? ToolTrust.UNTRUSTED : trust);
        }
    }
}
