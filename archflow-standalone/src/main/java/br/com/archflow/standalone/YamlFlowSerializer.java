package br.com.archflow.standalone;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.standalone.model.SerializableFlow;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * YAML-based serializer for ArchFlow workflows — complementary to
 * {@link FlowSerializer}.
 *
 * <p>YAML is the human-friendly format of choice for source control:
 * diffs are readable, indentation carries structure, and side-by-side
 * editing in the canvas + code panel becomes trivial. Round-trip with
 * {@link FlowSerializer} is guaranteed by reusing the same
 * {@link SerializableFlow} model — serializing either way produces the
 * same object graph.
 *
 * <h3>Round-trip JSON ↔ YAML</h3>
 * <pre>{@code
 * FlowSerializer json = new FlowSerializer();
 * YamlFlowSerializer yaml = new YamlFlowSerializer();
 *
 * String asYaml  = yaml.serialize(flow);         // canvas → yaml
 * SerializableFlow restored = yaml.deserialize(asYaml);
 * String asJson  = json.serialize(restored);     // yaml → canvas JSON
 * }</pre>
 *
 * <h3>Reading/writing files</h3>
 * <pre>{@code
 * yaml.exportToFile(flow, Path.of("workflow.yaml"));
 * SerializableFlow loaded = yaml.importFromFile(Path.of("workflow.yaml"));
 * }</pre>
 */
public class YamlFlowSerializer {

    private static final Logger log = LoggerFactory.getLogger(YamlFlowSerializer.class);
    private final ObjectMapper mapper;

    public YamlFlowSerializer() {
        YAMLFactory factory = new YAMLFactory()
                // Keep the output clean and diff-friendly.
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR);
        this.mapper = new YAMLMapper(factory)
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Serialize a {@link Flow} to a YAML string.
     */
    public String serialize(Flow flow) throws IOException {
        SerializableFlow serializable = flow instanceof SerializableFlow sf
                ? sf
                : SerializableFlow.from(flow);
        return mapper.writeValueAsString(serializable);
    }

    /**
     * Deserialize a YAML string into a {@link SerializableFlow}.
     */
    public SerializableFlow deserialize(String yaml) throws IOException {
        if (yaml == null || yaml.isBlank()) {
            throw new IOException("YAML payload is empty");
        }
        return mapper.readValue(yaml, SerializableFlow.class);
    }

    /**
     * Exports a flow to a YAML file.
     */
    public void exportToFile(Flow flow, Path outputPath) throws IOException {
        String yaml = serialize(flow);
        Files.writeString(outputPath, yaml);
        log.info("Exported workflow '{}' to {}", flow.getId(), outputPath);
    }

    /**
     * Imports a flow from a YAML file.
     */
    public SerializableFlow importFromFile(Path inputPath) throws IOException {
        String yaml = Files.readString(inputPath);
        SerializableFlow flow = deserialize(yaml);
        log.info("Imported workflow '{}' from {}", flow.getId(), inputPath);
        return flow;
    }

    /**
     * Imports a flow from a classpath resource.
     */
    public SerializableFlow importFromResource(String resourcePath) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Resource not found: " + resourcePath);
            return mapper.readValue(is, SerializableFlow.class);
        }
    }

    /**
     * Returns the configured YAML ObjectMapper — useful when a caller
     * needs to serialize/deserialize custom companion data (e.g. a
     * Workflow metadata envelope) using the same conventions.
     */
    public ObjectMapper getMapper() {
        return mapper;
    }
}
