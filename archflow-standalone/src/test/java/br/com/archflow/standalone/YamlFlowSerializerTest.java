package br.com.archflow.standalone;

import br.com.archflow.model.config.LLMConfig;
import br.com.archflow.model.config.RetryPolicy;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.standalone.model.SerializableConnection;
import br.com.archflow.standalone.model.SerializableFlow;
import br.com.archflow.standalone.model.SerializableFlowConfig;
import br.com.archflow.standalone.model.SerializableStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("YamlFlowSerializer")
class YamlFlowSerializerTest {

    private YamlFlowSerializer yaml;
    private FlowSerializer json;

    @BeforeEach
    void setUp() {
        yaml = new YamlFlowSerializer();
        json = new FlowSerializer();
    }

    private SerializableFlow createTestFlow() {
        var conn = new SerializableConnection("step-1", "step-2", null, false);
        var step1 = new SerializableStep("step-1", StepType.AGENT, "research-agent", "execute",
                Map.of("maxRetries", 3, "temperature", 0.7), List.of(conn));
        var step2 = new SerializableStep("step-2", StepType.TOOL, "text-transform", "summarize",
                Map.of("format", "markdown"), List.of());

        var llm = new LLMConfig("gpt-4o", 0.7, 2048, 30000, Map.of());
        var retry = new RetryPolicy(3, 1000, 2.0, Set.of());
        var config = new SerializableFlowConfig(60000, retry, llm, null);

        var metadata = new FlowMetadata("SAC Flow", "Customer support", "1.2.0",
                "support", "testing", List.of("sac", "demo"));

        return new SerializableFlow("flow-sac", metadata, List.of(step1, step2), config);
    }

    @Test
    @DisplayName("serialize produces readable YAML")
    void serialize() throws IOException {
        String out = yaml.serialize(createTestFlow());

        assertThat(out).contains("flow-sac");
        assertThat(out).contains("SAC Flow");
        assertThat(out).contains("research-agent");
        assertThat(out).contains("steps");
        // MINIMIZE_QUOTES should keep simple strings unquoted
        assertThat(out).contains("id: flow-sac");
        // No doc start marker
        assertThat(out).doesNotStartWith("---");
    }

    @Test
    @DisplayName("deserialize restores every field")
    void deserialize() throws IOException {
        SerializableFlow original = createTestFlow();
        String out = yaml.serialize(original);
        SerializableFlow restored = yaml.deserialize(out);

        assertThat(restored.getId()).isEqualTo("flow-sac");
        assertThat(restored.getMetadata().name()).isEqualTo("SAC Flow");
        assertThat(restored.getMetadata().version()).isEqualTo("1.2.0");
        assertThat(restored.getSteps()).hasSize(2);
        SerializableStep first = (SerializableStep) restored.getSteps().get(0);
        assertThat(first.getId()).isEqualTo("step-1");
        assertThat(first.getComponentId()).isEqualTo("research-agent");
    }

    @Test
    @DisplayName("round-trip YAML -> JSON -> YAML produces identical YAML")
    void yamlJsonRoundTrip() throws IOException {
        SerializableFlow original = createTestFlow();

        String yamlOut = yaml.serialize(original);
        SerializableFlow fromYaml = yaml.deserialize(yamlOut);
        String jsonOut = json.serialize(fromYaml);
        SerializableFlow fromJson = json.deserialize(jsonOut);
        String yamlAgain = yaml.serialize(fromJson);

        assertThat(yamlAgain).isEqualTo(yamlOut);
    }

    @Test
    @DisplayName("round-trip JSON -> YAML -> JSON preserves structure")
    void jsonYamlRoundTrip() throws IOException {
        SerializableFlow original = createTestFlow();

        String jsonOut = json.serialize(original);
        SerializableFlow fromJson = json.deserialize(jsonOut);
        String yamlOut = yaml.serialize(fromJson);
        SerializableFlow fromYaml = yaml.deserialize(yamlOut);

        assertThat(fromYaml.getId()).isEqualTo(original.getId());
        assertThat(fromYaml.getSteps()).hasSize(original.getSteps().size());
        for (int i = 0; i < original.getSteps().size(); i++) {
            SerializableStep a = (SerializableStep) original.getSteps().get(i);
            SerializableStep b = (SerializableStep) fromYaml.getSteps().get(i);
            assertThat(b.getId()).isEqualTo(a.getId());
            assertThat(b.getComponentId()).isEqualTo(a.getComponentId());
            assertThat(b.getType()).isEqualTo(a.getType());
        }
    }

    @Test
    @DisplayName("exportToFile writes valid YAML")
    void exportFile(@TempDir Path tempDir) throws IOException {
        SerializableFlow flow = createTestFlow();
        Path out = tempDir.resolve("workflow.yaml");

        yaml.exportToFile(flow, out);

        assertThat(Files.exists(out)).isTrue();
        String content = Files.readString(out);
        assertThat(content).contains("flow-sac");
    }

    @Test
    @DisplayName("importFromFile reads back the exported flow")
    void importFile(@TempDir Path tempDir) throws IOException {
        SerializableFlow flow = createTestFlow();
        Path out = tempDir.resolve("workflow.yaml");
        yaml.exportToFile(flow, out);

        SerializableFlow loaded = yaml.importFromFile(out);

        assertThat(loaded.getId()).isEqualTo(flow.getId());
        assertThat(loaded.getSteps()).hasSize(flow.getSteps().size());
    }

    @Test
    @DisplayName("deserialize with empty payload throws")
    void deserializeEmpty() {
        assertThatThrownBy(() -> yaml.deserialize(""))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> yaml.deserialize(null))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("deserialize tolerates unknown fields")
    void deserializeWithUnknownFields() throws IOException {
        String withExtras = """
                id: flow-sac
                unknownField: whatever
                metadata:
                  name: SAC Flow
                  description: Test
                  version: 1.0.0
                  category: support
                  author: test
                  tags:
                    - sac
                steps: []
                """;

        SerializableFlow flow = yaml.deserialize(withExtras);
        assertThat(flow.getId()).isEqualTo("flow-sac");
        assertThat(flow.getMetadata().name()).isEqualTo("SAC Flow");
    }

    /**
     * {@code getLLMPatch()} é derivado ({@code configuration} do passo, {@code llmConfig} do fluxo) e
     * {@code LLMConfigPatch.isEmpty()} é um predicado — nenhum dos dois é campo do documento. Sem
     * {@code @JsonIgnore} o Jackson os tratava como propriedades e a aba Código mostrava blocos
     * {@code llmpatch:} / {@code empty:} que o usuário não escreveu (e que voltavam gravados no JSON).
     */
    @Test
    @DisplayName("YAML não expõe os derivados llmpatch/empty, e o round-trip preserva a config de LLM")
    void yamlOmitsDerivedLlmPatch() throws IOException {
        SerializableFlow original = createTestFlow();

        String out = yaml.serialize(original);

        assertThat(out.toLowerCase()).doesNotContain("llmpatch").doesNotContain("empty:");
        assertThat(json.serialize(original).toLowerCase()).doesNotContain("llmpatch").doesNotContain("\"empty\"");

        SerializableFlow restored = yaml.deserialize(out);
        // O patch continua derivável dos campos reais — quem lê getLLMPatch() em runtime não perde nada.
        assertThat(restored.getConfiguration().getLLMConfig().model()).isEqualTo("gpt-4o");
        assertThat(restored.getConfiguration().getLLMPatch().model()).contains("gpt-4o");
        SerializableStep step1 = (SerializableStep) restored.getSteps().get(0);
        assertThat(step1.getLLMPatch().temperature()).hasValue(0.7);
        assertThat(yaml.serialize(restored).toLowerCase()).doesNotContain("llmpatch").doesNotContain("empty:");
    }

    /** Documentos já gravados com o bloco espúrio continuam abrindo — e o bloco some na próxima escrita. */
    @Test
    @DisplayName("YAML antigo com llmpatch/empty ainda carrega e é limpo na reescrita")
    void legacyYamlWithLlmPatchStillLoads() throws IOException {
        String legacy = """
                id: flow-sac
                metadata:
                  name: SAC Flow
                  version: 1.0.0
                configuration:
                  timeout: 1000
                  llmpatch:
                    empty: true
                steps:
                  - id: s1
                    type: llm-chat
                    configuration:
                      model: gpt-4o
                    llmpatch:
                      empty: false
                """;

        SerializableFlow flow = yaml.deserialize(legacy);

        assertThat(flow.getSteps()).hasSize(1);
        assertThat(((SerializableStep) flow.getSteps().get(0)).getLLMPatch().model()).contains("gpt-4o");
        assertThat(yaml.serialize(flow).toLowerCase()).doesNotContain("llmpatch").doesNotContain("empty:");
    }
}
