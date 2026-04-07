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
}
