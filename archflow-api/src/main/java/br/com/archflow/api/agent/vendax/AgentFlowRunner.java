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
     * caminho por {@code case} já tratava (ver {@link ConsultaAoNegociador}) e que o caminho
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

        Flow flow = deserializer.toFlow(documento);
        // Registrar é o que permite retomar depois de uma suspensão por aprovação: sem isto, o
        // fluxo suspende de forma durável e não há como continuá-lo.
        flowRepository.save(flow);

        ExecutionContext contexto = new DefaultExecutionContext(
                invoke.tenantId(), "vendax", execucaoId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        contexto.set(ENTRADA, entrada == null ? "" : entrada);
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
        // A MEMÓRIA DO CLIENTE vai sob chave transient: é entrada deste invoke, e são dados pessoais.
        // Persistida, iria para o estado durável do fluxo a cada checkpoint. O custo é que um passo
        // executado depois de uma retomada roda sem ela.
        if (!invoke.memoria().isEmpty()) {
            contexto.set(br.com.archflow.api.agent.mcp.McpAgentComponent.CTX_MEMORIA,
                    invoke.memoria());
        }

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

        Object saida = resultado.getOutput().orElse(null);
        log.debug("Fluxo {} do agente {} concluiu com status {} (conv={})",
                execucaoId, invoke.agent(), resultado.getStatus(), invoke.conversationId());
        return reduzir(saida);
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
    private static void definirSeHouver(ExecutionContext contexto, String chave, String valor) {
        if (valor != null && !valor.isBlank()) {
            contexto.set(chave, valor);
        }
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

    private static String causaDe(Exception e) {
        Throwable causa = e.getCause() != null ? e.getCause() : e;
        return causa.getMessage() == null ? causa.getClass().getSimpleName() : causa.getMessage();
    }
}
