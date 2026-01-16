package br.com.archflow.langchain4j.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Domain models for MCP (Model Context Protocol) v1.0.
 *
 * <p>This class contains the core data structures for MCP Resources, Tools, and Prompts.
 * These are the three main capabilities that an MCP server can expose.</p>
 *
 * @see <a href="https://modelcontextprotocol.io/specification/">MCP Specification</a>
 */
public final class McpModel {

    private McpModel() {}

    // ---------------------------------------------------------------------------
    // RESOURCES - Data/context that can be read by LLMs
    // ---------------------------------------------------------------------------

    /**
     * An MCP Resource represents a piece of data or context that can be read.
     *
     * @param uri Unique URI identifying the resource
     * @param name Human-readable name
     * @param description Description of what the resource provides
     * @param mimeType MIME type of the resource content (optional)
     * @param metadata Additional metadata (optional)
     */
    public record Resource(
            @JsonProperty("uri") URI uri,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("metadata") Map<String, Object> metadata
    ) {
        public Resource {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Resource name cannot be blank");
            }
        }

        public Resource(URI uri, String name, String description, String mimeType) {
            this(uri, name, description, mimeType, null);
        }
    }

    /**
     * Content returned from reading a resource.
     *
     * @param uri URI of the resource
     * @param mimeType MIME type of the content
     * @param text Text content (for text-based resources)
     * @param blob Base64-encoded binary content (for binary resources)
     */
    public record ResourceContent(
            @JsonProperty("uri") URI uri,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("text") String text,
            @JsonProperty("blob") String blob
    ) {
        public ResourceContent(URI uri, String mimeType, String text) {
            this(uri, mimeType, text, null);
        }

        public boolean isText() {
            return text != null;
        }

        public boolean isBlob() {
            return blob != null;
        }
    }

    /**
     * Template variables for a resource URI.
     *
     * @param name Variable name
     * @param description Description of the variable
     * @param required Whether the variable is required
     * @param type Expected type of the variable
     */
    public record ResourceTemplateVariable(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("required") boolean required,
            @JsonProperty("type") String type
    ) {
        public ResourceTemplateVariable(String name, String description, boolean required) {
            this(name, description, required, "string");
        }
    }

    /**
     * A resource template for URI templating.
     *
     * @param uriTemplate URI template string (e.g., "file://{path}")
     * @param name Human-readable name
     * @param description Description
     * @param mimeType MIME type of the resource
     * @param variables Template variables
     */
    public record ResourceTemplate(
            @JsonProperty("uriTemplate") String uriTemplate,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("variables") List<ResourceTemplateVariable> variables
    ) {
        public ResourceTemplate(String uriTemplate, String name, String description, String mimeType) {
            this(uriTemplate, name, description, mimeType, List.of());
        }
    }

    // ---------------------------------------------------------------------------
    // TOOLS - Executable functions that LLMs can call
    // ---------------------------------------------------------------------------

    /**
     * An MCP Tool represents an executable function.
     *
     * @param name Unique tool name
     * @param description Description of what the tool does
     * @param inputSchema JSON Schema for input validation
     */
    public record Tool(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("inputSchema") Map<String, Object> inputSchema
    ) {
        public Tool {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Tool name cannot be blank");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Tool description cannot be blank");
            }
            if (inputSchema == null || inputSchema.isEmpty()) {
                throw new IllegalArgumentException("Tool inputSchema cannot be null or empty");
            }
        }

        /**
         * Creates a tool with a simple JSON Schema.
         *
         * @param name Tool name
         * @param description Tool description
         * @param parameters Parameter names
         * @return Tool with generated schema
         */
        public static Tool simple(String name, String description, String... parameters) {
            java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
            java.util.List<String> required = new java.util.ArrayList<>();

            for (String param : parameters) {
                properties.put(param, Map.of(
                        "type", "string",
                        "description", "Parameter: " + param
                ));
                required.add(param);
            }

            java.util.Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required
            );

            return new Tool(name, description, schema);
        }
    }

    /**
     * Arguments passed to a tool.
     *
     * @param name Tool name
     * @param arguments Tool arguments as key-value map
     */
    public record ToolArguments(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") Map<String, Object> arguments
    ) {
        public ToolArguments(String name, Map<String, Object> arguments) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Tool name cannot be blank");
            }
            this.name = name;
            this.arguments = arguments != null ? arguments : Map.of();
        }
    }

    /**
     * Result returned from tool execution.
     *
     * @param content Result content (can be text, image, or embedded resource)
     * @param isError Whether the result indicates an error
     */
    public record ToolResult(
            @JsonProperty("content") List<ToolContent> content,
            @JsonProperty("isError") boolean isError
    ) {
        public ToolResult(List<ToolContent> content) {
            this(content, false);
        }

        public static ToolResult text(String text) {
            return new ToolResult(List.of(new ToolContent(text)));
        }

        public static ToolResult error(String errorMessage) {
            return new ToolResult(List.of(new ToolContent(errorMessage)), true);
        }
    }

    /**
     * Content item within a tool result.
     *
     * @param type Content type: "text", "image", or "resource"
     * @param text Text content (for type="text")
     * @param data Base64 data (for type="image")
     * @param uri Resource URI (for type="resource")
     */
    public record ToolContent(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text,
            @JsonProperty("data") String data,
            @JsonProperty("uri") URI uri
    ) {
        /**
         * Create a text content.
         */
        public ToolContent(String text) {
            this("text", text, null, null);
        }

        /**
         * Create a content with specified type.
         */
        public ToolContent(String type, String text) {
            this(type, text, null, null);
        }

        public ToolContent(String type, String text, String data, URI uri) {
            this.type = type != null ? type : "text";
            this.text = text;
            this.data = data;
            this.uri = uri;
        }

        public boolean isText() {
            return "text".equals(type);
        }

        public boolean isImage() {
            return "image".equals(type);
        }

        public boolean isResource() {
            return "resource".equals(type);
        }
    }

    // ---------------------------------------------------------------------------
    // PROMPTS - Templated prompts for LLMs
    // ---------------------------------------------------------------------------

    /**
     * An MCP Prompt represents a templated prompt.
     *
     * @param name Unique prompt name
     * @param description Description of the prompt
     * @param arguments Prompt template arguments
     */
    public record Prompt(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("arguments") List<PromptArgument> arguments
    ) {
        public Prompt {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Prompt name cannot be blank");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Prompt description cannot be blank");
            }
        }

        public Prompt(String name, String description) {
            this(name, description, List.of());
        }
    }

    /**
     * Argument definition for a prompt template.
     *
     * @param name Argument name
     * @param description Description
     * @param required Whether the argument is required
     */
    public record PromptArgument(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("required") boolean required
    ) {
        public PromptArgument(String name, String description, boolean required) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Prompt argument name cannot be blank");
            }
            this.name = name;
            this.description = description;
            this.required = required;
        }

        public PromptArgument(String name, String description) {
            this(name, description, false);
        }
    }

    /**
     * Message returned from getting a prompt.
     *
     * @param role Message role (system, user, assistant)
     * @param content Message content (can be text or structured)
     */
    public record PromptMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") Object content
    ) {
        public PromptMessage(String role, String content) {
            this(role, (Object) content);
        }

        /**
         * Valid roles for prompt messages.
         */
        public static final String ROLE_SYSTEM = "system";
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";
    }

    /**
     * Result returned from getting a prompt.
     *
     * @param description Description of the prompt
     * @param messages Prompt messages
     */
    public record PromptResult(
            @JsonProperty("description") String description,
            @JsonProperty("messages") List<PromptMessage> messages
    ) {
        public PromptResult(String description, List<PromptMessage> messages) {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("Prompt messages cannot be null or empty");
            }
            this.description = description;
            this.messages = messages;
        }

        public static PromptResult simple(String description, String userContent) {
            return new PromptResult(
                    description,
                    List.of(new PromptMessage(PromptMessage.ROLE_USER, userContent))
            );
        }
    }

    // ---------------------------------------------------------------------------
    // SERVER INFO - Server capabilities and metadata
    // ---------------------------------------------------------------------------

    /**
     * Server information exposed during initialization.
     *
     * @param protocolVersion MCP protocol version
     * @param capabilities Server capabilities
     * @param serverInfo Server metadata
     */
    public record ServerInfo(
            @JsonProperty("protocolVersion") String protocolVersion,
            @JsonProperty("capabilities") ServerCapabilities capabilities,
            @JsonProperty("serverInfo") ServerMetadata serverInfo
    ) {
        public static final String PROTOCOL_VERSION = "2025-06-18";

        public ServerInfo(ServerCapabilities capabilities, ServerMetadata serverInfo) {
            this(PROTOCOL_VERSION, capabilities, serverInfo);
        }
    }

    /**
     * Server capabilities.
     *
     * @param resources Whether resources are supported (experimental)
     * @param tools Whether tools are supported
     * @param prompts Whether prompts are supported
     * @param logging Whether logging is supported
     */
    public record ServerCapabilities(
            @JsonProperty("resources") ResourceCapabilities resources,
            @JsonProperty("tools") ToolCapabilities tools,
            @JsonProperty("prompts") PromptCapabilities prompts,
            @JsonProperty("logging") LoggingCapabilities logging
    ) {
        public ServerCapabilities(boolean resources, boolean tools, boolean prompts, boolean logging) {
            this(
                    resources ? new ResourceCapabilities(true, false) : null,
                    tools ? new ToolCapabilities(true) : null,
                    prompts ? new PromptCapabilities(true) : null,
                    logging ? new LoggingCapabilities() : null
            );
        }

        public static ServerCapabilities all() {
            return new ServerCapabilities(true, true, true, true);
        }

        public static ServerCapabilities toolsOnly() {
            return new ServerCapabilities(false, true, false, false);
        }

        public static ServerCapabilities resourcesAndTools() {
            return new ServerCapabilities(true, true, false, false);
        }
    }

    /**
     * Resource capabilities.
     *
     * @param subscribe Whether resource subscription is supported
     * @param listChanged Whether list changed notifications are supported
     */
    public record ResourceCapabilities(
            @JsonProperty("subscribe") boolean subscribe,
            @JsonProperty("listChanged") boolean listChanged
    ) {
        public ResourceCapabilities(boolean subscribe) {
            this(subscribe, false);
        }
    }

    /**
     * Tool capabilities.
     *
     * @param listChanged Whether list changed notifications are supported
     */
    public record ToolCapabilities(
            @JsonProperty("listChanged") boolean listChanged
    ) {
        public ToolCapabilities() {
            this(false);
        }
    }

    /**
     * Prompt capabilities.
     *
     * @param listChanged Whether list changed notifications are supported
     */
    public record PromptCapabilities(
            @JsonProperty("listChanged") boolean listChanged
    ) {
        public PromptCapabilities() {
            this(false);
        }
    }

    /**
     * Logging capabilities.
     */
    public record LoggingCapabilities() {
    }

    /**
     * Server metadata.
     *
     * @param name Server name
     * @param version Server version
     */
    public record ServerMetadata(
            @JsonProperty("name") String name,
            @JsonProperty("version") String version
    ) {
        public ServerMetadata(String name) {
            this(name, "1.0.0");
        }

        public ServerMetadata {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Server name cannot be blank");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // CLIENT INFO - Client capabilities and metadata
    // ---------------------------------------------------------------------------

    /**
     * Client information sent during initialization.
     *
     * @param protocolVersion MCP protocol version
     * @param capabilities Client capabilities
     * @param clientInfo Client metadata
     */
    public record ClientInfo(
            @JsonProperty("protocolVersion") String protocolVersion,
            @JsonProperty("capabilities") ClientCapabilities capabilities,
            @JsonProperty("clientInfo") ClientMetadata clientInfo
    ) {
        public ClientInfo(ClientCapabilities capabilities, ClientMetadata clientInfo) {
            this(ServerInfo.PROTOCOL_VERSION, capabilities, clientInfo);
        }
    }

    /**
     * Client capabilities.
     *
     * @param roots Whether roots list is supported
     * @param sampling Whether sampling is supported
     */
    public record ClientCapabilities(
            @JsonProperty("roots") RootsCapabilities roots,
            @JsonProperty("sampling") SamplingCapabilities sampling
    ) {
        public ClientCapabilities(boolean roots, boolean sampling) {
            this(
                    roots ? new RootsCapabilities() : null,
                    sampling ? new SamplingCapabilities() : null
            );
        }

        public static ClientCapabilities none() {
            return new ClientCapabilities(false, false);
        }
    }

    /**
     * Roots capabilities.
     */
    public record RootsCapabilities() {
    }

    /**
     * Sampling capabilities.
     */
    public record SamplingCapabilities() {
    }

    /**
     * Client metadata.
     *
     * @param name Client name
     * @param version Client version
     */
    public record ClientMetadata(
            @JsonProperty("name") String name,
            @JsonProperty("version") String version
    ) {
        public ClientMetadata(String name) {
            this(name, "1.0.0");
        }
    }

    // ---------------------------------------------------------------------------
    // INITIALIZATION RESULT
    // ---------------------------------------------------------------------------

    /**
     * Result of initialize method.
     *
     * @param protocolVersion MCP protocol version
     * @param capabilities Server capabilities
     * @param serverInfo Server metadata
     */
    public record InitializeResult(
            @JsonProperty("protocolVersion") String protocolVersion,
            @JsonProperty("capabilities") ServerCapabilities capabilities,
            @JsonProperty("serverInfo") ServerMetadata serverInfo
    ) {
        public InitializeResult(ServerCapabilities capabilities, ServerMetadata serverInfo) {
            this(ServerInfo.PROTOCOL_VERSION, capabilities, serverInfo);
        }
    }
}
