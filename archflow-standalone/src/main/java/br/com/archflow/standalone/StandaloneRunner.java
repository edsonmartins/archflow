package br.com.archflow.standalone;

import br.com.archflow.agent.ArchFlowAgent;
import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.config.ResourceConfig;
import br.com.archflow.agent.config.RetryConfig;
import br.com.archflow.events.proto.ProtobufEventPublisher;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.standalone.model.SerializableFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Standalone runner for exported archflow workflows.
 *
 * <p>Loads a workflow from a JSON file and executes it without Spring Boot,
 * databases, or any external infrastructure — only needs Java 25+ and LLM API keys.
 *
 * <p><b>Os passos são os do servidor (RCHFLW-37).</b> O documento é materializado pela mesma fábrica do
 * archflow-api ({@link StandaloneFlowMaterializer}) antes de chegar ao motor: Entrada/Saída, condição/switch,
 * decisão, nós de chat dos adapters LangChain4j e componentes de plugin executam aqui como lá. Um nó que
 * precisa do servidor (aprovação com fila, orquestração, {@code mcp-agent}, base de conhecimento) recusa o
 * fluxo antes do primeiro passo, dizendo o nó e o motivo.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * java -jar my-workflow.jar workflow.json [--input "query text"] [--var key=value]
 *
 * Options:
 *   workflow.json         Path to the workflow JSON file (required)
 *   --input TEXT          Input text for the workflow
 *   --var KEY=VALUE       Set a workflow variable (repeatable)
 *   --timeout SECONDS     Execution timeout (default: 300)
 *   --threads N           Max threads (default: 4)
 *   --plugins PATH        Path to plugins directory
 * </pre>
 *
 * <p>Environment variables (a chave vem só daqui, nunca do JSON do fluxo — ver {@link StandaloneEnvironment}):
 * <ul>
 *   <li>{@code ARCHFLOW_API_KEY} / {@code ARCHFLOW_LLM_API_KEY} — LLM API key of the default provider</li>
 *   <li>{@code ARCHFLOW_MODEL} / {@code ARCHFLOW_LLM_MODEL} — LLM model name</li>
 *   <li>{@code ARCHFLOW_PROVIDER} / {@code ARCHFLOW_LLM_PROVIDER} — LLM provider (openai, anthropic, etc.)</li>
 *   <li>{@code ARCHFLOW_LLM_KEYS_<PROVIDER>} — key of one provider</li>
 *   <li>{@code ARCHFLOW_LLM_BASE_URL}, {@code ARCHFLOW_LLM_ALLOWED_BASE_URLS} — destinations that may receive
 *       the key</li>
 * </ul>
 */
public class StandaloneRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StandaloneRunner.class);

    private final FlowSerializer serializer;
    private final Map<String, String> environment;
    private ArchFlowAgent agent;
    private ProtobufEventPublisher eventPublisher;

    public StandaloneRunner() {
        this(System.getenv());
    }

    /** Com um ambiente dado no lugar das variáveis do processo (testes). */
    StandaloneRunner(Map<String, String> environment) {
        this.serializer = new FlowSerializer();
        this.environment = environment;
    }

    /**
     * Main entry point for standalone execution.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        try (StandaloneRunner runner = new StandaloneRunner()) {
            CliArgs cli = CliArgs.parse(args);
            FlowResult result = runner.run(cli);

            if (result.getOutput().isPresent()) {
                System.out.println(result.getOutput().get());
            }

            System.exit(result.getStatus().name().equals("COMPLETED") ? 0 : 1);
        } catch (Exception e) {
            log.error("Execution failed: {}", e.getMessage(), e);
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Runs a workflow from CLI arguments.
     */
    public FlowResult run(CliArgs args) throws Exception {
        // Load workflow — o documento cru, como o servidor o guarda e lê: é ele que a fábrica materializa.
        Map<String, Object> document = loadDocument(args.workflowPath);
        Object meta = document.get("metadata");
        log.info("Loaded workflow: {} ({})", document.get("id"),
                meta instanceof Map<?, ?> m && m.get("name") != null ? m.get("name") : "unnamed");
        String flowId = String.valueOf(document.get("id"));

        // Create agent
        AgentConfig config = AgentConfig.builder()
                .agentId("standalone-" + flowId)
                .pluginsPath(args.pluginsPath)
                .maxConcurrentFlows(1)
                .defaultFlowTimeout(args.timeoutSeconds * 1000L)
                .retryConfig(new RetryConfig(3, 1000, 2.0))
                .resourceConfig(new ResourceConfig(args.maxThreads, Runtime.getRuntime().maxMemory()))
                .build();

        agent = new ArchFlowAgent(config);

        // Wire up protobuf publisher if --events-url was provided
        if (args.eventsUrl != null) {
            String agentId = args.agentId != null ? args.agentId : "standalone-" + flowId;
            eventPublisher = new ProtobufEventPublisher(
                    agent.getEventStreamRegistry(),
                    URI.create(args.eventsUrl),
                    agentId);
            log.info("Protobuf event publisher started → {}", args.eventsUrl);
        }

        // Prepare input
        Map<String, Object> input = new HashMap<>(args.variables);
        if (args.inputText != null) {
            input.put("input", args.inputText);
        }

        // Materializa os passos com a fábrica do servidor. Anotações do canvas ficam no arquivo para o
        // designer, mas não são passos; um nó que precisa do servidor recusa o fluxo aqui, antes de qualquer
        // passo rodar. A chave de LLM vem do ambiente — antes ela ia parar nas variáveis da execução
        // (api_key), onde nenhum passo a lia e qualquer saída podia carregá-la.
        br.com.archflow.model.flow.Flow flow = StandaloneFlowMaterializer
                .create(agent.getPluginCatalog(), StandaloneEnvironment.of(environment))
                .materialize(document);

        // Execute
        log.info("Executing workflow with {} variables...", input.size());
        FlowResult result = submit(agent, flow, input)
                .get(args.timeoutSeconds, TimeUnit.SECONDS);

        log.info("Workflow completed with status: {}", result.getStatus());
        return result;
    }

    /**
     * Entrega o fluxo ao agente. Separado do {@link #run} só para um teste ver o fluxo que chega ao agente
     * (materializado, sem as anotações do canvas) sem executá-lo.
     */
    protected java.util.concurrent.CompletableFuture<FlowResult> submit(ArchFlowAgent agent,
            br.com.archflow.model.flow.Flow flow, Map<String, Object> input) {
        return agent.executeFlow(flow, StandaloneFlowMaterializer.executionContext(flow.getId(), input));
    }

    /**
     * Loads a workflow from file path or classpath resource.
     */
    public SerializableFlow loadWorkflow(String path) throws Exception {
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            return serializer.importFromFile(filePath);
        }
        return serializer.importFromResource(path);
    }

    /**
     * O documento do fluxo, de um arquivo ou de um recurso do classpath, sem passar pelo modelo de transporte:
     * a fábrica lê o mesmo mapa que o servidor lê do banco.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadDocument(String path) throws Exception {
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            return serializer.getMapper().readValue(Files.readString(filePath), Map.class);
        }
        try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new java.io.IOException("Workflow not found (file or classpath resource): " + path);
            }
            return serializer.getMapper().readValue(is, Map.class);
        }
    }

    /**
     * Exports a Flow to a standalone JSON file.
     */
    public void exportWorkflow(br.com.archflow.model.flow.Flow flow, Path outputPath) throws Exception {
        serializer.exportToFile(flow, outputPath);
    }

    @Override
    public void close() throws Exception {
        if (eventPublisher != null) {
            try {
                eventPublisher.close();
            } catch (Exception e) {
                log.warn("Error closing event publisher: {}", e.getMessage());
            }
        }
        if (agent != null) {
            agent.close();
        }
    }

    private static void printUsage() {
        System.out.println("""
                archflow Standalone Runner

                Usage: java -jar archflow-standalone.jar <workflow.json> [options]

                Options:
                  --input TEXT          Input text for the workflow
                  --var KEY=VALUE       Set a workflow variable (repeatable)
                  --timeout SECONDS     Execution timeout (default: 300)
                  --threads N           Max threads (default: 4)
                  --plugins PATH        Path to plugins directory

                Environment (the LLM key comes only from here, never from the workflow file):
                  ARCHFLOW_API_KEY      LLM API key of the default provider (or ARCHFLOW_LLM_API_KEY)
                  ARCHFLOW_MODEL        LLM model name (or ARCHFLOW_LLM_MODEL)
                  ARCHFLOW_PROVIDER     LLM provider: openai, anthropic, openrouter (or ARCHFLOW_LLM_PROVIDER)
                  ARCHFLOW_LLM_KEYS_<PROVIDER>     key of one provider (e.g. ARCHFLOW_LLM_KEYS_OPENAI)
                  ARCHFLOW_LLM_BASE_URL            endpoint of the default provider (a gateway)
                  ARCHFLOW_LLM_ALLOWED_BASE_URLS   other endpoints allowed to receive the key

                Nodes that need the archflow server (approval queue, orchestrate, mcp-agent,
                knowledge base) refuse the workflow before the first step.

                Example:
                  java -jar my-workflow.jar customer-support.json --input "Track order #123"
                """);
    }

    /**
     * Parsed CLI arguments.
     */
    public record CliArgs(
            String workflowPath,
            String inputText,
            Map<String, Object> variables,
            int timeoutSeconds,
            int maxThreads,
            String pluginsPath,
            String eventsUrl,
            String agentId
    ) {
        public static CliArgs parse(String[] args) {
            String workflowPath = args[0];
            String inputText = null;
            Map<String, Object> variables = new HashMap<>();
            int timeout = 300;
            int threads = 4;
            String plugins = null;
            String eventsUrl = null;
            String agentId = null;

            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--input"      -> inputText = args[++i];
                    case "--var"        -> {
                        String[] kv = args[++i].split("=", 2);
                        variables.put(kv[0], kv.length > 1 ? kv[1] : "");
                    }
                    case "--timeout"    -> timeout = Integer.parseInt(args[++i]);
                    case "--threads"    -> threads = Integer.parseInt(args[++i]);
                    case "--plugins"    -> plugins = args[++i];
                    case "--events-url" -> eventsUrl = args[++i];
                    case "--agent-id"   -> agentId = args[++i];
                }
            }

            return new CliArgs(workflowPath, inputText, variables, timeout, threads,
                    plugins, eventsUrl, agentId);
        }
    }
}
