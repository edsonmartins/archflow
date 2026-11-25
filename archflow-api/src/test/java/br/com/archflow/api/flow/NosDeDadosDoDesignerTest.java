package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-56 — os nós de dados como o designer os grava chegam ao adapter escolhido no painel.
 *
 * <p>Antes: Vector search, Embedding e Memory falhavam com "component not found" (o backend ficava em
 * {@code vectorBackend}/{@code embeddingProvider}/{@code memoryBackend}, que o factory não lia) e o RAG com
 * "Embedding provider is required". Sem rede nem banco, o que se confere é que o passo chega ao adapter e que
 * o que falta é dito com o nome da propriedade/campo a preencher — nunca suprido pelo JSON do nó.</p>
 */
@DisplayName("nós de dados do designer → adapter escolhido no painel")
class NosDeDadosDoDesignerTest {

    private static StepResult run(String type, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "dados");
        node.put("type", type);
        node.put("componentId", type);
        node.put("config", config);
        Map<String, Object> json = new HashMap<>();
        json.put("id", "wf");
        json.put("metadata", Map.of("name", "Dados"));
        json.put("steps", List.of(node));
        var factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(), mock(DynamicWorkflowService.class),
                null, null, null);
        var flow = new DefaultWorkflowDeserializer(factory).toFlow(json);
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", "texto");
        return flow.getSteps().get(0).execute(ctx).join();
    }

    @Test
    @DisplayName("vector-search pgvector vai ao adapter e pede a conexão do servidor — o JSON não a supre")
    void vectorSearchPgvector() {
        StepResult result = run("vector-search", Map.of("vectorBackend", "pgvector", "pgDimensions", 1536,
                "pgvector.jdbcUrl", "jdbc:postgresql://de-quem-edita/x", "pgvector.username", "u",
                "pgvector.password", "p"));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString()
                .doesNotContain("component not found")
                .contains("[ADAPTER-CONEXAO]")
                .contains("campo 'pgDatasource'")
                .contains("archflow.adapters.pgvector.<nome>.jdbc-url");
    }

    @Test
    @DisplayName("RAG do designer chega ao RagChainAdapter com os providers traduzidos")
    void ragDoDesigner() {
        StepResult result = run("rag", Map.of("embeddingProvider", "openai", "vectorBackend", "pgvector",
                "llmProvider", "openai", "pgDatasource", "principal", "pgDimensions", 1536));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString()
                .as("antes: 'Embedding provider is required'")
                .doesNotContain("Embedding provider is required")
                .contains("a conexão 'principal' não está configurada no servidor")
                .contains("archflow.adapters.pgvector.principal.jdbc-url");
    }

    @Test
    @DisplayName("Embedding openai vai ao adapter e pede a chave do tenant, não 'component not found'")
    void embeddingOpenAi() {
        StepResult result = run("embedding", Map.of("embeddingProvider", "openai",
                "embeddingModel", "text-embedding-3-small"));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString()
                .doesNotContain("component not found")
                .contains("[LLM-CHAVE]")
                .contains("'openai'");
    }

    @Test
    @DisplayName("backend sem adapter diz qual e quais existem")
    void backendSemAdapter() {
        StepResult result = run("vector-search", Map.of("vectorBackend", "in-memory"));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString()
                .contains("o backend 'in-memory' não tem adapter de vectorstore")
                .contains("pgvector");
    }

    @Test
    @DisplayName("vector-store do designer não busca em silêncio: falha dizendo a operação, antes de tocar o banco")
    void vectorStoreSemOperacao() {
        StepResult result = run("vector-store", Map.of("vectorBackend", "pgvector", "pgDatasource", "principal",
                "pgDimensions", 1536));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString()
                .contains("(vector-store)")
                .contains("diga a operação em 'operation' (search, remove ou removeAll)")
                .contains("Nenhum adapter de vector store grava vetores")
                .doesNotContain("[ADAPTER-CONEXAO]");
    }
}
