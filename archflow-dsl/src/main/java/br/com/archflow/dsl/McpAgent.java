package br.com.archflow.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * O passo que conduz o laço agente↔ferramentas contra um servidor MCP, com a
 * configuração dele escrita por método em vez de por chave de texto.
 *
 * <pre>{@code
 * Workflows.workflow("ap-narracao")
 *     .step("narra", McpAgent.node()
 *             .systemPrompt(PROMPT)
 *             .noTools()          // o agente só redige: sem catálogo, sem servidor
 *             .maxIterations(1)
 *             .spec())
 *     .build();
 * }</pre>
 *
 * <p><b>Por que existe.</b> {@link NodeSpec#with(String, Object)} continua
 * aceitando qualquer chave — é o que mantém a DSL utilizável com um runtime mais
 * novo que ela. O custo de escrever tudo assim é que o nome da chave só é
 * conferido quando o fluxo executa, e há chaves cujo <i>valor</i> muda de
 * sentido: {@code tools} ausente é "todas as ferramentas do servidor" e
 * {@code tools: []} é "nenhuma". Quem digita {@code .with("tools", List.of())}
 * não vê essa diferença; quem escreve {@link #noTools()} vê.
 *
 * <p>Imutável, como a {@link NodeSpec}: cada método devolve uma instância nova.
 *
 * @since 1.4.0
 */
public final class McpAgent {

    private final Map<String, Object> config;

    private McpAgent(Map<String, Object> config) {
        this.config = config;
    }

    /** Um passo de agente MCP, ainda sem configuração. */
    public static McpAgent node() {
        return new McpAgent(Map.of());
    }

    private McpAgent with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(config);
        merged.put(key, value);
        return new McpAgent(merged);
    }

    /** A instrução do agente. Obrigatória: o passo a recusa vazia. */
    public McpAgent systemPrompt(String prompt) {
        return with("systemPrompt", Objects.requireNonNull(prompt, "systemPrompt"));
    }

    /** Qual servidor MCP, por referência. Ausente = o padrão do host. */
    public McpAgent server(String ref) {
        return with("server", Objects.requireNonNull(ref, "server"));
    }

    /**
     * As ferramentas que este passo pode usar — interseccionadas com o teto do
     * host, que é limite e não fonte.
     *
     * <p>Sem esta chave, o passo recebe todas as do servidor. Chamar com lista
     * vazia é o mesmo que {@link #noTools()}, e o método existe justamente para
     * que isso seja dito por extenso.
     */
    public McpAgent tools(String... names) {
        return with("tools", List.of(names));
    }

    /**
     * Nenhuma ferramenta: o passo roda sem catálogo e <b>sem abrir o servidor</b>.
     *
     * <p>É o que um agente que só redige precisa — tudo o que ele pode dizer já
     * está na entrada. Com uma ferramenta à mão, o modelo tende a buscar dado que
     * não lhe cabe, e num passo de uma volta só a chamada consome a volta e o
     * passo termina sem texto.
     */
    public McpAgent noTools() {
        return with("tools", List.of());
    }

    /** Quantas voltas o laço pode dar. Ausente = o padrão do runtime. */
    public McpAgent maxIterations(int voltas) {
        if (voltas <= 0) {
            throw new IllegalArgumentException("maxIterations deve ser positivo: " + voltas);
        }
        return with("maxIterations", voltas);
    }

    /**
     * De onde sai a resposta do passo, quando ela não é o texto do modelo: a
     * lista é ordenada por preferência.
     */
    public McpAgent outputFromTool(String... names) {
        return with("saidaDaTool", List.of(names));
    }

    /** Falha o passo se nenhuma das ferramentas de saída for chamada. */
    public McpAgent requireOutputFromTool() {
        return with("exigirSaidaDaTool", true);
    }

    /** Ferramentas que só rodam depois de uma decisão humana; o laço suspende antes. */
    public McpAgent humanApproval(String... names) {
        return with("aprovacaoHumana", List.of(names));
    }

    /**
     * Códigos de erro de ferramenta que <b>encerram</b> o laço em vez de voltar
     * ao modelo.
     *
     * <p>Para o erro que nenhuma volta a mais resolve — "este tenant não
     * contratou o agente". Sem isso, o modelo tenta de novo até o teto de voltas
     * e acaba redigindo uma resposta sem o dado, que é o pior desfecho: parece
     * resposta.
     */
    public McpAgent stopOnToolErrorCodes(int... codes) {
        List<Integer> lista = new ArrayList<>(codes.length);
        for (int code : codes) {
            lista.add(code);
        }
        return with("encerrarEmCodigos", List.copyOf(lista));
    }

    /** Modelo deste passo, quando ele não é o do fluxo nem o do tenant. */
    public McpAgent model(String model) {
        return with("model", Objects.requireNonNull(model, "model"));
    }

    /** Teto de tokens da resposta deste passo. */
    public McpAgent maxTokens(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens deve ser positivo: " + maxTokens);
        }
        return with("maxTokens", maxTokens);
    }

    /**
     * O esforço de raciocínio pedido ao provedor — {@code "minimal"},
     * {@code "low"}, {@code "medium"}, {@code "high"}, {@code "none"}.
     *
     * <p>O que cada valor faz é do provedor e do modelo, não da DSL: há modelo
     * que ignora o pedido de desligar. Medir antes de confiar.
     */
    public McpAgent reasoningEffort(String effort) {
        return reasoning(Map.of("effort", Objects.requireNonNull(effort, "effort")));
    }

    /** O objeto {@code reasoning} inteiro, para o que o método acima não cobre. */
    public McpAgent reasoning(Map<String, Object> reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            throw new IllegalArgumentException(
                    "reasoning vazio: declare {\"effort\": ...} ou {\"max_tokens\": N}");
        }
        return with("reasoning", Map.copyOf(reasoning));
    }

    /** Uma chave que esta versão da DSL ainda não tenha método próprio. */
    public McpAgent with(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(config);
        merged.putAll(values);
        return new McpAgent(merged);
    }

    /** O passo, pronto para {@link WorkflowBuilder#step(String, NodeSpec)}. */
    public NodeSpec spec() {
        return Nodes.mcpAgent().with(config);
    }

    /** O rótulo que o designer mostra. */
    public NodeSpec labeled(String label) {
        return spec().labeled(label);
    }
}
