package br.com.archflow.api.flow;

import br.com.archflow.api.knowledge.DocumentChunk;
import br.com.archflow.api.knowledge.HashEmbeddingModel;
import br.com.archflow.api.knowledge.InMemoryKnowledgeRepository;
import br.com.archflow.api.knowledge.InMemoryKnowledgeVectorIndex;
import br.com.archflow.api.knowledge.KnowledgeBase;
import br.com.archflow.api.knowledge.KnowledgeEmbeddingModel;
import br.com.archflow.api.knowledge.KnowledgeService;
import br.com.archflow.api.knowledge.KnowledgeVectorIndex;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-55 — os nós {@code vector-search} e {@code rag} alcançam as Bases de Conhecimento.
 *
 * <p>Antes o nó ia ao {@code PgVectorStoreAdapter} (tabela {@code embeddings}, que a ingestão nunca
 * preenche) ou caía em "component not found"; o {@code knowledgeBaseId}/{@code vectorStoreId} era
 * ignorado. Aqui a base é populada como a ingestão faz (o mesmo modelo de embedding nos dois sentidos) e
 * o fluxo passa pelo deserializer de verdade.</p>
 */
@DisplayName("nó com base de conhecimento busca no KnowledgeService, no tenant da execução")
class NoDeBaseDeConhecimentoTest {

    private static final String TENANT = "acme";
    private static final String CHAT_PROVIDER = "provider-de-teste";

    /** O modelo da ingestão, que anota cada texto embutido — prova que a consulta usa o mesmo. */
    private static final class RecordingEmbeddings implements KnowledgeEmbeddingModel {
        final HashEmbeddingModel delegate = new HashEmbeddingModel(128);
        final List<String> embedded = new CopyOnWriteArrayList<>();

        @Override public float[] embed(String text) {
            embedded.add(text);
            return delegate.embed(text);
        }

        @Override public int dimension() {
            return delegate.dimension();
        }
    }

    private RecordingEmbeddings embeddings;
    private KnowledgeService service;
    private DefaultFlowStepFactory factory;
    private String baseId;

    @BeforeEach
    void setUp() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        InMemoryKnowledgeVectorIndex index = new InMemoryKnowledgeVectorIndex();
        embeddings = new RecordingEmbeddings();
        service = new KnowledgeService(repository, null, null, embeddings, index);
        KnowledgeBase base = service.createBase(TENANT, null, "suporte");
        baseId = base.id();
        // O que o DocumentIngestionHandler faz: trechos embutidos pelo mesmo modelo, versão ativada.
        List<String> trechos = List.of(
                "O prazo de reembolso é de 30 dias corridos após a compra.",
                "O suporte atende de segunda a sexta, das 8h às 18h.");
        var candidate = index.createCandidate(TENANT, baseId);
        index.replaceDocument(TENANT, baseId, candidate.id(), "doc-1", java.util.stream.IntStream
                .range(0, trechos.size())
                .mapToObj(i -> new KnowledgeVectorIndex.EmbeddedChunk(
                        new DocumentChunk("chunk-" + i, TENANT, "doc-1", i, trechos.get(i), 0, 0),
                        embeddings.embed(trechos.get(i))))
                .toList());
        assertThat(index.activate(TENANT, baseId, candidate.id())).isTrue();
        embeddings.embedded.clear();

        factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(), mock(DynamicWorkflowService.class),
                null, null, null);
        factory.useKnowledgeService(service);
    }

    private static Map<String, Object> node(String type, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "kb");
        node.put("type", type);
        node.put("componentId", type);
        node.put("config", config);
        return node;
    }

    private StepResult run(DefaultFlowStepFactory f, Map<String, Object> node, String tenant, Object input) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", "wf");
        json.put("metadata", Map.of("name", "KB"));
        json.put("steps", List.of(node));
        var flow = new DefaultWorkflowDeserializer(f).toFlow(json);
        ExecutionContext ctx = new DefaultExecutionContext(tenant, "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", input);
        return flow.getSteps().get(0).execute(ctx).join();
    }

    @Test
    @DisplayName("vector-search com base devolve os trechos da base, com o embedding da ingestão")
    void vectorSearchBuscaNaBase() {
        StepResult result = run(factory, node("vector-search", Map.of("knowledgeBaseId", baseId, "topK", 1)),
                TENANT, Map.of("input", "qual o prazo de reembolso?"));

        assertThat(result.getStatus()).as("%s", result.getOutput()).isEqualTo(StepStatus.COMPLETED);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.getOutput().orElseThrow();
        assertThat(hits).as("topK=1").hasSize(1);
        assertThat(hits.get(0)).containsKeys("chunkId", "documentId", "content", "score");
        assertThat(hits.get(0).get("documentId")).isEqualTo("doc-1");
        assertThat(embeddings.embedded)
                .as("a consulta é embutida pelo mesmo KnowledgeEmbeddingModel da ingestão")
                .containsExactly("qual o prazo de reembolso?");
    }

    @Test
    @DisplayName("a chave vectorStoreId dos templates já instalados também escolhe a base")
    void chaveDosTemplatesInstalados() {
        StepResult result = run(factory, node("vector-search", Map.of("vectorStoreId", baseId, "topK", "2")),
                TENANT, "horário do suporte");

        assertThat(result.getStatus()).as("%s", result.getOutput()).isEqualTo(StepStatus.COMPLETED);
        assertThat((List<?>) result.getOutput().orElseThrow()).hasSize(2);
    }

    @Test
    @DisplayName("base de outro tenant falha dizendo que não existe no tenant da execução")
    void baseDeOutroTenant() {
        StepResult result = run(factory, node("vector-search", Map.of("knowledgeBaseId", baseId)),
                "outro-tenant", "qual o prazo?");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString()
                .contains("'" + baseId + "' não existe no tenant 'outro-tenant'")
                .doesNotContain("component not found");
        assertThat(embeddings.embedded).as("nada é embutido para uma base que o tenant não tem").isEmpty();
    }

    @Test
    @DisplayName("base inexistente falha com mensagem clara")
    void baseInexistente() {
        StepResult result = run(factory, node("rag", Map.of("knowledgeBaseId", "kb-nao-existe",
                "llmProvider", CHAT_PROVIDER)), TENANT, "qual o prazo?");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains("'kb-nao-existe' não existe no tenant 'acme'");
    }

    @Test
    @DisplayName("rag com base monta o prompt com os trechos e responde pelo chat do nó")
    void ragResponde() {
        StepResult result = run(factory, node("rag", Map.of("knowledgeBaseId", baseId,
                        "llmProvider", CHAT_PROVIDER, "retrieverMaxResults", 1)),
                TENANT, "qual o prazo de reembolso?");

        assertThat(result.getStatus()).as("%s", result.getOutput()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput().orElseThrow()).asString()
                .startsWith("eco:generate:Based on the following context:")
                .contains("O prazo de reembolso é de 30 dias")
                .contains("Please answer the question: qual o prazo de reembolso?");
    }

    @Test
    @DisplayName("rag sem provider de LLM em nenhum nível falha na execução dizendo por quê")
    void ragSemLlm() {
        StepResult result = run(factory, node("rag", Map.of("knowledgeBaseId", baseId)), TENANT, "prazo?");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains("não escolheu provider");
    }

    @Test
    @DisplayName("sem o serviço de conhecimento no servidor, o nó com base não cai em outro caminho")
    void semServico() {
        DefaultFlowStepFactory semKnowledge = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null);

        StepResult result = run(semKnowledge, node("vector-search", Map.of("knowledgeBaseId", baseId)),
                TENANT, "prazo?");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains("bases de conhecimento não estão disponíveis");
    }

    @Test
    @DisplayName("fluxo restrito por allowedComponents precisa listar a base de conhecimento")
    void escopoDoFluxo() {
        Map<String, Object> json = new HashMap<>();
        json.put("id", "wf");
        json.put("metadata", Map.of("name", "KB"));
        json.put("configuration", Map.of("allowedComponents", List.of("outra-coisa")));
        json.put("steps", List.of(node("vector-search", Map.of("knowledgeBaseId", baseId))));
        var flow = new DefaultWorkflowDeserializer(factory).toFlow(json);
        ExecutionContext ctx = new DefaultExecutionContext(TENANT, "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", "prazo?");

        StepResult result = flow.getSteps().get(0).execute(ctx).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains(KnowledgeNodeComponent.COMPONENT_ID);
    }

    @Test
    @DisplayName("sem base escolhida, o nó segue para o adapter como antes")
    void semBaseSegueOAdapter() {
        StepResult result = run(factory, node("vector-search", Map.of("topK", 3)), TENANT, "prazo?");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString()
                .contains("component not found: vector-search")
                .contains("não escolheu o backend")
                .contains("Base de conhecimento");
    }
}
