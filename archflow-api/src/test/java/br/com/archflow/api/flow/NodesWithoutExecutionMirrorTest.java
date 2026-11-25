package br.com.archflow.api.flow;

import br.com.archflow.api.flow.adapter.AdapterNodeTypes;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.flow.CanvasAnnotation;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A lista de tipos sem execução do designer ({@code NOT_EXECUTABLE_NODE_TYPES} em {@code constants.ts}) é a
 * do motor ({@link NodesWithoutExecution#TYPES}) — e as duas dizem a verdade sobre o factory.
 *
 * <p>Nas duas direções, como o {@code AdapterNodeTypesMirrorTest}: um tipo que ganhar execução e ficar na
 * lista do designer continuaria bloqueando o Executar e marcado na paleta; um tipo que perder execução sem
 * entrar na lista voltaria ao "component not found" no meio do fluxo, sem aviso no editor.</p>
 *
 * <p>Lê o arquivo do designer do mesmo repositório, como o {@code CanvasAnnotationMirrorTest}.</p>
 */
@DisplayName("tipos sem execução: designer espelha NodesWithoutExecution")
class NodesWithoutExecutionMirrorTest {

    private static final Path CONSTANTS =
            Path.of("..", "archflow-ui", "src", "components", "FlowCanvas", "constants.ts");

    private static final Pattern QUOTED = Pattern.compile("'([a-z0-9-]+)'");

    private final DefaultFlowStepFactory factory = factory();

    private static DefaultFlowStepFactory factory() {
        // Catálogo vazio: o que um deployment sem plugins resolve — os tipos da lista não podem depender
        // de um plugin para existir.
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString())).thenReturn(Optional.empty());
        return new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);
    }

    /** O bloco do arquivo que começa na declaração {@code name} e vai até o fechamento indicado. */
    private static String block(String source, String name, String close) {
        int start = source.indexOf(name);
        assertThat(start).as("%s em constants.ts", name).isNotNegative();
        int end = source.indexOf(close, start);
        assertThat(end).as("fim de %s em constants.ts", name).isPositive();
        return source.substring(start, end);
    }

    private static Set<String> quoted(String text) {
        Matcher m = QUOTED.matcher(text);
        return m.results().map(r -> r.group(1)).collect(Collectors.toSet());
    }

    /** Todos os tipos que o designer conhece ({@code NODE_TYPE_TO_CATEGORY}), sem as anotações. */
    private static Set<String> designerTypes(String source) {
        Matcher m = Pattern.compile("'([a-z0-9-]+)'\\s*:\\s*'[a-z]+'")
                .matcher(block(source, "NODE_TYPE_TO_CATEGORY", "\n}"));
        return m.results().map(r -> r.group(1))
                .filter(t -> !CanvasAnnotation.isAnnotation(t))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a lista do designer é exatamente a do motor")
    void mesmaLista() throws Exception {
        assertThat(CONSTANTS).as("constants.ts do designer").exists();
        Set<String> doDesigner = quoted(block(Files.readString(CONSTANTS), "NOT_EXECUTABLE_NODE_TYPES", "])"));

        assertThat(doDesigner).as("tipos sem execução no designer").isNotEmpty();
        assertThat(doDesigner).isEqualTo(NodesWithoutExecution.TYPES);
    }

    @Test
    @DisplayName("todo tipo da lista realmente não executa: o factory o recusa no início da execução")
    void tiposDaListaNaoExecutam() {
        for (String type : NodesWithoutExecution.TYPES) {
            assertThat(factory.create(Map.of("id", "n", "type", type, "componentId", type)))
                    .as(type).isInstanceOf(UnsupportedNodeStep.class);
        }
    }

    /**
     * O designer decide só pela lista — não conhece os plugins carregados. O motor tem de dar a mesma
     * resposta mesmo quando o catálogo tem um componente com o id do tipo; senão o editor bloquearia o
     * Executar de um fluxo que o servidor roda.
     */
    @Test
    @DisplayName("o catálogo não isenta um tipo da lista: a regra é a mesma do designer")
    void catalogoNaoIsentaTiposDaLista() {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent(anyString()))
                .thenReturn(Optional.of(mock(br.com.archflow.model.ai.AIComponent.class)));
        DefaultFlowStepFactory comPlugins = new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);

        for (String type : NodesWithoutExecution.TYPES) {
            assertThat(comPlugins.create(Map.of("id", "n", "type", type, "componentId", type)))
                    .as(type).isInstanceOf(UnsupportedNodeStep.class);
        }
    }

    /**
     * A direção inversa: cada tipo do designer fora da lista tem caminho de execução — um passo dedicado
     * do motor ou um adapter LangChain4j (que executa quando o nó tem um provider com adapter). Um tipo novo
     * na paleta sem execução quebra este teste até entrar na lista.
     */
    @Test
    @DisplayName("todo tipo do designer fora da lista tem caminho de execução no motor")
    void tiposForaDaListaExecutam() throws Exception {
        Set<String> doDesigner = designerTypes(Files.readString(CONSTANTS));
        assertThat(doDesigner).as("tipos do designer").contains("llm-chat", "input", "agent");
        assertThat(doDesigner).as("a lista só cita tipos que o designer conhece")
                .containsAll(NodesWithoutExecution.TYPES);

        NodeSupport support = new NodeSupport(factory);
        List<String> semCaminho = doDesigner.stream()
                .filter(t -> !NodesWithoutExecution.contains(t))
                .filter(t -> AdapterNodeTypes.adapterTypeFor(t) == null)
                .filter(t -> !support.unsupportedNodeTypes(
                        List.of(Map.of("id", "n", "type", t, "componentId", t))).isEmpty())
                .sorted()
                .toList();

        assertThat(semCaminho)
                .as("tipo da paleta que cai em 'component not found' — falta execução ou entrar na lista")
                .isEmpty();
    }
}
