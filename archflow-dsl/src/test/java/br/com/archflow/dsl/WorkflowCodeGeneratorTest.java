package br.com.archflow.dsl;

import br.com.archflow.model.config.LLMConfigPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O gerador de código, verificado <b>compilando e executando</b> o que ele gera.
 *
 * <p>Um teste de golden file — comparar a string gerada com um arquivo esperado
 * — provaria só que o gerador continua gerando o mesmo texto, inclusive se esse
 * texto não compilar. A pergunta que interessa é outra: o código gerado, ao
 * rodar, reconstrói o documento de origem?
 *
 * <p>Então aqui o código vai para o disco, passa pelo {@code javac} de verdade
 * (via {@link ToolProvider}), é carregado num classloader e executado. O
 * documento que sai é comparado com o que entrou. É lento em comparação com uma
 * asserção de string, e é o único jeito de a promessa "JSON → Java → JSON" não
 * ser uma suposição.
 */
@DisplayName("Gerador de código da DSL")
class WorkflowCodeGeneratorTest {

    /**
     * Compila o código gerado e executa o {@code build()}, devolvendo o
     * documento reconstruído.
     */
    private static WorkflowDocument compileAndRun(GeneratedCode code, Path dir) throws Exception {
        // relativePath() já traz os diretórios do pacote — javac aceita o
        // arquivo em qualquer lugar, mas manter a estrutura é o que um gerador
        // de verdade faria ao gravar em src/main/java.
        Path source = dir.resolve(code.relativePath());
        Files.createDirectories(source.getParent());
        Files.writeString(source, code.source(), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("sem JDK (só JRE) este teste não pode rodar")
                .isNotNull();

        Path classes = Files.createDirectories(dir.resolve("classes"));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = compiler.run(null, null, errors,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                source.toString());

        assertThat(result)
                .as("o código gerado não compilou:\n%s\n--- fonte ---\n%s",
                        errors.toString(StandardCharsets.UTF_8), code.source())
                .isZero();

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()},
                WorkflowCodeGeneratorTest.class.getClassLoader())) {
            Class<?> generated = loader.loadClass(code.qualifiedName());
            Method build = generated.getMethod("build");
            return (WorkflowDocument) build.invoke(null);
        }
    }

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        /**
         * O caso central: um fluxo completo — metadata, allowlist, config,
         * condição, caminho de erro e posições — sai igual do outro lado.
         */
        @Test
        @DisplayName("documento → código → documento devolve o mesmo documento")
        void fullRoundTrip(@TempDir Path dir) throws Exception {
            WorkflowDocument original = Workflows.define("rag-doc-qa")
                    .named("Document Q&A")
                    .describedAs("Responde citando a base")
                    .version("2.1.0")
                    .author("edson")
                    .category("rag")
                    .allowing("input", "pgvector", "openai", "notify")
                    .llm(LLMConfigPatch.builder()
                            .provider("openrouter")
                            .model("openai/gpt-4.1-mini")
                            .temperature(0.3)
                            .maxTokens(2048)
                            .build())
                    .step("pergunta", Nodes.component("input").at(60, 200))
                    .step("busca", Nodes.vectorSearch("pgvector")
                            .with("topK", 6)
                            .with("collection", "docs")
                            .at(280, 200))
                    .step("resposta", Nodes.llmChat("openai")
                            .with("model", "gpt-4o")
                            .with("temperature", 0.2)
                            .labeled("Sintetiza")
                            .at(500, 200))
                    .step("falhou", Nodes.component("notify").at(500, 400))
                    .edge("pergunta", "busca")
                    .edge("busca", "resposta", "chunks.size() > 0")
                    .onError("busca", "falhou")
                    .build();

            GeneratedCode code = WorkflowCodeGenerator.from(original.toMap())
                    .inPackage("gerado")
                    .generate();

            assertThat(code.isLossless())
                    .as("nada aqui deveria escapar da DSL: %s", code.unrepresented())
                    .isTrue();

            WorkflowDocument reconstruido = compileAndRun(code, dir);

            assertThat(reconstruido.toMap()).isEqualTo(original.toMap());
        }

        @Test
        @DisplayName("mcp-agent preserva tipo, componentId e override de LLM")
        void mcpAgentRoundTrip(@TempDir Path dir) throws Exception {
            WorkflowDocument original = Workflows.define("mcp")
                    .llm(LLMConfigPatch.builder().provider("openrouter").model("flow-model").build())
                    .allowing("mcp-agent")
                    .step("agent", Nodes.mcpAgent()
                            .with("systemPrompt", "Use as ferramentas")
                            .with("model", "step-model")
                            .with("temperature", 0.1))
                    .build();

            GeneratedCode code = WorkflowCodeGenerator.from(original.toMap()).generate();

            assertThat(code.isLossless()).as("perdas: %s", code.unrepresented()).isTrue();
            assertThat(code.source()).contains("Nodes.component(\"agent\", \"mcp-agent\")");
            assertThat(compileAndRun(code, dir).toMap()).isEqualTo(original.toMap());
        }

        @Test
        @DisplayName("passos do motor (APPROVAL/ORCHESTRATE) sobrevivem ao round-trip")
        void engineStepsRoundTrip(@TempDir Path dir) throws Exception {
            WorkflowDocument original = Workflows.define("gated")
                    .step("gate", Nodes.approval().with("prompt", "Aprova?"))
                    .step("orq", Nodes.orchestrate().with("budget", 1000))
                    .edge("gate", "orq")
                    .build();

            GeneratedCode code = WorkflowCodeGenerator.from(original.toMap()).generate();

            assertThat(code.source())
                    .as("um APPROVAL regenerado como component() perderia o portão humano")
                    .contains("Nodes.approval()")
                    .contains("Nodes.orchestrate()");
            assertThat(compileAndRun(code, dir).toMap()).isEqualTo(original.toMap());
        }

        @Test
        @DisplayName("o round-trip parte do YAML tal como o servidor o entrega")
        void fromYaml(@TempDir Path dir) throws Exception {
            WorkflowDocument original = Workflows.define("f")
                    .named("Fluxo")
                    .step("a", Nodes.component("input"))
                    .step("b", Nodes.component("output"))
                    .edge("a", "b")
                    .build();

            GeneratedCode code = WorkflowCodeGenerator.fromYaml(original.toYaml()).generate();

            assertThat(compileAndRun(code, dir).toMap()).isEqualTo(original.toMap());
        }
    }

    @Nested
    @DisplayName("o que não se consegue representar é declarado")
    class Losses {

        @Test
        @DisplayName("campos de infraestrutura do servidor são reportados, não descartados calados")
        void reportsServerFields() {
            // É o que POST/PUT /api/workflows acrescentam ao gravar.
            Map<String, Object> doServidor = new java.util.LinkedHashMap<>(
                    Workflows.define("f").step("a", Nodes.component("x")).build().toMap());
            doServidor.put("status", "active");
            doServidor.put("updatedAt", "2026-07-26T10:00:00Z");

            GeneratedCode code = WorkflowCodeGenerator.from(doServidor).generate();

            assertThat(code.isLossless()).isFalse();
            assertThat(code.unrepresented())
                    .anySatisfy(p -> assertThat(p).contains("documento.status"))
                    .anySatisfy(p -> assertThat(p).contains("documento.updatedAt"));
        }

        @Test
        @DisplayName("chave desconhecida num passo é reportada")
        void reportsUnknownStepKey() {
            Map<String, Object> doc = Map.of(
                    "id", "f",
                    "steps", List.of(new java.util.LinkedHashMap<>(Map.of(
                            "id", "a", "type", "input", "retryPolicy", "exponential"))));

            assertThat(WorkflowCodeGenerator.from(doc).generate().unrepresented())
                    .anySatisfy(p -> assertThat(p).contains("passo a.retryPolicy"));
        }

        /**
         * A DSL não tem aresta de erro condicional. Gerar {@code onError} perde a
         * condição e gerar {@code edge} perde o caminho de erro — as duas são
         * perdas, então o gerador escolhe preservar o caminho de erro (o que
         * muda o comportamento se apagado) e <b>declara</b> a condição perdida.
         */
        @Test
        @DisplayName("caminho de erro com condição declara a perda")
        void errorPathWithConditionIsReported() {
            Map<String, Object> doc = Map.of(
                    "id", "f",
                    "steps", List.of(
                            Map.of("id", "a", "type", "x", "connections", List.of(
                                    Map.of("sourceId", "a", "targetId", "b",
                                            "condition", "erro == 'timeout'", "isErrorPath", true))),
                            Map.of("id", "b", "type", "y")));

            GeneratedCode code = WorkflowCodeGenerator.from(doc).generate();

            assertThat(code.isLossless()).isFalse();
            assertThat(code.unrepresented()).anySatisfy(p -> assertThat(p)
                    .contains("caminho de erro E tem condição"));
            assertThat(code.source()).contains(".onError(\"a\", \"b\")");
        }
    }

    @Nested
    @DisplayName("nome da classe")
    class Naming {

        @Test
        @DisplayName("derivado do id, em PascalCase")
        void derivedFromId() {
            assertThat(WorkflowCodeGenerator.classNameFor("rag-doc-qa")).isEqualTo("RagDocQaFlow");
            assertThat(WorkflowCodeGenerator.classNameFor("meu_fluxo v2")).isEqualTo("MeuFluxoV2Flow");
        }

        @Test
        @DisplayName("id que começa com dígito não vira classe inválida")
        void digitPrefixIsFixed() {
            assertThat(WorkflowCodeGenerator.classNameFor("2fa-check"))
                    .as("class 2faCheckFlow não compila")
                    .isEqualTo("Workflow2faCheckFlow");
        }

        @Test
        @DisplayName("nome explícito vence")
        void explicitWins() {
            GeneratedCode code = WorkflowCodeGenerator
                    .from(Workflows.define("f").step("a", Nodes.component("x")).build().toMap())
                    .className("MeuFluxo")
                    .generate();

            assertThat(code.className()).isEqualTo("MeuFluxo");
            assertThat(code.source()).contains("public final class MeuFluxo");
        }
    }

    @Test
    @DisplayName("aspas e quebras de linha na config não quebram o código gerado")
    void escapesLiterals(@TempDir Path dir) throws Exception {
        WorkflowDocument original = Workflows.define("f")
                .step("a", Nodes.component("prompt")
                        .with("template", "Diga \"olá\"\nem duas linhas\tcom tab"))
                .build();

        GeneratedCode code = WorkflowCodeGenerator.from(original.toMap()).generate();

        assertThat(compileAndRun(code, dir).toMap()).isEqualTo(original.toMap());
    }

    @Test
    @DisplayName("config aninhada (mapa e lista) sobrevive")
    void nestedConfig(@TempDir Path dir) throws Exception {
        WorkflowDocument original = Workflows.define("f")
                .step("a", Nodes.component("router")
                        .with("routes", List.of("billing", "tech"))
                        .with("limites", Map.of("maxTokens", 500)))
                .build();

        GeneratedCode code = WorkflowCodeGenerator.from(original.toMap()).generate();

        assertThat(compileAndRun(code, dir).toMap()).isEqualTo(original.toMap());
    }
}
