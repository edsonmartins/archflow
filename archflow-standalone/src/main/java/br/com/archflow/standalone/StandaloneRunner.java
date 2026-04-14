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
 * databases, or any external infrastructure — only needs Java 17+ and LLM API keys.
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
 * <p>Environment variables:
 * <ul>
 *   <li>{@code ARCHFLOW_API_KEY} — LLM API key</li>
 *   <li>{@code ARCHFLOW_MODEL} — LLM model name</li>
 *   <li>{@code ARCHFLOW_PROVIDER} — LLM provider (openai, anthropic, etc.)</li>
 * </ul>
 */
public class StandaloneRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StandaloneRunner.class);

    private final FlowSerializer serializer;
    private ArchFlowAgent agent;
    private ProtobufEventPublisher eventPublisher;

    public StandaloneRunner() {
        this.serializer = new FlowSerializer();
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
        // Load workflow
        SerializableFlow flow = loadWorkflow(args.workflowPath);
        log.info("Loaded workflow: {} ({})", flow.getId(),
                flow.getMetadata() != null ? flow.getMetadata().name() : "unnamed");

        // Create agent
        AgentConfig config = AgentConfig.builder()
                .agentId("standalone-" + flow.getId())
                .pluginsPath(args.pluginsPath)
                .maxConcurrentFlows(1)
                .defaultFlowTimeout(args.timeoutSeconds * 1000L)
                .retryConfig(new RetryConfig(3, 1000, 2.0))
                .resourceConfig(new ResourceConfig(args.maxThreads, Runtime.getRuntime().maxMemory()))
                .build();

        agent = new ArchFlowAgent(config);

        // Wire up protobuf publisher if --events-url was provided
        if (args.eventsUrl != null) {
            String agentId = args.agentId != null ? args.agentId : "standalone-" + flow.getId();
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

        // Inject environment-based LLM config
        injectEnvConfig(input);

        // Execute
        log.info("Executing workflow with {} variables...", input.size());
        FlowResult result = agent.executeFlow(flow, input)
                .get(args.timeoutSeconds, TimeUnit.SECONDS);

        log.info("Workflow completed with status: {}", result.getStatus());
        return result;
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
     * Exports a Flow to a standalone JSON file.
     */
    public void exportWorkflow(br.com.archflow.model.flow.Flow flow, Path outputPath) throws Exception {
        serializer.exportToFile(flow, outputPath);
    }

    private void injectEnvConfig(Map<String, Object> input) {
        String apiKey = System.getenv("ARCHFLOW_API_KEY");
        String model = System.getenv("ARCHFLOW_MODEL");
        String provider = System.getenv("ARCHFLOW_PROVIDER");

        if (apiKey != null) input.putIfAbsent("api_key", apiKey);
        if (model != null) input.putIfAbsent("model", model);
        if (provider != null) input.putIfAbsent("provider", provider);
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

                Environment:
                  ARCHFLOW_API_KEY      LLM API key
                  ARCHFLOW_MODEL        LLM model name
                  ARCHFLOW_PROVIDER     LLM provider (openai, anthropic, etc.)

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
