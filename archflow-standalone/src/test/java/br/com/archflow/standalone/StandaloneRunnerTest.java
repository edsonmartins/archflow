package br.com.archflow.standalone;

import br.com.archflow.standalone.StandaloneRunner.CliArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StandaloneRunner")
class StandaloneRunnerTest {

    @Test @DisplayName("should parse CLI args with all options")
    void shouldParseFullArgs() {
        String[] args = {"workflow.json", "--input", "Hello", "--var", "key1=value1",
                "--var", "key2=value2", "--timeout", "120", "--threads", "8", "--plugins", "/opt/plugins"};

        CliArgs cli = CliArgs.parse(args);

        assertThat(cli.workflowPath()).isEqualTo("workflow.json");
        assertThat(cli.inputText()).isEqualTo("Hello");
        assertThat(cli.variables()).containsEntry("key1", "value1").containsEntry("key2", "value2");
        assertThat(cli.timeoutSeconds()).isEqualTo(120);
        assertThat(cli.maxThreads()).isEqualTo(8);
        assertThat(cli.pluginsPath()).isEqualTo("/opt/plugins");
    }

    @Test @DisplayName("should parse minimal CLI args")
    void shouldParseMinimalArgs() {
        CliArgs cli = CliArgs.parse(new String[]{"my-flow.json"});

        assertThat(cli.workflowPath()).isEqualTo("my-flow.json");
        assertThat(cli.inputText()).isNull();
        assertThat(cli.variables()).isEmpty();
        assertThat(cli.timeoutSeconds()).isEqualTo(300);
        assertThat(cli.maxThreads()).isEqualTo(4);
    }

    @Test @DisplayName("should load workflow from file")
    void shouldLoadWorkflow(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test-flow.json");
        Files.writeString(file, """
                {
                  "id": "test-1",
                  "metadata": {"name": "Test", "description": "A test", "version": "1.0"},
                  "steps": [],
                  "configuration": null
                }
                """);

        StandaloneRunner runner = new StandaloneRunner();
        var flow = runner.loadWorkflow(file.toString());

        assertThat(flow.getId()).isEqualTo("test-1");
        assertThat(flow.getMetadata().name()).isEqualTo("Test");
    }

    @Test @DisplayName("should throw for nonexistent workflow file")
    void shouldThrowForNonexistentFile() {
        var runner = new StandaloneRunner();
        assertThatThrownBy(() -> runner.loadWorkflow("/nonexistent/path.json"))
                .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("should create runner and close without error")
    void shouldCreateAndClose() throws Exception {
        var runner = new StandaloneRunner();
        runner.close(); // Should not throw
    }

    @Test @DisplayName("should export workflow to file and verify content")
    void shouldExportWorkflowContent(@TempDir Path tempDir) throws Exception {
        var serializer = new FlowSerializer();
        var flow = serializer.deserialize("""
                {"id": "verify-export", "metadata": {"name": "V", "version": "1.0"}, "steps": [], "configuration": null}
                """);
        Path file = tempDir.resolve("out.json");
        new StandaloneRunner().exportWorkflow(flow, file);

        String content = java.nio.file.Files.readString(file);
        assertThat(content).contains("verify-export").contains("\"name\"");
    }

    @Test @DisplayName("should parse var with equals in value")
    void shouldParseVarWithEquals() {
        var cli = StandaloneRunner.CliArgs.parse(new String[]{"f.json", "--var", "url=http://host:8080/path?a=1"});
        assertThat(cli.variables().get("url")).isEqualTo("http://host:8080/path?a=1");
    }

    @Test @DisplayName("should use default plugins path as null")
    void shouldDefaultPluginsNull() {
        var cli = StandaloneRunner.CliArgs.parse(new String[]{"f.json"});
        assertThat(cli.pluginsPath()).isNull();
    }

    @Test @DisplayName("should export and reload workflow")
    void shouldExportAndReload(@TempDir Path tempDir) throws Exception {
        // Create a flow via serializer
        FlowSerializer serializer = new FlowSerializer();
        var flow = serializer.deserialize("""
                {
                  "id": "export-test",
                  "metadata": {"name": "Export Test", "description": "test", "version": "1.0"},
                  "steps": [
                    {"id": "s1", "type": "AGENT", "componentId": "my-agent", "operation": "run", "config": {}, "connections": []}
                  ]
                }
                """);

        Path exportPath = tempDir.resolve("exported.json");
        StandaloneRunner runner = new StandaloneRunner();
        runner.exportWorkflow(flow, exportPath);

        assertThat(Files.exists(exportPath)).isTrue();

        var reloaded = runner.loadWorkflow(exportPath.toString());
        assertThat(reloaded.getId()).isEqualTo("export-test");
        assertThat(reloaded.getSteps()).hasSize(1);
    }

    /**
     * RCHFLW-12 no segundo leitor: o {@code run} entrega ao agente o fluxo SEM as anotações do canvas e sem
     * as arestas que apontam para elas. Sem isto, um fluxo exportado do designer com uma nota voltava a
     * falhar no CLI com "component not found: sticky-note".
     */
    @Test
    @DisplayName("run entrega ao agente o fluxo sem as anotações do canvas")
    void runIgnoraAnotacoes(@TempDir Path tempDir) throws Exception {
        Path arquivo = tempDir.resolve("flow-notas.json");
        Files.writeString(arquivo, """
                {"id": "flow-notas", "metadata": {"name": "Com notas", "version": "1.0.0"},
                 "steps": [
                   {"id": "nota", "componentId": "sticky-note", "type": "STICKY-NOTE"},
                   {"id": "decide", "componentId": "condition", "type": "CONDITION",
                    "configuration": {"conditionExpression": "true"},
                    "connections": [{"sourceId": "decide", "targetId": "nota"}]},
                   {"id": "divisor", "type": "SECTION-DIVIDER"}
                 ]}
                """);
        java.util.concurrent.atomic.AtomicReference<br.com.archflow.model.flow.Flow> enviado =
                new java.util.concurrent.atomic.AtomicReference<>();
        StandaloneRunner runner = new StandaloneRunner() {
            @Override
            protected java.util.concurrent.CompletableFuture<br.com.archflow.model.flow.FlowResult> submit(
                    br.com.archflow.agent.ArchFlowAgent agent, br.com.archflow.model.flow.Flow flow,
                    java.util.Map<String, Object> input) {
                enviado.set(flow);
                return java.util.concurrent.CompletableFuture.completedFuture(concluido());
            }
        };
        try {
            runner.run(CliArgs.parse(new String[]{arquivo.toString()}));
        } finally {
            runner.close();
        }

        assertThat(enviado.get().getSteps()).extracting(s -> s.getId()).containsExactly("decide");
        assertThat(enviado.get().getSteps().get(0).getConnections()).isEmpty();
    }

    private static br.com.archflow.model.flow.FlowResult concluido() {
        return new br.com.archflow.model.flow.FlowResult() {
            @Override public br.com.archflow.model.enums.ExecutionStatus getStatus() {
                return br.com.archflow.model.enums.ExecutionStatus.COMPLETED;
            }
            @Override public java.util.Optional<Object> getOutput() { return java.util.Optional.empty(); }
            @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
            @Override public java.util.List<br.com.archflow.model.error.ExecutionError> getErrors() {
                return java.util.List.of();
            }
        };
    }
}
