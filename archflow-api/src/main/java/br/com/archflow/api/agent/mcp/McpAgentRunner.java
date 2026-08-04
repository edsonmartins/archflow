package br.com.archflow.api.agent.mcp;

import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.api.trust.UntrustedContentFence;
import br.com.archflow.langchain4j.core.memory.ChatMessageCodec;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.config.LLMConfigPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Loop de tool-calling NATIVO server-side — o motor que faltava no ArchFlow
 * (o AG-UI é single-turn e delega ao browser; o conversation é textual). Dado
 * um MCP client conectado (ex.: o VendaX Core), resolve um {@link ChatModel}
 * tenant-aware, expõe as tools do server como {@link ToolSpecification} e roda
 * o multi-turn: modelo → tool call → execução MCP → resultado de volta ao
 * modelo, até não haver mais chamadas ou atingir {@code maxIterations}.
 *
 * <p>Usa o {@link McpClient} diretamente (um server por execução, escopo por
 * tenant) — a {@code McpToolRegistry} multiplexa vários servers, camada que
 * não é necessária aqui.
 *
 * <p>Toda execução exige uma {@link ToolAccessPolicy}, aplicada em dois pontos:
 * ao montar o catálogo enviado ao modelo e novamente antes de cada
 * {@code callTool}. A segunda checagem não é redundante — o modelo pode emitir
 * um nome de tool que não estava no catálogo.
 *
 * <p>O resultado de cada tool carrega um {@link ToolTrust} decidido pela
 * {@link ToolTrustPolicy} (default: tudo que vem do server é
 * {@link ToolTrust#UNTRUSTED}). Conteúdo não-confiável volta ao modelo dentro de
 * uma {@link UntrustedContentFence} com nonce por execução, e o system prompt
 * ganha a regra que diz ao modelo para tratar o que está cercado como dado.
 * Sem isso, uma linha de log com "ignore as instruções anteriores" chega ao
 * modelo com o mesmo status da instrução do operador.
 *
 * <h2>Gate humano dentro do raciocínio</h2>
 * A {@link ToolApprovalPolicy} pode exigir decisão humana para uma tool. Quando
 * exige, o laço <b>suspende antes de executá-la</b> — pedir aprovação depois de
 * rodar a tool não é aprovação, é notificação — persiste o
 * {@link McpAgentState} no {@link McpAgentStateStore} e devolve um
 * {@link Result} suspenso. {@link #resume} continua de onde parou.
 *
 * <p>Isto era o limite estrutural do runtime: o gate humano existia apenas como
 * <em>aresta do grafo</em>, então dava para aprovar entre passos, não no meio de
 * uma conversa — que é onde uma remediação de fato é proposta. O transcript
 * vivia em heap e um crash perdia o raciocínio inteiro.
 *
 * <p>O {@link McpAgentState} é a fronteira de durabilidade: é o que o store
 * persiste, e é exatamente o que um orquestrador durável externo persistiria no
 * lugar dele — adotar um depois troca quem guarda o estado, não o laço.
 */
public class McpAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(McpAgentRunner.class);
    /** Visível ao pacote: o {@link McpAgentComponent} usa o mesmo default quando o nó não o declara. */
    static final int DEFAULT_MAX_ITERATIONS = 8;
    private static final long LIST_TOOLS_TIMEOUT_SECONDS = 15;
    private static final long CALL_TOOL_TIMEOUT_SECONDS = 120;

    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final MetricsCollector metrics;
    private final int catalogWarnTokens;
    private final McpAgentStateStore stateStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpAgentRunner(LLMConfigResolver llmConfigResolver, ResolvedLLMConfig platformDefault) {
        this(llmConfigResolver, platformDefault, null, 0);
    }

    public McpAgentRunner(LLMConfigResolver llmConfigResolver, ResolvedLLMConfig platformDefault,
                          MetricsCollector metrics) {
        this(llmConfigResolver, platformDefault, metrics, 0);
    }

    public McpAgentRunner(LLMConfigResolver llmConfigResolver, ResolvedLLMConfig platformDefault,
                          MetricsCollector metrics, int catalogWarnTokens) {
        this(llmConfigResolver, platformDefault, metrics, catalogWarnTokens, null);
    }

    /**
     * @param metrics           coletor compartilhado com o engine; {@code null}
     *                          desliga a instrumentação
     * @param catalogWarnTokens acima disto, o catálogo de tools gera aviso com os
     *                          maiores contribuintes; {@code 0} desliga o aviso
     *                          (a medição continua)
     * @param stateStore        onde o laço suspenso é persistido. {@code null}
     *                          desabilita a suspensão para aprovação — suspender
     *                          sem store daria uma pausa que não sobrevive a
     *                          restart, que é justamente o único motivo de
     *                          suspender.
     */
    public McpAgentRunner(LLMConfigResolver llmConfigResolver, ResolvedLLMConfig platformDefault,
                          MetricsCollector metrics, int catalogWarnTokens,
                          McpAgentStateStore stateStore) {
        this.llmConfigResolver = llmConfigResolver;
        this.platformDefault = platformDefault;
        this.metrics = metrics;
        this.catalogWarnTokens = Math.max(0, catalogWarnTokens);
        this.stateStore = stateStore;
    }

    /**
     * Uma chamada de tool executada no loop (para o chamador extrair, ex., o quote).
     *
     * <p>{@code resultText} é o payload <b>cru</b> devolvido pela tool — sem a
     * cerca que vai ao modelo. Quem consome programaticamente (extrair um quote,
     * auditar) quer o dado; a cerca é um detalhe do canal com o LLM.
     * {@code trust} registra como o conteúdo foi tratado.
     */
    public record ToolCall(String name, Map<String, Object> arguments, String resultText,
                           boolean isError, ToolTrust trust) {
    }

    /**
     * Resultado do loop: texto final do assistente + as tools executadas na ordem.
     *
     * <p>{@code pendingApproval} não-nulo significa que o laço <b>suspendeu</b>
     * antes de executar uma tool e o estado foi persistido: o chamador precisa
     * levar a decisão a um humano e depois chamar {@link #resume}. Tratar um
     * resultado suspenso como final entregaria ao usuário uma resposta parcial
     * como se fosse a conclusão.
     */
    public record Result(String finalText, List<ToolCall> toolCalls,
                         McpAgentState.PendingApproval pendingApproval) {

        public Result(String finalText, List<ToolCall> toolCalls) {
            this(finalText, toolCalls, null);
        }

        static Result finished(String finalText, List<ToolCall> toolCalls) {
            return new Result(finalText, List.copyOf(toolCalls), null);
        }

        static Result suspended(String finalText, List<ToolCall> toolCalls,
                                McpAgentState.PendingApproval pending) {
            return new Result(finalText, List.copyOf(toolCalls), pending);
        }

        /** {@code true} quando o laço parou esperando uma decisão humana. */
        public boolean isSuspended() {
            return pendingApproval != null;
        }

        /** Última chamada (bem-sucedida) da tool de nome {@code name}, se houver. */
        public ToolCall lastSuccessfulCall(String name) {
            ToolCall found = null;
            for (ToolCall c : toolCalls) {
                if (c.name().equals(name) && !c.isError()) {
                    found = c;
                }
            }
            return found;
        }
    }

    public Result run(String tenantId, String systemPrompt, String userMessage,
                      McpClient client, ToolAccessPolicy policy) {
        return run(tenantId, systemPrompt, userMessage, client, policy,
                ToolTrustPolicy.untrustedByDefault(), DEFAULT_MAX_ITERATIONS);
    }

    public Result run(String tenantId, String systemPrompt, String userMessage,
                      McpClient client, ToolAccessPolicy policy, int maxIterations) {
        return run(tenantId, systemPrompt, userMessage, client, policy,
                ToolTrustPolicy.untrustedByDefault(), maxIterations);
    }

    public Result run(String tenantId, String systemPrompt, String userMessage,
                      McpClient client, ToolAccessPolicy policy, ToolTrustPolicy trustPolicy,
                      int maxIterations) {
        return run(tenantId, systemPrompt, userMessage, client,
                new Options(policy, trustPolicy, ToolApprovalPolicy.none(), maxIterations));
    }

    /** Executa o laço com as três políticas explícitas. */
    public Result run(String tenantId, String systemPrompt, String userMessage,
                      McpClient client, Options options) {
        Objects.requireNonNull(options, "options é obrigatória").validate();

        Session session = new Session(
                java.util.UUID.randomUUID().toString(),
                tenantId,
                systemPrompt,
                UntrustedContentFence.create(),
                options.flowPatch(), options.stepPatch());
        // A regra sobre a cerca vai na mensagem de SISTEMA — o lado confiável da
        // conversa. Anunciá-la de dentro do próprio conteúdo cercado seria pedir
        // ao dado que se autodeclare inofensivo.
        session.messages.add(SystemMessage.from(systemPrompt + "\n" + session.fence.preamble()));
        session.messages.add(UserMessage.from(userMessage));

        return drive(session, client, options);
    }

    /**
     * Retoma um laço suspenso, aplicando a decisão humana.
     *
     * <p>É o outro lado do gate <em>dentro</em> do raciocínio: o laço parou antes
     * de executar a tool, o estado foi persistido e este método continua de onde
     * parou. O {@code client} vem do chamador porque é ele que sabe a qual MCP
     * server esta execução pertence — o runner é agnóstico de server.
     *
     * @param requestId        id da solicitação que o decisor referencia
     * @param approved         {@code false} devolve a recusa ao modelo em vez de
     *                         executar; o modelo pode reagir (propor outra coisa)
     * @param editedArguments  argumentos ajustados pelo humano, ou {@code null}
     *                         para usar os que o modelo emitiu
     * @throws java.util.NoSuchElementException se o id é desconhecido
     */
    public Result resume(String requestId, boolean approved,
                         Map<String, Object> editedArguments,
                         McpClient client, Options options) {
        Objects.requireNonNull(options, "options é obrigatória").validate();
        McpAgentState state = requireStore().findByRequestId(requestId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "Nenhum laço suspenso para a aprovação " + requestId));

        Session session = Session.from(state);
        McpAgentState.PendingApproval pending = state.pending();
        Map<String, Object> args = editedArguments != null ? editedArguments : pending.arguments();

        if (approved) {
            // A política de acesso é reconferida na retomada: a autorização pode
            // ter sido revogada entre a suspensão e a decisão.
            executeAndAppend(session, client, options, pending.toolName(),
                    pending.toolCallId(), args);
        } else {
            log.info("Aprovação {} recusada; devolvendo a recusa ao modelo (run={})",
                    requestId, session.runId);
            appendToolResult(session, pending.toolCallId(), pending.toolName(),
                    "RECUSADO por decisão humana: a chamada de " + pending.toolName()
                            + " não foi executada.",
                    true, ToolTrust.TRUSTED);
        }

        // O estado suspenso deixa de existir: a decisão foi consumida. Se o laço
        // suspender de novo, um estado novo é gravado com outro requestId.
        requireStore().delete(session.runId);
        return drive(session, client, options);
    }

    // ── Laço ─────────────────────────────────────────────────────────

    /**
     * Conduz o laço até uma resposta final, uma suspensão ou o teto de iterações.
     * Compartilhado por {@link #run} e {@link #resume} — foi extraído justamente
     * para que a retomada não fosse uma segunda implementação do mesmo laço, que
     * divergiria com o tempo.
     */
    private Result drive(Session session, McpClient client, Options options) {
        ChatModel model = llmConfigResolver.resolveModel(
                LLMResolutionRequest.builder(platformDefault)
                        .tenantId(session.tenantId)
                        .flowPatch(session.flowPatch)
                        .stepPatch(session.stepPatch)
                        .build());

        List<ToolSpecification> tools;
        // Schemas guardados por nome: o mesmo contrato que descrevemos ao modelo
        // é o que conferimos na volta.
        Map<String, Map<String, Object>> schemas = new HashMap<>();
        try {
            List<McpModel.Tool> allowed =
                    allowedTools(await(client.listTools(), LIST_TOOLS_TIMEOUT_SECONDS,
                                    "listar tools"),
                            options.access(), session.tenantId);
            allowed.forEach(t -> schemas.put(t.name(), t.inputSchema()));
            accountForCatalog(allowed, session.tenantId);
            tools = McpToolSpecifications.from(allowed);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao listar tools do MCP server: " + e.getMessage(), e);
        }

        while (session.iteration < options.maxIterations()) {
            session.iteration++;
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(session.messages)
                    .toolSpecifications(tools)
                    .build());
            recordTurn(response);
            AiMessage ai = response.aiMessage();
            session.messages.add(ai);
            if (ai.text() != null) {
                session.lastText = ai.text();
            }

            if (!ai.hasToolExecutionRequests()) {
                // O MODELO CONCLUIU SEM PRODUZIR O ARTEFATO — cobra uma vez antes de desistir.
                //
                // Um nó pode declarar que a resposta do passo sai de uma tool específica
                // (`requiredOutputTools`). Quando o modelo termina falando em vez de chamá-la, o
                // trabalho todo — que pode ter sido muitas tool calls corretas — é jogado fora, e
                // quem chamou recebe um texto onde esperava um artefato.
                //
                // Medido no VendaX em 03-04/08: de sete execuções de um agente de cotação, TRÊS
                // terminaram assim. Ele resolvia os cinco produtos certos e não chamava a tool que
                // monta a cotação. O prompt já mandava, em maiúsculas, e não bastava.
                //
                // Cobrar é barato: o histórico já está montado, e um turno a mais custa muito
                // menos que a execução inteira perdida. UMA vez só — se o modelo insistir, quem
                // decide o que fazer é o chamador, que conhece o significado da tool.
                if (deveCobrarSaida(session, options)) {
                    session.cobrouSaida = true;
                    log.info("Modelo concluiu sem chamar nenhuma tool de saída {} — cobrando uma vez",
                            options.requiredOutputTools());
                    session.messages.add(UserMessage.from(cobrancaDeSaida(options.requiredOutputTools())));
                    continue;
                }
                return Result.finished(session.lastText, session.toolCalls);
            }

            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                // Gate humano: suspende ANTES de executar. Pedir aprovação depois
                // de rodar a tool não é aprovação, é notificação.
                if (options.approval().requiresApproval(req.name())
                        && options.access().isAllowed(req.name())) {
                    return suspend(session, req, schemas);
                }
                ParsedArguments parsed = parseAndValidate(req, schemas);
                if (!parsed.valid()) {
                    // A tool NÃO roda; o modelo recebe o motivo exato e pode
                    // corrigir. Antes desta checagem, JSON bem formado e errado
                    // chegava à tool e virava erro do server — ou execução com
                    // semântica diferente da pretendida.
                    rejectCall(session, req.id(), req.name(),
                            invalidArgumentsMessage(req.name(), parsed.error()));
                    continue;
                }
                executeAndAppend(session, client, options, req.name(), req.id(), parsed.args());
            }
        }

        log.warn("Loop de tool-calling atingiu maxIterations={} sem resposta final",
                options.maxIterations());
        return Result.finished(session.lastText, session.toolCalls);
    }

    /** Exige saída por tool, ainda não cobrou, e nenhuma das exigidas foi chamada com sucesso. */
    private static boolean deveCobrarSaida(Session session, Options options) {
        if (options.requiredOutputTools().isEmpty() || session.cobrouSaida) {
            return false;
        }
        for (ToolCall c : session.toolCalls) {
            if (!c.isError() && options.requiredOutputTools().contains(c.name())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A cobrança vai como mensagem de USUÁRIO, não de sistema.
     *
     * <p>O system prompt já foi entregue no início e o modelo o desobedeceu; repeti-lo no mesmo
     * canal seria dizer mais alto a mesma coisa. Como turno de usuário, ela entra no fim da
     * conversa, referindo-se ao que acabou de acontecer — que é onde a instrução tem mais peso.</p>
     */
    private static String cobrancaDeSaida(Set<String> exigidas) {
        return "Você terminou sem chamar nenhuma destas tools: " + String.join(", ", exigidas)
                + ". A resposta deste passo sai do resultado de uma delas — texto não serve. "
                + "Chame a apropriada agora, com o que você já apurou.";
    }

    /**
     * Persiste o laço e devolve um resultado suspenso.
     *
     * <p>Os argumentos são validados <b>antes</b> de suspender: mandar um humano
     * decidir sobre uma chamada que nem passaria na validação desperdiça o tempo
     * dele e ensina o modelo errado.
     */
    private Result suspend(Session session, ToolExecutionRequest req,
                           Map<String, Map<String, Object>> schemas) {
        Map<String, Object> args;
        try {
            args = parseArguments(req.arguments());
            List<String> violations = ToolArgumentValidator.validate(schemas.get(req.name()), args);
            if (!violations.isEmpty()) {
                throw new MalformedToolArgumentsException(String.join("; ", violations));
            }
        } catch (MalformedToolArgumentsException e) {
            // Não suspende: devolve o erro e deixa o modelo corrigir.
            rejectCall(session, req.id(), req.name(),
                    invalidArgumentsMessage(req.name(), e.getMessage()));
            return Result.finished(session.lastText, session.toolCalls);
        }

        String requestId = java.util.UUID.randomUUID().toString();
        McpAgentState state = session.snapshot(new McpAgentState.PendingApproval(
                requestId, req.name(), args, req.id()));
        requireStore().save(state);
        log.info("Laço {} suspenso aguardando aprovação de {} (requestId={})",
                session.runId, req.name(), requestId);
        return Result.suspended(session.lastText, session.toolCalls, state.pending());
    }

    private static String invalidArgumentsMessage(String toolName, String detail) {
        return "ERRO: argumentos inválidos para a tool " + toolName
                + " (" + detail + "). Corrija e repita a chamada.";
    }

    /**
     * Registra uma chamada que <b>não</b> foi executada, do lado do chamador e do
     * lado do modelo, com a mesma mensagem. Um erro visível só num dos dois lados
     * deixa auditoria e modelo com histórias diferentes.
     */
    private void rejectCall(Session session, String toolCallId, String toolName, String message) {
        session.toolCalls.add(new ToolCall(toolName, Map.of(), message, true, ToolTrust.TRUSTED));
        appendToolResult(session, toolCallId, toolName, message, true, ToolTrust.TRUSTED);
        recordToolCall(toolName, System.nanoTime(), false);
    }

    /** Executa a tool e acrescenta o resultado ao transcript. */
    private void executeAndAppend(Session session, McpClient client, Options options,
                                  String toolName, String toolCallId, Map<String, Object> args) {
        long startedAt = System.nanoTime();
        String resultText;
        boolean isError = true;
        // Texto de erro é nosso, não do server: não é conteúdo de terceiro e não
        // entra na cerca. Só o payload de uma execução bem-sucedida é que
        // carrega a marca da política.
        ToolTrust trust = ToolTrust.TRUSTED;
        Map<String, Object> effectiveArgs = args == null ? Map.of() : args;

        if (!options.access().isAllowed(toolName)) {
            // Segunda checagem da política: o catálogo enviado ao modelo já estava
            // filtrado, mas um modelo pode emitir um nome que não estava lá — e na
            // retomada a autorização pode ter sido revogada. A tool NÃO é executada.
            resultText = "ERRO: a tool '" + toolName + "' não está autorizada para este agente.";
            log.warn("Tool '{}' negada pela política de acesso (tenant={})",
                    toolName, session.tenantId);
        } else {
            try {
                McpModel.ToolResult tr = await(client.callTool(
                                new McpModel.ToolArguments(toolName, effectiveArgs)),
                        CALL_TOOL_TIMEOUT_SECONDS, "executar a tool " + toolName);
                resultText = textOf(tr);
                isError = tr.isError();
                if (!isError) {
                    trust = options.trust().trustOf(toolName);
                }
            } catch (Exception e) {
                resultText = "ERRO ao executar a tool " + toolName + ": " + e.getMessage();
                log.warn("Falha na tool MCP {}: {}", toolName, e.getMessage());
            }
        }

        recordToolCall(toolName, startedAt, !isError);
        session.toolCalls.add(new ToolCall(toolName, effectiveArgs, resultText, isError, trust));
        appendToolResult(session, toolCallId, toolName, resultText, isError, trust);
    }

    /**
     * Argumentos parseados, ou o motivo pelo qual não servem.
     *
     * <p>Carrega o {@code error} em vez de uma sentinela porque a mensagem
     * <b>específica</b> é o que permite ao modelo corrigir no turno seguinte —
     * um "argumentos inválidos" genérico o deixa adivinhando.
     */
    private record ParsedArguments(Map<String, Object> args, String error) {
        boolean valid() {
            return error == null;
        }

        static ParsedArguments ok(Map<String, Object> args) {
            return new ParsedArguments(args, null);
        }

        static ParsedArguments invalid(String error) {
            return new ParsedArguments(Map.of(), error);
        }
    }

    private ParsedArguments parseAndValidate(ToolExecutionRequest req,
                                             Map<String, Map<String, Object>> schemas) {
        try {
            Map<String, Object> args = parseArguments(req.arguments());
            List<String> violations = ToolArgumentValidator.validate(schemas.get(req.name()), args);
            if (!violations.isEmpty()) {
                String detail = String.join("; ", violations);
                log.warn("Argumentos inválidos para a tool MCP {}: {}", req.name(), detail);
                return ParsedArguments.invalid(detail);
            }
            return ParsedArguments.ok(args);
        } catch (MalformedToolArgumentsException e) {
            log.warn("Argumentos inválidos para a tool MCP {}: {}", req.name(), e.getMessage());
            return ParsedArguments.invalid(e.getMessage());
        }
    }

    /** O chamador recebe o payload cru; o modelo recebe o cercado. */
    private void appendToolResult(Session session, String toolCallId, String toolName,
                                  String resultText, boolean isError, ToolTrust trust) {
        String forModel = trust == ToolTrust.UNTRUSTED
                ? session.fence.wrap(toolName, resultText)
                : resultText;
        session.messages.add(ToolExecutionResultMessage.from(
                ToolExecutionRequest.builder().id(toolCallId).name(toolName).arguments("{}").build(),
                forModel));
        if (isError) {
            log.debug("Resultado de erro da tool {} devolvido ao modelo", toolName);
        }
    }

    private McpAgentStateStore requireStore() {
        if (stateStore == null) {
            throw new IllegalStateException(
                    "Suspensão para aprovação exige um McpAgentStateStore — "
                            + "sem store o laço não sobreviveria a um restart, o que é o "
                            + "único motivo de suspender");
        }
        return stateStore;
    }

    /**
     * Estado vivo de um laço. Existe para que {@link #run} e {@link #resume}
     * compartilhem o mesmo laço, e para converter de/para {@link McpAgentState}
     * num só lugar.
     */
    private static final class Session {
        private final String runId;
        private final String tenantId;
        private final String systemPrompt;
        private final UntrustedContentFence fence;
        private final LLMConfigPatch flowPatch;
        private final LLMConfigPatch stepPatch;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private int iteration;
        /** Só se cobra a saída uma vez — insistir viraria laço com o modelo. */
        private boolean cobrouSaida;
        private String lastText = "";

        Session(String runId, String tenantId, String systemPrompt, UntrustedContentFence fence,
                LLMConfigPatch flowPatch, LLMConfigPatch stepPatch) {
            this.runId = runId;
            this.tenantId = tenantId;
            this.systemPrompt = systemPrompt;
            this.fence = fence;
            this.flowPatch = flowPatch == null ? LLMConfigPatch.empty() : flowPatch;
            this.stepPatch = stepPatch == null ? LLMConfigPatch.empty() : stepPatch;
        }

        static Session from(McpAgentState state) {
            // O nonce vem do estado: gerar um novo faria o conteúdo cercado depois
            // não casar com a regra já persistida na mensagem de sistema.
            Session session = new Session(state.runId(), state.tenantId(), state.systemPrompt(),
                    UntrustedContentFence.withNonce(state.fenceNonce()),
                    LLMConfigPatch.fromMap(state.flowLLMConfig()),
                    LLMConfigPatch.fromMap(state.stepLLMConfig()));
            for (String encoded : state.messages()) {
                ChatMessage message = ChatMessageCodec.fromJson(encoded);
                if (message != null) {
                    session.messages.add(message);
                }
            }
            state.toolCalls().forEach(c -> session.toolCalls.add(c.toToolCall()));
            session.iteration = state.iteration();
            session.lastText = state.lastText() == null ? "" : state.lastText();
            return session;
        }

        McpAgentState snapshot(McpAgentState.PendingApproval pending) {
            List<String> encoded = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                encoded.add(ChatMessageCodec.toJson(message));
            }
            return new McpAgentState(runId, tenantId, systemPrompt, fence.nonce(), encoded,
                    toolCalls.stream().map(McpAgentState.SerializedToolCall::from).toList(),
                    iteration, lastText, pending, flowPatch.toMap(), stepPatch.toMap(),
                    java.time.Instant.now());
        }
    }

    /**
     * As três políticas do laço, o teto de iterações e os patches de LLM.
     *
     * <p>Agrupadas num record porque passar parâmetros posicionais em duas
     * assinaturas (run e resume) convida a trocar a ordem — e trocar
     * {@code access} por {@code approval} seria um erro silencioso e grave.
     */
    public record Options(ToolAccessPolicy access, ToolTrustPolicy trust,
                          ToolApprovalPolicy approval, int maxIterations,
                          LLMConfigPatch flowPatch, LLMConfigPatch stepPatch,
                          Set<String> requiredOutputTools) {

        public Options(ToolAccessPolicy access, ToolTrustPolicy trust,
                       ToolApprovalPolicy approval, int maxIterations) {
            this(access, trust, approval, maxIterations,
                    LLMConfigPatch.empty(), LLMConfigPatch.empty(), Set.of());
        }

        public Options(ToolAccessPolicy access, ToolTrustPolicy trust,
                       ToolApprovalPolicy approval, int maxIterations,
                       LLMConfigPatch flowPatch, LLMConfigPatch stepPatch) {
            this(access, trust, approval, maxIterations, flowPatch, stepPatch, Set.of());
        }

        public Options(ToolAccessPolicy access) {
            this(access, ToolTrustPolicy.untrustedByDefault(), ToolApprovalPolicy.none(),
                    DEFAULT_MAX_ITERATIONS, LLMConfigPatch.empty(), LLMConfigPatch.empty(),
                    Set.of());
        }

        public Options {
            flowPatch = flowPatch == null ? LLMConfigPatch.empty() : flowPatch;
            stepPatch = stepPatch == null ? LLMConfigPatch.empty() : stepPatch;
            requiredOutputTools = requiredOutputTools == null
                    ? Set.of() : Set.copyOf(requiredOutputTools);
        }

        void validate() {
            Objects.requireNonNull(access, "access é obrigatória — use ToolAccessPolicy.allowAll() "
                    + "para declarar explicitamente que o agente pode usar todas as tools do server");
            Objects.requireNonNull(trust, "trust é obrigatória");
            Objects.requireNonNull(approval, "approval é obrigatória — use ToolApprovalPolicy.none() "
                    + "para declarar explicitamente que nenhuma tool exige aprovação");
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations deve ser positivo");
            }
        }
    }

    /**
     * Mede o custo do catálogo e avisa quando ele passa do limite configurado.
     *
     * <p>Não descarta tool nenhuma: cortar em silêncio mudaria a capacidade do
     * agente sem ninguém pedir, e escolher <em>qual</em> cortar exige a seleção
     * semântica que ainda não existe. O aviso nomeia os maiores contribuintes
     * para o operador saber o que enxugar.
     */
    private void accountForCatalog(List<McpModel.Tool> tools, String tenantId) {
        if (tools.isEmpty()) {
            return;
        }
        ToolCatalogBudget.Estimate estimate = ToolCatalogBudget.estimate(tools);
        log.debug("Catálogo de {} tool(s) ≈ {} tokens por turno (tenant={})",
                tools.size(), estimate.totalTokens(), tenantId);
        if (metrics != null) {
            try {
                metrics.recordToolCatalog(tools.size(), estimate.totalTokens());
            } catch (RuntimeException e) {
                log.debug("Falha ao registrar métrica do catálogo: {}", e.getMessage());
            }
        }
        if (catalogWarnTokens > 0 && estimate.totalTokens() > catalogWarnTokens) {
            log.warn("Catálogo de tools ≈ {} tokens por turno (limite de aviso {}, tenant={}). "
                            + "Isso é enviado a CADA turno e disputa a janela com o problema. "
                            + "Maiores contribuintes: {}",
                    estimate.totalTokens(), catalogWarnTokens, tenantId,
                    estimate.topContributors(5));
        }
    }

    /**
     * Latência e sucesso de uma invocação. Uma falha ao instrumentar não pode
     * derrubar a execução do agente — a métrica é observação, não requisito.
     */
    private void recordToolCall(String toolName, long startedAtNanos, boolean success) {
        if (metrics == null) {
            return;
        }
        try {
            metrics.recordToolCall(toolName,
                    (System.nanoTime() - startedAtNanos) / 1_000_000, success);
        } catch (RuntimeException e) {
            log.debug("Falha ao registrar métrica da tool {}: {}", toolName, e.getMessage());
        }
    }

    /** Tokens do turno, quando o provider os reporta. */
    private void recordTurn(ChatResponse response) {
        if (metrics == null || response.tokenUsage() == null) {
            return;
        }
        try {
            TokenUsage usage = response.tokenUsage();
            metrics.recordLlmTurn(
                    usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                    usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
        } catch (RuntimeException e) {
            log.debug("Falha ao registrar tokens do turno: {}", e.getMessage());
        }
    }

    /** Sinaliza que o modelo emitiu argumentos que não são um objeto JSON. */
    static final class MalformedToolArgumentsException extends RuntimeException {
        MalformedToolArgumentsException(String message) {
            super(message);
        }
    }

    /**
     * Argumentos ausentes/vazios são legítimos (tool sem parâmetros) e viram um
     * mapa vazio. Qualquer outro conteúdo que não desserialize como objeto JSON
     * é erro e <b>impede</b> a execução da tool — invocá-la com argumentos vazios
     * seria executar uma ação diferente da que o modelo pediu.
     */
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(argumentsJson, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            throw new MalformedToolArgumentsException(
                    "esperado um objeto JSON, recebido: " + truncate(argumentsJson));
        }
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    /** Espera limitada que preserva interrupção e expõe timeout como causa operacional. */
    private static <T> T await(CompletableFuture<T> future, long timeoutSeconds,
                               String operation) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new McpOperationTimeoutException(operation, timeoutSeconds, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execução interrompida ao " + operation, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Falha ao " + operation + ": " + cause.getMessage(), cause);
        }
    }

    /** Timeout classificável por métricas, logs e políticas de reconexão. */
    public static final class McpOperationTimeoutException extends RuntimeException {
        McpOperationTimeoutException(String operation, long timeoutSeconds, Throwable cause) {
            super("Timeout ao " + operation + " após " + timeoutSeconds + "s", cause);
        }
    }

    /**
     * Catálogo efetivamente enviado ao modelo: só as tools que a política
     * autoriza. O que foi descartado vai para o log — um catálogo silenciosamente
     * reduzido se parece com um server que não expõe a tool.
     */
    private static List<McpModel.Tool> allowedTools(List<McpModel.Tool> discovered,
                                                    ToolAccessPolicy policy,
                                                    String tenantId) {
        List<McpModel.Tool> allowed = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (McpModel.Tool tool : discovered) {
            if (policy.isAllowed(tool.name())) {
                allowed.add(tool);
            } else {
                denied.add(tool.name());
            }
        }
        if (!denied.isEmpty()) {
            log.info("Política de acesso ocultou {} de {} tools do MCP server (tenant={}): {}",
                    denied.size(), discovered.size(), tenantId, denied);
        }
        if (allowed.isEmpty() && !discovered.isEmpty()) {
            log.warn("Nenhuma das {} tools do MCP server é permitida pela política (tenant={}) — "
                    + "o agente rodará sem tools", discovered.size(), tenantId);
        }
        return allowed;
    }

    private String textOf(McpModel.ToolResult result) {
        // O contrato do VendaX Core devolve o payload em content[0].text (JSON
        // string). Concatena todos os textos por robustez.
        StringBuilder sb = new StringBuilder();
        for (McpModel.ToolContent c : result.content()) {
            if (c.text() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(c.text());
            }
        }
        return sb.toString();
    }
}
