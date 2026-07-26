package br.com.archflow.dsl;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * O documento canônico pronto — o mesmo que o designer grava e que o motor
 * desserializa.
 *
 * <p>Resultado de {@link WorkflowBuilder#build()}, e por isso já validado: se
 * você tem uma instância, ela passou pelas conferências descritas lá.
 *
 * @since 1.1.0
 */
public final class WorkflowDocument {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code MINIMIZE_QUOTES} deixa o YAML legível (sem aspas em toda string) e
     * {@code WRITE_DOC_START_MARKER} desligado tira o {@code ---} do topo — é o
     * mesmo ajuste do {@code YamlFlowSerializer}, para que um fluxo escrito na
     * DSL e outro exportado pelo servidor não difiram só na formatação.
     */
    private static final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private final Map<String, Object> document;

    WorkflowDocument(Map<String, Object> document) {
        this.document = document;
    }

    /**
     * O documento como mapa, que é a forma que o motor consome diretamente
     * ({@code WorkflowDeserializer.toFlow}). Sem serializar nada no caminho.
     */
    public Map<String, Object> toMap() {
        return document;
    }

    /** Identificador do fluxo. */
    public String id() {
        return String.valueOf(document.get("id"));
    }

    /** JSON identado — a forma que o document store guarda. */
    public String toJson() {
        // Identador explícito: o pretty-printer default do Jackson usa espaço
        // antes dos dois-pontos em objetos e o arquivo fica diferente do que o
        // resto do sistema produz.
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        try {
            return JSON.writer(printer).writeValueAsString(document);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao serializar o fluxo " + id() + " em JSON", e);
        }
    }

    /** YAML — o mesmo texto que {@code GET /api/workflows/{id}/yaml} devolve. */
    public String toYaml() {
        try {
            return YAML.writeValueAsString(document);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao serializar o fluxo " + id() + " em YAML", e);
        }
    }

    /**
     * Grava em disco, escolhendo JSON ou YAML pela extensão do arquivo
     * ({@code .yaml}/{@code .yml} → YAML; qualquer outra → JSON).
     *
     * @return o próprio caminho, para encadear
     */
    public Path writeTo(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String content = name.endsWith(".yaml") || name.endsWith(".yml") ? toYaml() : toJson();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao gravar o fluxo " + id() + " em " + file, e);
        }
        return file;
    }

    @Override
    public String toString() {
        return "WorkflowDocument[" + id() + "]";
    }
}
