package br.com.archflow.standalone;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.standalone.model.SerializableFlow;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serializes and deserializes archflow workflows to/from JSON.
 *
 * <p>Converts between the Flow interface (used at runtime) and
 * SerializableFlow (concrete classes that Jackson can handle).
 *
 * <h3>Export a workflow:</h3>
 * <pre>{@code
 * FlowSerializer serializer = new FlowSerializer();
 * serializer.exportToFile(myFlow, Path.of("workflow.json"));
 * }</pre>
 *
 * <h3>Import a workflow:</h3>
 * <pre>{@code
 * SerializableFlow flow = serializer.importFromFile(Path.of("workflow.json"));
 * agent.executeFlow(flow, Map.of("input", "Hello"));
 * }</pre>
 */
public class FlowSerializer {

    private static final Logger log = LoggerFactory.getLogger(FlowSerializer.class);
    private final ObjectMapper mapper;

    public FlowSerializer() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    /**
     * Serializes a Flow to JSON string.
     */
    public String serialize(Flow flow) throws IOException {
        SerializableFlow serializable = flow instanceof SerializableFlow sf
                ? sf : SerializableFlow.from(flow);
        return mapper.writeValueAsString(serializable);
    }

    /**
     * Deserializes a Flow from JSON string.
     */
    public SerializableFlow deserialize(String json) throws IOException {
        return mapper.readValue(json, SerializableFlow.class);
    }

    /**
     * Exports a Flow to a JSON file.
     */
    public void exportToFile(Flow flow, Path outputPath) throws IOException {
        String json = serialize(flow);
        Files.writeString(outputPath, json);
        log.info("Exported workflow '{}' to {}", flow.getId(), outputPath);
    }

    /**
     * Imports a Flow from a JSON file.
     */
    public SerializableFlow importFromFile(Path inputPath) throws IOException {
        String json = Files.readString(inputPath);
        SerializableFlow flow = deserialize(json);
        log.info("Imported workflow '{}' from {}", flow.getId(), inputPath);
        return flow;
    }

    /**
     * Imports a Flow from a classpath resource.
     */
    public SerializableFlow importFromResource(String resourcePath) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Resource not found: " + resourcePath);
            return mapper.readValue(is, SerializableFlow.class);
        }
    }

    /**
     * Returns the configured ObjectMapper for custom usage.
     */
    public ObjectMapper getMapper() {
        return mapper;
    }
}
