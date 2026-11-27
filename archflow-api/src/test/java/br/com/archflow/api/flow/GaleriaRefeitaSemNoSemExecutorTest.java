package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.api.template.impl.TemplateControllerImpl;
import br.com.archflow.api.web.template.SpringTemplateController;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.template.WorkflowTemplateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * As receitas refeitas da galeria (RCHFLW-202) não ganham a marca "Nós sem executor".
 *
 * <p>A galeria pergunta ao {@code POST /api/templates/unsupported-node-types} quais nós de cada receita local o motor
 * não executa ({@code template-api.ts markUnsupported}) e mostra a marca no card e no detalhe. A checagem do Executar
 * no designer ({@code NOT_EXECUTABLE_NODE_TYPES}) só espelha a lista de tipos da paleta sem execução; a do endpoint
 * também conta o nó que cai num componente que não existe — um {@code vector-search} sem base e sem backend, por
 * exemplo. Por isso o teste da UI não basta: este passa as duas receitas de {@code archflow-ui/public/templates} pelo
 * mesmo handler, com o corpo que a galeria monta ({@code documentStepsForCheck}: id, type, componentId e a config em
 * {@code configuration}) e o motor como o servidor o tem — catálogo sem componentes e o LLM padrão da plataforma
 * ({@code archflow.llm.provider}), que é de onde o LLM Chat sem provider no nó herda o dele.</p>
 *
 * <p>Os outros 4 templates da galeria ainda dependem de integração que a plataforma não tem e esperam a decisão do
 * RCHFLW-205; entram aqui quando forem refeitos.</p>
 */
@DisplayName("galeria refeita (RCHFLW-202): unsupported-node-types não marca rag-doc-qa nem content-writer")
class GaleriaRefeitaSemNoSemExecutorTest {

    private static final List<String> REFEITAS = List.of("rag-doc-qa", "content-writer");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SpringTemplateController endpoint() {
        ComponentCatalog catalogo = mock(ComponentCatalog.class);
        when(catalogo.getComponent(anyString())).thenReturn(Optional.empty());
        DefaultFlowStepFactory fabrica = new DefaultFlowStepFactory(catalogo, mock(DynamicWorkflowService.class),
                mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                mock(br.com.archflow.engine.core.StateManager.class), null);
        fabrica.setPlatformDefaultLLM(ResolvedLLMConfig.builder().provider("openrouter").model("modelo-padrao")
                .build());
        return new SpringTemplateController(new TemplateControllerImpl(WorkflowTemplateRegistry.getInstance()),
                new InMemoryWorkflowRuntimeStore(), new NodeSupport(fabrica));
    }

    /** O corpo de {@code documentStepsForCheck}: a config do designer vai em {@code configuration}. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> comoAGaleriaPergunta(String id) throws Exception {
        Map<String, Object> receita = JSON.readValue(new File("../archflow-ui/public/templates/" + id + ".json"),
                Map.class);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) receita.get("steps")) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", s.get("id"));
            step.put("type", s.get("type"));
            step.put("componentId", s.get("componentId"));
            step.put("configuration", s.getOrDefault("config", Map.of()));
            steps.add(step);
        }
        return steps;
    }

    @Test
    @DisplayName("como a galeria pergunta, nenhuma das duas receitas tem nó sem executor")
    void receitasRefeitasNaoSaoMarcadas() throws Exception {
        Map<String, List<Map<String, Object>>> corpo = new LinkedHashMap<>();
        for (String id : REFEITAS) {
            corpo.put(id, comoAGaleriaPergunta(id));
        }

        Map<String, List<String>> marcados = endpoint().unsupportedNodeTypes(corpo);

        assertThat(marcados).containsOnlyKeys(REFEITAS);
        marcados.forEach((id, tipos) -> assertThat(tipos).as(id).isEmpty());
    }

    @Test
    @DisplayName("a mesma checagem marca o nó que a receita não pode ter (o teste pega a regressão)")
    void aChecagemMarcaOQueNaoExecuta() {
        // As três formas que já estiveram nas receitas: busca sem base nem backend (o primeiro rag-doc-qa refeito),
        // o prompt-template e o agent sem executor (os originais). Se o endpoint deixar de marcá-las, o teste acima
        // passaria por nada.
        Map<String, List<String>> marcados = endpoint().unsupportedNodeTypes(Map.of("regressao", List.of(
                Map.of("id", "s1", "type", "input", "componentId", "input", "configuration", Map.of()),
                Map.of("id", "s2", "type", "vector-search", "componentId", "vector-search",
                        "configuration", Map.of("topK", 6)),
                Map.of("id", "s3", "type", "prompt-template", "componentId", "prompt-template",
                        "configuration", Map.of("template", "rag.qa")),
                Map.of("id", "s4", "type", "agent", "componentId", "agent", "configuration", Map.of()),
                Map.of("id", "s5", "type", "llm-chat", "componentId", "llm-chat",
                        "configuration", Map.of("userMessage", "${s1}")))));

        assertThat(marcados.get("regressao")).containsExactly("vector-search", "prompt-template", "agent");
    }
}
