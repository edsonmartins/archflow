package br.com.archflow.langchain4j.mcp.prompt;

import br.com.archflow.langchain4j.mcp.McpModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for MCP prompts.
 *
 * <p>This class manages a collection of prompts that can be exposed
 * through the MCP protocol. Prompts are templates that can be
 * retrieved with arguments to generate structured messages for LLMs.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * McpPromptManager manager = new McpPromptManager();
 *
 * // Register a simple prompt
 * manager.register("summarize", "Summarize the given text",
 *     List.of(new PromptArgument("text", "Text to summarize", true)),
 *     args -> new PromptResult(
 *         "Summary",
 *         List.of(new PromptMessage(ROLE_USER,
 *             "Please summarize: " + args.get("text")))
 *     )
 * );
 *
 * // Get prompt with arguments
 * PromptResult result = manager.getPrompt("summarize", Map.of("text", "Hello World"));
 * }</pre>
 */
public class McpPromptManager {

    private static final Logger log = LoggerFactory.getLogger(McpPromptManager.class);

    private final Map<String, PromptTemplate> prompts;
    private final Map<String, PromptExecutor> executors;

    /**
     * Create a new prompt manager.
     */
    public McpPromptManager() {
        this.prompts = new LinkedHashMap<>();
        this.executors = new ConcurrentHashMap<>();
    }

    // ---------------------------------------------------------------------------
    // PROMPT REGISTRATION
    // ---------------------------------------------------------------------------

    /**
     * Register a prompt template.
     *
     * @param name Prompt name
     * @param description Prompt description
     * @return Builder for configuration
     */
    public PromptBuilder register(String name, String description) {
        return new PromptBuilder(this, name, description);
    }

    /**
     * Register a prompt template with executor.
     *
     * @param template Prompt template
     * @param executor Prompt executor
     * @return This manager
     */
    public McpPromptManager register(PromptTemplate template, PromptExecutor executor) {
        prompts.put(template.name(), template);
        executors.put(template.name(), executor);
        log.debug("Registered prompt: {}", template.name());
        return this;
    }

    /**
     * Register a simple text prompt.
     *
     * @param name Prompt name
     * @param description Prompt description
     * @param templateText Template text with {placeholder} support
     * @return This manager
     */
    public McpPromptManager registerTextPrompt(String name, String description, String templateText) {
        PromptTemplate template = new PromptTemplate(
                name,
                description,
                extractArguments(templateText),
                List.of()
        );

        SimpleTemplateExecutor executor = new SimpleTemplateExecutor(templateText);
        return register(template, executor);
    }

    /**
     * Register a prompt with arguments.
     *
     * @param name Prompt name
     * @param description Prompt description
     * @param arguments Prompt arguments
     * @param executor Prompt executor
     * @return This manager
     */
    public McpPromptManager register(String name,
                                      String description,
                                      List<McpModel.PromptArgument> arguments,
                                      PromptExecutor executor) {
        PromptTemplate template = new PromptTemplate(name, description, arguments, List.of());
        return register(template, executor);
    }

    /**
     * Unregister a prompt.
     *
     * @param name Prompt name
     * @return This manager
     */
    public McpPromptManager unregister(String name) {
        prompts.remove(name);
        executors.remove(name);
        log.debug("Unregistered prompt: {}", name);
        return this;
    }

    // ---------------------------------------------------------------------------
    // PROMPT ACCESS
    // ---------------------------------------------------------------------------

    /**
     * List all prompt templates.
     *
     * @return List of prompts
     */
    public List<McpModel.Prompt> listPrompts() {
        return prompts.values().stream()
                .map(this::toMcpPrompt)
                .toList();
    }

    /**
     * Get a prompt with arguments.
     *
     * @param name Prompt name
     * @param arguments Prompt arguments
     * @return Prompt result
     */
    public CompletableFuture<McpModel.PromptResult> getPrompt(String name, Map<String, Object> arguments) {
        PromptExecutor executor = executors.get(name);
        if (executor == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Prompt not found: " + name));
        }

        try {
            McpModel.PromptResult result = executor.execute(arguments);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Error executing prompt: {}", name, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Check if a prompt exists.
     *
     * @param name Prompt name
     * @return true if exists
     */
    public boolean hasPrompt(String name) {
        return prompts.containsKey(name);
    }

    /**
     * Get prompt template by name.
     *
     * @param name Prompt name
     * @return Prompt template or null
     */
    public PromptTemplate getTemplate(String name) {
        return prompts.get(name);
    }

    /**
     * Get all prompt templates.
     *
     * @return List of templates
     */
    public List<PromptTemplate> getTemplates() {
        return List.copyOf(prompts.values());
    }

    /**
     * Get number of registered prompts.
     *
     * @return Prompt count
     */
    public int size() {
        return prompts.size();
    }

    // ---------------------------------------------------------------------------
    // INTERNAL HELPERS
    // ---------------------------------------------------------------------------

    private McpModel.Prompt toMcpPrompt(PromptTemplate template) {
        return new McpModel.Prompt(
                template.name(),
                template.description(),
                template.arguments()
        );
    }

    private List<McpModel.PromptArgument> extractArguments(String template) {
        List<McpModel.PromptArgument> args = new ArrayList<>();
        // Extract {placeholder} style arguments
        int start = 0;
        while ((start = template.indexOf('{', start)) != -1) {
            int end = template.indexOf('}', start);
            if (end != -1) {
                String argName = template.substring(start + 1, end);
                args.add(new McpModel.PromptArgument(argName, "Argument: " + argName, true));
                start = end + 1;
            } else {
                break;
            }
        }
        return args;
    }

    // ---------------------------------------------------------------------------
    // BUILDER
    // ---------------------------------------------------------------------------

    /**
     * Builder for registering prompts.
     */
    public static class PromptBuilder {
        private final McpPromptManager manager;
        private final String name;
        private final String description;
        private final List<McpModel.PromptArgument> arguments;
        private PromptExecutor executor;

        PromptBuilder(McpPromptManager manager, String name, String description) {
            this.manager = manager;
            this.name = name;
            this.description = description;
            this.arguments = new ArrayList<>();
        }

        /**
         * Add an argument.
         *
         * @param name Argument name
         * @param description Argument description
         * @return This builder
         */
        public PromptBuilder addArgument(String name, String description) {
            return addArgument(name, description, true);
        }

        /**
         * Add an argument with required flag.
         *
         * @param name Argument name
         * @param description Argument description
         * @param required Whether required
         * @return This builder
         */
        public PromptBuilder addArgument(String name, String description, boolean required) {
            arguments.add(new McpModel.PromptArgument(name, description, required));
            return this;
        }

        /**
         * Set the executor.
         *
         * @param executor Prompt executor
         * @return The manager
         */
        public McpPromptManager withExecutor(PromptExecutor executor) {
            this.executor = executor;
            PromptTemplate template = new PromptTemplate(name, description, arguments, List.of());
            return manager.register(template, executor);
        }

        /**
         * Register as a simple text template.
         *
         * @param template Template text
         * @return The manager
         */
        public McpPromptManager asTemplate(String template) {
            return manager.registerTextPrompt(name, description, template);
        }

        /**
         * Register as a fixed text prompt.
         *
         * @param text Fixed text
         * @return The manager
         */
        public McpPromptManager asFixed(String text) {
            executor = args -> new McpModel.PromptResult(
                    description,
                    List.of(new McpModel.PromptMessage(
                            McpModel.PromptMessage.ROLE_USER,
                            text
                    ))
            );
            PromptTemplate template = new PromptTemplate(name, description, arguments, List.of());
            return manager.register(template, executor);
        }

        /**
         * Register as a system prompt.
         *
         * @param systemText System prompt text
         * @return The manager
         */
        public McpPromptManager asSystem(String systemText) {
            executor = args -> new McpModel.PromptResult(
                    description,
                    List.of(
                            new McpModel.PromptMessage(
                                    McpModel.PromptMessage.ROLE_SYSTEM,
                                    systemText
                            ),
                            new McpModel.PromptMessage(
                                    McpModel.PromptMessage.ROLE_USER,
                                    args.getOrDefault("input", "").toString()
                            )
                    )
            );
            PromptTemplate template = new PromptTemplate(
                    name,
                    description,
                    arguments.isEmpty() ?
                            List.of(new McpModel.PromptArgument("input", "User input", true)) :
                            arguments,
                    List.of()
            );
            return manager.register(template, executor);
        }
    }

    // ---------------------------------------------------------------------------
    // NESTED CLASSES
    // ---------------------------------------------------------------------------

    /**
     * Prompt template descriptor.
     */
    public record PromptTemplate(
            String name,
            String description,
            List<McpModel.PromptArgument> arguments,
            List<String> tags
    ) {
        public PromptTemplate {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description cannot be blank");
            }
            if (arguments == null) {
                arguments = List.of();
            }
            if (tags == null) {
                tags = List.of();
            }
        }

        public PromptTemplate(String name, String description) {
            this(name, description, List.of(), List.of());
        }
    }

    /**
     * Executor for prompt templates.
     */
    @FunctionalInterface
    public interface PromptExecutor {
        /**
         * Execute the prompt with given arguments.
         *
         * @param arguments Prompt arguments
         * @return Prompt result
         */
        McpModel.PromptResult execute(Map<String, Object> arguments);
    }

    /**
     * Simple template executor with {placeholder} replacement.
     */
    private static class SimpleTemplateExecutor implements PromptExecutor {

        private final String template;

        SimpleTemplateExecutor(String template) {
            this.template = template;
        }

        @Override
        public McpModel.PromptResult execute(Map<String, Object> arguments) {
            String result = template;

            // Replace placeholders
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                result = result.replace(placeholder, value);
            }

            return new McpModel.PromptResult(
                    "Generated prompt",
                    List.of(new McpModel.PromptMessage(
                            McpModel.PromptMessage.ROLE_USER,
                            result
                    ))
            );
        }
    }

    // ---------------------------------------------------------------------------
    // PREDEFINED PROMPTS
    // ---------------------------------------------------------------------------

    /**
     * Register common predefined prompts.
     *
     * @return This manager
     */
    public McpPromptManager registerPredefinedPrompts() {
        // Summarize prompt
        register("summarize", "Summarize the given text")
                .addArgument("text", "Text to summarize")
                .addArgument("style", "Summary style (brief/detailed)", false)
                .asTemplate("Please summarize the following text in a {style} style:\n\n{text}");

        // Explain prompt
        register("explain", "Explain a concept")
                .addArgument("topic", "Topic to explain")
                .addArgument("audience", "Target audience", false)
                .asTemplate("Explain '{topic}' to an {audience} audience.");

        // Translate prompt
        register("translate", "Translate text to another language")
                .addArgument("text", "Text to translate")
                .addArgument("language", "Target language")
                .asTemplate("Translate the following text to {language}:\n\n{text}");

        // Code review prompt
        register("code_review", "Review code for issues")
                .addArgument("code", "Code to review")
                .addArgument("language", "Programming language")
                .asTemplate("Review the following {language} code for issues, bugs, and improvements:\n\n```{language}\n{code}\n```");

        // Format prompt
        register("format", "Format text according to a style")
                .addArgument("text", "Text to format")
                .addArgument("format", "Format style (markdown/json/xml)")
                .asTemplate("Format the following text as {format}:\n\n{text}");

        log.info("Registered {} predefined prompts", 5);
        return this;
    }
}
