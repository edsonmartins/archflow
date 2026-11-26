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
import java.util.List;
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
     * O {@code text} do invoke, como veio — ao lado de {@link #ENTRADA}, que é a entrada já montada para o agente.
     * Pedido do VendaX (18/10, VEN-397): o {@code state} do nó {@code decision} resolve caminhos do contexto
     * ({@code StateTemplate}), e o nó julgava a entrada inteira em texto. Com o texto e o payload separados, o estado
     * vira objeto — e o classificador do VendaX acertou 35/36 contra 28/36.
     */
    static final String TEXTO = "texto";

    /**
     * O {@code payload} do invoke LIDO COMO OBJETO ({@code ${payload.pergunta}}). Ilegível, ou JSON que não é objeto:
     * fica ausente — o caminho não resolve, e o {@code StateTemplate} já trata o ausente.
     *
     * <p>Chave comum, e não {@code transient}: a {@link #ENTRADA} já leva este mesmo texto e payload ao estado
     * durável; separá-los não expõe nada novo.</p>
     */
    static final String PAYLOAD = "payload";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * O que o fluxo produziu.
     *
     * @param texto    saída do último passo, já reduzida a texto
     * @param suspenso o fluxo parou esperando decisão humana — não é conclusão, e o chamador não
     *                 pode tratar {@code texto} como resposta final
     */
    /**
     * O que o fluxo produziu.
     *
     * <p>As {@code toolCalls} sobem junto com o texto porque o Core confere a frase contra os
     * ARGUMENTOS que de fato foram à tool — "o modelo chama com 850 e relata 800" é o caso que o
     * caminho por {@code case} do NS já tratava (retirado no VEN-1134) e que o caminho
     * genérico descartava aqui, antes mesmo do dispatcher. Sem elas, um agente só sai do
     * {@code switch} perdendo a garantia.</p>
     *
     * @param toolCalls as chamadas do último passo, na ordem; vazia = nenhuma, nunca nula
     * @param encerradoPor a tool cujo código de erro encerrou o laço, ou nulo
     */
    public record Saida(String texto, boolean suspenso, List<VendaxResult.ToolCall> toolCalls,
                        VendaxResult.Encerramento encerradoPor) {

        public Saida {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        /** Compat: saída sem chamadas de tool — a forma que existia antes. */
        public Saida(String texto, boolean suspenso) {
            this(texto, suspenso, List.of(), null);
        }
    }

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
        return executar(invoke, fluxo, entrada, null);
    }

    /**
     * Idem, somando em {@code uso} o que os passos gastarem de modelo.
     *
     * <p>O contador vem de fora porque quem relata o custo é quem monta o resultado — e ele tem de
     * poder lê-lo mesmo quando esta execução termina em exceção.</p>
     */
    public Saida executar(VendaxInvoke invoke, Map<String, Object> fluxo, String entrada,
                          br.com.archflow.api.agent.mcp.ContadorDeUso uso) {
        return executar(invoke, fluxo, entrada, uso, null, false);
    }

    /**
     * Executa com a saída em fluxo ligada.
     *
     * @param saida          para onde vão os pedaços de texto, os avisos de tool e de onde vem o
     *                       cancelamento; nula é a execução de sempre
     * @param transmitirTexto o texto do nó final vai a {@code saida}. Falso para um fluxo de saída
     *                       estruturada: o cancelamento e os avisos de tool continuam valendo, mas
     *                       não se pede JSON parcial.
     */
    public Saida executar(VendaxInvoke invoke, Map<String, Object> fluxo, String entrada,
                          br.com.archflow.api.agent.mcp.ContadorDeUso uso,
                          br.com.archflow.api.agent.mcp.SaidaDeTexto saida, boolean transmitirTexto) {
        // Id único por execução: o motor indexa fluxos ativos por id, e duas execuções do mesmo
        // documento (reentrega do Core, retentativa) colidiriam se compartilhassem o dele.
        //
        // UUID puro, sem prefixo legível: a coluna de estado do motor é varchar(36), do tamanho
        // exato de um UUID. Um prefixo como "vendax-CS-" estourava o limite e o INSERT do
        // checkpoint falhava com "value too long" — o fluxo concluía, o resultado saía, e só a
        // retomada durável ficava quebrada, o que ninguém percebe até precisar dela.
        String execucaoId = UUID.randomUUID().toString();

        Map<String, Object> documento = new HashMap<>(fluxo);
        documento.put("id", execucaoId);
        if (saida != null && transmitirTexto) {
            documento.put("steps", marcandoOsNosFinais(documento.get("steps")));
        }

        Flow flow;
        try {
            flow = deserializer.toFlow(documento);
        } catch (RuntimeException e) {
            // O documento não se lê: repetir o mesmo invoke dá o mesmo erro. A mensagem é a de antes;
            // o que muda é o tipo, que diz ao chamador que não vale tentar de novo (RCHFLW-190).
            throw new FluxoInvalido(e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName(), e);
        }
        // Registrar é o que permite retomar depois de uma suspensão por aprovação: sem isto, o
        // fluxo suspende de forma durável e não há como continuá-lo.
        flowRepository.save(flow);

        ExecutionContext contexto = new DefaultExecutionContext(
                invoke.tenantId(), "vendax", execucaoId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        contexto.set(ENTRADA, entrada == null ? "" : entrada);
        textoEPayload(contexto, invoke);
        // A CORRELAÇÃO ATRAVESSA POR AQUI, e não por ThreadLocal: o passo executa noutra thread
        // (medido: dispatcher em [vendax-agent-N], passo em [virtual-N]). Quem a repõe do outro
        // lado é o McpAgentComponent, já na thread que chama as tools.
        definirSeHouver(contexto, br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.CTX_JANELA,
                invoke.idempotencyKey());
        definirSeHouver(contexto, br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.CTX_TRACE,
                invoke.traceId());
        // A IDENTIDADE ATRAVESSA PELA MESMA PORTA. Se ela ficasse só no ThreadLocal do
        // dispatcher, o header não sairia — é a armadilha que a correlação já pagou uma vez, e
        // aqui ela seria pior: a ausência do header faz o server voltar a confiar no argumento
        // que o modelo escreveu, sem nada indicando que a proteção não estava ativa.
        definirSeHouver(contexto, br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.CTX_CLIENTE,
                invoke.customerRef());
        definirSeHouver(contexto, br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.CTX_VENDEDOR,
                invoke.vendorRef());
        // A ORIGEM ATRAVESSA PELA MESMA PORTA, e a ausência dela é o caso comum: a maioria das
        // conversas não vem de task. Não há nada a repor quando não veio — e é justamente por não
        // haver que este valor NUNCA pode ser o da execução anterior. Ver CorrelacaoMcp.HEADER_TASK.
        definirSeHouver(contexto, br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.CTX_TASK,
                invoke.taskId());
        // O TIER TAMBÉM ATRAVESSA POR AQUI. Ele vinha no invoke desde sempre — decidido pelo
        // Playbook do Core — e era DESCARTADO: nada no archflow chamava invoke.tier(), e o
        // resolvedor de LLM sequer conhecia o conceito. A política de roteamento não tinha
        // efeito, e o contorno foi declarar o modelo direto no nó do fluxo, o que obriga quem
        // decide política a conhecer nome de modelo de provedor.
        definirSeHouver(contexto, br.com.archflow.api.agent.mcp.McpAgentComponent.CTX_TIER,
                invoke.tier());
        McpAgentHost.inject(contexto, mcpAgentHost);
        br.com.archflow.api.agent.mcp.ContadorDeUso.injetar(contexto, uso);
        br.com.archflow.api.agent.mcp.SaidaDeTexto.injetar(contexto, saida);
        // A MEMÓRIA DO CLIENTE vai sob chave transient: é entrada deste invoke, e são dados pessoais.
        // Persistida, iria para o estado durável do fluxo a cada checkpoint. O custo é que um passo
        // executado depois de uma retomada roda sem ela.
        if (!invoke.memoria().isEmpty()) {
            contexto.set(br.com.archflow.api.agent.mcp.McpAgentComponent.CTX_MEMORIA,
                    invoke.memoria());
        }
        // AS IMAGENS vão pela mesma porta e pelo mesmo motivo: entrada deste invoke, possivelmente
        // dado de cliente, fora do estado durável. Pesam centenas de KB — mais um motivo para não
        // ir a checkpoint.
        if (!invoke.imagens().isEmpty()) {
            contexto.set(br.com.archflow.api.agent.mcp.McpAgentComponent.CTX_IMAGENS,
                    invoke.imagens());
        }

        FlowResult resultado;
        java.util.concurrent.CompletableFuture<FlowResult> execucao = null;
        FlowEngine motor = flowEngine.getObject();
        long timeoutMs = timeoutDe(invoke);
        long limite = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            execucao = motor.execute(flow, contexto);
            resultado = execucao.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // INTERROMPER QUEM ESPERA NÃO PARA O MOTOR. O `execucao.cancel(true)` que estava aqui
            // não parava nada: a future vem de supplyAsync, e cancelá-la só a completa — a travessia
            // seguia, e o turno que ela ainda registrava caía num contador que ninguém mais lia
            // (RCHFLW-166/167). Agora o motor é parado de verdade (cooperativo: o passo em curso
            // termina) e esperamos que ele termine, dentro do prazo desta execução: quem monta o
            // result (o CANCELADO) lê o contador já com tudo o que foi gasto. Num cancelamento em
            // fluxo o laço desiste na hora, então a espera é curta.
            //
            // A ESPERA TEM TETO (o timeout_ms), e o motor pode passar dele — uma chamada bloqueante
            // longa. Por isso a exceção leva a execução do motor: quem monta o result corta o
            // contador e pendura NELA o relato APOS_PRAZO do que vier depois (RCHFLW-167). Pendurado
            // na thread que espera, o relato disparava vazio quando ela desistia no teto, e o turno
            // que o motor ainda anotava se perdia.
            pararOMotor(motor, execucaoId);
            if (execucao != null) {
                aguardarOMotor(execucao, limite);
            }
            Thread.currentThread().interrupt();
            throw new ExecucaoEmCurso("Execução do fluxo interrompida", e, execucao);
        } catch (java.util.concurrent.TimeoutException e) {
            // O `politica.timeout_ms` venceu. O ERROR sai agora, com o que foi gasto até aqui; o
            // motor é parado, e o que o passo em curso ainda gastar é relatado quando ele terminar —
            // quem se pendura em `motor()` é o dispatcher (RCHFLW-167).
            //
            // A MENSAGEM DIZ O PRAZO, e não o nome da exceção: "Falha ao executar o fluxo:
            // TimeoutException" não dizia qual prazo venceu nem de quanto era, ao lado do texto claro
            // do `validoAte` (RCHFLW-190). O prefixo é o de sempre.
            pararOMotor(motor, execucaoId);
            throw new PrazoDoFluxo("Falha ao executar o fluxo: não terminou em " + timeoutMs + " ms ("
                    + (timeoutDeclarado(invoke) ? "politica.timeout_ms" : "teto padrão, sem politica.timeout_ms")
                    + ")", e, execucao);
        } catch (Exception e) {
            String mensagem = "Falha ao executar o fluxo: " + causaDe(e);
            // RECUSA NA VALIDAÇÃO É DETERMINÍSTICA: passo inexistente, ciclo, nó solto, fluxo sem
            // passos — o mesmo documento é recusado de novo. A mensagem não muda; o tipo leva
            // `recuperavel: false` à rota em fluxo, que antes dizia "tente de novo" para tudo.
            if (validacaoNaCadeia(e)) {
                throw new FluxoInvalido(mensagem, e);
            }
            throw new IllegalStateException(mensagem, e);
        }

        if (resultado.getStatus() == ExecutionStatus.FAILED) {
            throw new IllegalStateException("Fluxo falhou: " + errosDe(resultado, contexto));
        }
        // PAUSADO É SUSPENSÃO, NÃO SAÍDA VAZIA. Um nó APPROVAL faz o motor devolver PAUSED, sem
        // saída. Tratado como conclusão, virava Saida(null, false) e o Core recebia um ERROR dizendo
        // que o fluxo "não devolveu um JSON" — um erro que não aconteceu, no lugar de uma espera.
        if (resultado.getStatus() == ExecutionStatus.PAUSED) {
            log.info("Fluxo {} do agente {} pausado aguardando decisão (conv={})",
                    execucaoId, invoke.agent(), invoke.conversationId());
            return new Saida(null, true);
        }
        // Cancelado também não é "saída vazia": o motivo certo é o cancelamento.
        if (resultado.getStatus() == ExecutionStatus.CANCELLED) {
            throw new IllegalStateException("Fluxo cancelado antes de concluir");
        }

        Object produzido = resultado.getOutput().orElse(null);
        log.debug("Fluxo {} do agente {} concluiu com status {} (conv={})",
                execucaoId, invoke.agent(), resultado.getStatus(), invoke.conversationId());
        return reduzir(produzido);
    }

    /**
     * Põe o valor no contexto <b>só quando ele existe</b>.
     *
     * <p>Não é preferência de estilo: as variáveis do {@code DefaultExecutionContext} vivem num
     * {@code ConcurrentHashMap}, que lança {@code NullPointerException} em {@code put(k, null)}.
     * Um {@code set} direto derrubava a execução inteira sempre que o campo viesse ausente — e
     * ausente é o caso normal de {@code idempotencyKey}, de {@code customerRef} numa conversa sem
     * vínculo e, agora, de {@code taskId} na maioria das conversas.</p>
     *
     * <p>Ausência é um valor legítimo aqui, e quem lê do outro lado já a trata: sem a chave, o
     * header correspondente não sai.</p>
     */
    /**
     * Uma cópia dos passos em que os agentes <b>finais</b> — os que não têm aresta de saída —
     * levam {@code transmitirTexto}. É o documento do fluxo, e não o motor, quem diz qual nó fala
     * com quem espera: o texto de um passo intermediário é insumo do seguinte.
     *
     * <p>Só se marca a cópia desta execução; o documento guardado não é tocado.</p>
     */
    @SuppressWarnings("unchecked")
    static Object marcandoOsNosFinais(Object passos) {
        if (!(passos instanceof List<?> lista)) {
            return passos;
        }
        List<Object> marcados = new java.util.ArrayList<>();
        for (Object item : lista) {
            if (!(item instanceof Map<?, ?> bruto)) {
                marcados.add(item);
                continue;
            }
            Map<String, Object> passo = new HashMap<>((Map<String, Object>) bruto);
            Object componente = passo.get("componentId") != null ? passo.get("componentId")
                    : passo.get("type");
            if (br.com.archflow.api.agent.mcp.McpAgentComponent.COMPONENT_ID
                    .equals(componente == null ? null : componente.toString())
                    && !temAresta(passo.get("connections"))) {
                Map<String, Object> config = new HashMap<>();
                if (passo.get("config") instanceof Map<?, ?> c) {
                    config.putAll((Map<String, Object>) c);
                }
                config.put(br.com.archflow.api.agent.mcp.McpAgentComponent.CFG_TRANSMITIR_TEXTO, true);
                passo.put("config", config);
            }
            marcados.add(passo);
        }
        return marcados;
    }

    private static boolean temAresta(Object connections) {
        if (!(connections instanceof List<?> lista)) {
            return false;
        }
        for (Object c : lista) {
            if (c instanceof Map<?, ?> m && m.get("targetId") != null
                    && !m.get("targetId").toString().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** O texto e o payload do invoke, separados, no contexto do fluxo (VEN-397 do VendaX) — ver {@link #TEXTO}. */
    static void textoEPayload(ExecutionContext contexto, VendaxInvoke invoke) {
        definirSeHouver(contexto, TEXTO, invoke.text());
        Map<String, Object> payload = payloadComoObjeto(invoke.payload());
        if (payload != null) {
            contexto.set(PAYLOAD, payload);
        }
    }

    /** O payload do invoke como objeto; nulo quando vazio, ilegível ou não é um objeto JSON. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> payloadComoObjeto(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            Object lido = JSON.readValue(payload, Object.class);
            return lido instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (Exception e) {
            log.debug("payload do invoke ilegível — fica fora do contexto: {}", e.getMessage());
            return null;
        }
    }

    private static void definirSeHouver(ExecutionContext contexto, String chave, String valor) {
        if (valor != null && !valor.isBlank()) {
            contexto.set(chave, valor);
        }
    }

    /**
     * A saída do último passo como texto.
     *
     * <p>Um passo de componente devolve mapa; o de MCP traz {@code text} e {@code suspended}; o de
     * decisão traz {@code route} e {@code answers}, e vira o envelope de {@link DecisaoNoResult}.
     * Tratar um laço suspenso como conclusão entregaria ao Core uma resposta parcial com cara de
     * final, e a aprovação pendente sumiria sem ninguém a ver — por isso a suspensão sobe
     * explícita.</p>
     */
    @SuppressWarnings("unchecked")
    private Saida reduzir(Object saida) {
        if (saida instanceof Map<?, ?> mapa) {
            Map<String, Object> m = (Map<String, Object>) mapa;
            boolean suspenso = Boolean.TRUE.equals(m.get("suspended"));
            Object texto = m.get("text");
            if (texto == null && DecisaoNoResult.ehDecisao(m)) {
                // O último passo foi um nó de decisão: a saída dele vai ao Core na forma pedida
                // (VEN-221), em vez de "o fluxo não devolveu um JSON".
                texto = DecisaoNoResult.envelope(m);
            }
            if (texto == null) {
                texto = m.get(ENTRADA);
            }
            return new Saida(texto == null ? null : String.valueOf(texto), suspenso,
                    chamadasDe(m.get(br.com.archflow.api.agent.mcp.McpAgentComponent.SAIDA_TOOLS)),
                    encerramentoDe(m.get(br.com.archflow.api.agent.mcp.McpAgentComponent.SAIDA_ENCERROU)));
        }
        return new Saida(saida == null ? null : String.valueOf(saida), false);
    }

    /**
     * As chamadas do passo, na forma que vai ao Core: nome, argumentos como o modelo os mandou, e
     * se deu erro.
     *
     * <p>O {@code result} de cada chamada fica de fora de propósito: pode ser grande, e o Core já
     * tem o dado — ele é dono das tools. O que ele não tem é o que o modelo passou.</p>
     */
    @SuppressWarnings("unchecked")
    private static List<VendaxResult.ToolCall> chamadasDe(Object valor) {
        if (!(valor instanceof List<?> lista) || lista.isEmpty()) {
            return List.of();
        }
        List<VendaxResult.ToolCall> chamadas = new java.util.ArrayList<>();
        for (Object item : lista) {
            if (item instanceof Map<?, ?> mapa) {
                Map<String, Object> m = (Map<String, Object>) mapa;
                Object argumentos = m.get("arguments");
                chamadas.add(new VendaxResult.ToolCall(
                        m.get("name") == null ? null : String.valueOf(m.get("name")),
                        argumentos instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of(),
                        Boolean.TRUE.equals(m.get("error"))));
            }
        }
        return chamadas;
    }

    /** O que encerrou o laço, quando algo encerrou. */
    @SuppressWarnings("unchecked")
    private static VendaxResult.Encerramento encerramentoDe(Object valor) {
        if (!(valor instanceof Map<?, ?> mapa) || mapa.isEmpty()) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) mapa;
        Object codigo = m.get("code");
        return new VendaxResult.Encerramento(
                m.get("name") == null ? null : String.valueOf(m.get("name")),
                codigo instanceof Number n ? n.intValue() : null);
    }

    /**
     * A execução desistiu de esperar, mas o trabalho por trás dela pode seguir gastando: interrompida
     * (cancelamento, {@code validoAte}, desligamento) ou com o {@code timeout_ms} vencido.
     *
     * <p>Continua sendo uma {@link IllegalStateException} com a mesma mensagem de antes — quem já a
     * tratava não muda. O que ela acrescenta é {@link #trabalho()}, para quem monta o result pendurar
     * nele o relato do que for gasto depois. O trabalho pode, por sua vez, terminar com outra
     * {@code ExecucaoEmCurso} — a thread do {@code validoAte} desiste antes do motor —, e por isso a
     * espera certa é {@link #quandoTudoParar}, não o trabalho sozinho.</p>
     */
    public static class ExecucaoEmCurso extends IllegalStateException {
        private final transient java.util.concurrent.CompletableFuture<?> trabalho;

        public ExecucaoEmCurso(String mensagem, Throwable causa,
                               java.util.concurrent.CompletableFuture<?> trabalho) {
            super(mensagem, causa);
            this.trabalho = trabalho == null
                    ? java.util.concurrent.CompletableFuture.completedFuture(null) : trabalho;
        }

        /** O trabalho que ficou rodando; completa quando ele terminou (bem, mal ou parado). */
        public java.util.concurrent.CompletableFuture<?> trabalho() {
            return trabalho;
        }

        /**
         * Completa quando {@code trabalho} terminou <b>e</b>, se ele terminou com uma
         * {@code ExecucaoEmCurso}, quando o trabalho que ela deixou rodando também terminou — em
         * cascata. É o "quem termina por último" entre a thread que espera e o motor: o relato
         * depois do prazo só pode ler o contador quando nada mais vai anotar nele.
         */
        public static java.util.concurrent.CompletableFuture<Void> quandoTudoParar(
                java.util.concurrent.CompletableFuture<?> trabalho) {
            return trabalho
                    .handle((valor, erro) -> emCursoEm(erro))
                    .thenCompose(emCurso -> emCurso == null
                            ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                            : quandoTudoParar(emCurso.trabalho()));
        }

        /** A {@code ExecucaoEmCurso} na cadeia de causas de {@code erro} (o futuro a embrulha). */
        public static ExecucaoEmCurso emCursoEm(Throwable erro) {
            for (Throwable t = erro; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof ExecucaoEmCurso emCurso) {
                    return emCurso;
                }
            }
            return null;
        }
    }

    /**
     * O {@code politica.timeout_ms} venceu antes de o motor terminar; {@link #motor()} é a execução
     * do motor, que segue até o passo em curso terminar e cujo gasto ainda precisa ser relatado.
     */
    public static final class PrazoDoFluxo extends ExecucaoEmCurso {

        PrazoDoFluxo(String mensagem, Throwable causa, java.util.concurrent.CompletableFuture<?> motor) {
            super(mensagem, causa, motor);
        }

        /** Completa quando o motor de fato terminou (bem, mal ou parado). */
        public java.util.concurrent.CompletableFuture<?> motor() {
            return trabalho();
        }
    }

    /**
     * O documento do fluxo não passa: não se lê, ou o motor o recusou na validação. Repetir o mesmo
     * invoke dá o mesmo erro — é o que a rota em fluxo diz com {@code recuperavel: false}.
     *
     * <p>Continua sendo uma {@link IllegalStateException} com a mesma mensagem de antes: quem já tratava
     * a falha não muda.</p>
     */
    public static final class FluxoInvalido extends IllegalStateException {
        FluxoInvalido(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }

        /** Há um {@code FluxoInvalido} na cadeia de causas de {@code erro}? */
        public static boolean em(Throwable erro) {
            for (Throwable t = erro; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof FluxoInvalido) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean validacaoNaCadeia(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof br.com.archflow.engine.exceptions.FlowValidationException) {
                return true;
            }
        }
        return false;
    }

    /** Pede ao motor que pare esta execução; já terminada ou não registrada, não há o que parar. */
    private static void pararOMotor(FlowEngine motor, String execucaoId) {
        try {
            motor.cancel(execucaoId);
        } catch (RuntimeException e) {
            log.debug("Fluxo {} não estava mais ativo para ser parado: {}", execucaoId, e.getMessage());
        }
    }

    /**
     * Espera o motor terminar, até {@code limite} (o prazo desta execução, em {@link System#nanoTime}),
     * sem ceder a uma nova interrupção: quem chega aqui já foi interrompido e vai restaurar a flag ao
     * sair. O dispatcher usa a mesma espera quando o cancelamento o pega no prazo do envelope.
     */
    static void aguardarOMotor(java.util.concurrent.CompletableFuture<?> execucao, long limite) {
        while (true) {
            long restante = limite - System.nanoTime();
            if (restante <= 0) {
                return;
            }
            try {
                execucao.get(restante, TimeUnit.NANOSECONDS);
                return;
            } catch (InterruptedException denovo) {
                // Segue esperando; a flag é restaurada por quem chamou.
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException
                     | java.util.concurrent.TimeoutException e) {
                return;
            }
        }
    }

    /** A política do invoke declarou um {@code timeout_ms} válido (senão vale o teto padrão). */
    private static boolean timeoutDeclarado(VendaxInvoke invoke) {
        var def = invoke.definicao();
        return def != null && def.politica() != null
                && def.politica().get("timeout_ms") instanceof Number n && n.longValue() > 0;
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

    /**
     * O motivo da falha, procurado onde ele de fato está.
     *
     * <p>Medido em 04/08: um passo falhou com {@code IllegalStateException} carregando o
     * diagnóstico exato — qual tool de saída faltou e o que o modelo respondeu — e o Core recebeu
     * <b>"Fluxo falhou: sem erro registrado"</b>. A informação existia e era descartada em dois
     * saltos.</p>
     *
     * <p>A causa é que os erros ficam em dois lugares diferentes. O {@code FlowResult} carrega os
     * erros do FLUXO; o erro de um PASSO é gravado por {@code handleFailure} no contexto, sob
     * {@code step.<id>.error}. Quem só olha o primeiro não encontra o segundo — e o segundo é
     * justamente o que diz por que falhou.</p>
     *
     * <p>Sem isto, toda investigação começa por SSH no log do runtime. Uma falha que não se explica
     * ao chamador é meia falha: acontece, e ninguém sabe do quê.</p>
     */
    private String errosDe(FlowResult resultado, ExecutionContext contexto) {
        if (resultado.getErrors() != null && !resultado.getErrors().isEmpty()) {
            return resultado.getErrors().stream()
                    .map(ExecutionError::message)
                    .filter(m -> m != null && !m.isBlank())
                    .collect(Collectors.joining("; "));
        }
        String doPasso = erroDePasso(contexto);
        return doPasso != null ? doPasso : "sem erro registrado";
    }

    /**
     * O erro que {@code handleFailure} deixou no contexto, sob {@code step.<id>.error}.
     *
     * <p>Varre as variáveis porque o id do passo não é conhecido aqui: o fluxo é um documento do
     * cliente, e amarrar este runtime a um nome de passo específico o tornaria menos genérico do
     * que ele é.</p>
     */
    private String erroDePasso(ExecutionContext contexto) {
        if (contexto == null || contexto.getVariables() == null) {
            return null;
        }
        for (var e : contexto.getVariables().entrySet()) {
            if (!e.getKey().startsWith("step.") || !e.getKey().endsWith(".error")) {
                continue;
            }
            String msg = mensagemDe(e.getValue());
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
        }
        return null;
    }

    /** O valor é a lista de {@code StepError}; interessa a primeira mensagem não vazia. */
    private String mensagemDe(Object valor) {
        if (valor instanceof java.util.Collection<?> c) {
            for (Object o : c) {
                if (o instanceof br.com.archflow.model.flow.StepError se
                        && se.message() != null && !se.message().isBlank()) {
                    return se.message();
                }
                // Depois de um checkpoint o valor volta desserializado, não mais tipado.
                if (o != null && !(o instanceof br.com.archflow.model.flow.StepError)) {
                    String t = String.valueOf(o);
                    if (!t.isBlank()) {
                        return t;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A razão da falha, não o invólucro. O motor embrulha duas vezes ({@code ExecutionException} →
     * {@code FlowEngineException("Error executing flow: <uuid>")}), e ler um nível só entregava ao Core
     * o uuid da execução no lugar, por exemplo, da recusa na validação ({@code NODE_DISCONNECTED},
     * nó sem execução) — que a tela já mostrava. Mesma ideia do {@code errorOf} do
     * {@code SpringWorkflowCrudController}: só mensagem, nunca o stack trace.
     *
     * <p>Vale a mensagem da causa MAIS FUNDA QUE TEM MENSAGEM, não a da raiz às cegas: a raiz pode vir
     * muda. O motor interrompido lança {@code FlowEngineException("Flow execution interrupted: <id>")}
     * sobre um {@code InterruptedException} sem mensagem — parar na raiz trocaria a frase que o Core
     * recebia pelo nome da classe. Só quando ninguém na cadeia tem mensagem vai o nome da raiz.</p>
     */
    private static String causaDe(Exception e) {
        Throwable causa = e;
        String motivo = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : null;
        while (causa.getCause() != null && causa.getCause() != causa) {
            causa = causa.getCause();
            if (causa.getMessage() != null && !causa.getMessage().isBlank()) {
                motivo = causa.getMessage();
            }
        }
        return motivo != null ? motivo : causa.getClass().getSimpleName();
    }
}
