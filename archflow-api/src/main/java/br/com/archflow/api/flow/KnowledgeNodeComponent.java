package br.com.archflow.api.flow;

import br.com.archflow.api.flow.adapter.LangChainAdapterComponent;
import br.com.archflow.api.knowledge.KnowledgeService;
import br.com.archflow.api.knowledge.KnowledgeVectorIndex;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Os nós {@code vector-search} e {@code rag} com uma <b>Base de conhecimento</b> escolhida: a busca é a
 * da tela de Conhecimento ({@link KnowledgeService#search}), no tenant da execução (RCHFLW-55).
 *
 * <p><b>Por que não o adapter.</b> O nó ia ao {@code PgVectorStoreAdapter}, que lê a tabela
 * {@code embeddings} — que a ingestão do Conhecimento nunca preenche (ela grava em
 * {@code knowledge_embeddings}, com tenant, base, versão de índice e o embedding dela). O resultado vinha
 * vazio, sem erro, e os documentos enviados só serviam para a busca de teste da própria tela. Passar pelo
 * {@link KnowledgeService} dá ao fluxo o mesmo que a tela: a versão de índice ativa, o mesmo modelo de
 * embedding da ingestão (o bean {@code KnowledgeEmbeddingModel} que o serviço usa nos dois sentidos) e o
 * isolamento por tenant — a base é procurada no tenant da execução, nunca no do documento.</p>
 *
 * <p><b>Base inexistente ou de outro tenant</b> falha a execução dizendo isso; o serviço devolve
 * {@code null} nos dois casos, e o nó não distingue um do outro de propósito (dizer "existe, mas é de
 * outro tenant" vazaria a existência).</p>
 *
 * <p><b>Saída.</b> A busca devolve os trechos ({@code chunkId}, {@code documentId}, {@code content},
 * {@code score}), como a tela. O RAG monta o prompt com eles — o mesmo texto do {@code RagChainAdapter} —
 * e o manda ao chat do nó ({@code llmProvider}, ou o herdado do fluxo/plataforma), que passa pela mesma
 * ponte governada dos nós de chat (chave do tenant, destino, modelos permitidos, ZDR); devolve o texto da
 * resposta.</p>
 *
 * <p><b>Padrões.</b> Sem {@code topK}/{@code retrieverMaxResults} o nó traz {@value #DEFAULT_LIMIT}
 * trechos e sem score mínimo aceita qualquer um ({@value #DEFAULT_MIN_SCORE}) — os mesmos da busca da
 * tela ({@code SpringKnowledgeController}). O serviço limita o pedido a 1–50.</p>
 */
public final class KnowledgeNodeComponent implements AIComponent {

    // Os identificadores moram em KnowledgeNodes (archflow-flow-runtime, RCHFLW-37): a fábrica comum reconhece
    // o nó sem depender do KnowledgeService. Estes nomes ficam para quem já os usa.

    /** Id para {@code configuration.allowedComponents}: um fluxo restrito precisa listá-lo. */
    public static final String COMPONENT_ID = KnowledgeNodes.COMPONENT_ID;

    /** Os tipos de nó que aceitam uma base (o painel mostra o seletor nos mesmos). */
    public static final Set<String> NODE_TYPES = KnowledgeNodes.NODE_TYPES;

    /** A chave que o painel grava. */
    public static final String BASE_KEY = KnowledgeNodes.BASE_KEY;

    /**
     * A chave que os templates do backend gravavam ({@code CustomerSupport}, {@code KnowledgeBase}) e que
     * nenhum código lia. Os templates agora gravam {@link #BASE_KEY}; esta continua lida para os fluxos
     * já instalados com ela — o documento salvo não muda.
     */
    public static final String LEGACY_BASE_KEY = KnowledgeNodes.LEGACY_BASE_KEY;

    static final int DEFAULT_LIMIT = 5;
    static final double DEFAULT_MIN_SCORE = 0.0;

    /** O mesmo enquadramento do {@code RagChainAdapter}, para o RAG responder igual nos dois caminhos. */
    static final String RAG_PROMPT = """
            Based on the following context:
            ---
            %s
            ---
            Please answer the question: %s
            """;

    enum Mode { SEARCH, RAG }

    private final String nodeId;
    private final Mode mode;
    private final String baseId;
    private final Object limit;
    private final Object minScore;
    private final Supplier<KnowledgeService> knowledge;
    private final AIComponent generator;
    private final String generatorProblem;

    /**
     * @param generator        o chat do RAG; {@code null} na busca
     * @param generatorProblem por que o RAG não tem chat (sem provider, provider sem adapter, fora do
     *                         escopo do fluxo) — dito na execução, não ao abrir o fluxo
     */
    KnowledgeNodeComponent(String nodeId, Mode mode, Map<String, Object> config,
                           Supplier<KnowledgeService> knowledge, AIComponent generator, String generatorProblem) {
        this.nodeId = nodeId;
        this.mode = mode;
        this.baseId = baseIdOf(config);
        this.limit = mode == Mode.RAG ? config.get("retrieverMaxResults") : config.get("topK");
        this.minScore = mode == Mode.RAG ? config.get("retrieverMinScore") : config.get("minScore");
        this.knowledge = knowledge;
        this.generator = generator;
        this.generatorProblem = generatorProblem;
    }

    /** A base que o nó escolheu ({@link #BASE_KEY}, senão {@link #LEGACY_BASE_KEY}), ou {@code null}. */
    static String baseIdOf(Map<String, Object> config) {
        return KnowledgeNodes.baseIdOf(config);
    }

    @Override
    public void initialize(Map<String, Object> config) {
        // A configuração é do nó e chega pelo construtor.
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(COMPONENT_ID, "Base de conhecimento",
                "Busca numa base de conhecimento do tenant" + (mode == Mode.RAG ? " e responde com o LLM" : ""),
                ComponentType.TOOL, "1.0.0", Set.of("knowledge"), List.of(), Map.of(),
                Set.of("knowledge", mode == Mode.RAG ? "rag" : "vector-search"));
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        KnowledgeService service = knowledge == null ? null : knowledge.get();
        if (service == null) {
            throw new IllegalStateException("Nó " + nodeId + ": as bases de conhecimento não estão disponíveis "
                    + "neste servidor, então a base '" + baseId + "' não pode ser consultada.");
        }
        String tenantId = context == null ? null : context.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("Nó " + nodeId + ": a execução não tem tenant, e a base de "
                    + "conhecimento é procurada no tenant da execução.");
        }
        Object text = LangChainAdapterComponent.messageText(input);
        if (!(text instanceof String query) || query.isBlank()) {
            throw new IllegalArgumentException("Nó " + nodeId + ": não recebeu texto para buscar na base de "
                    + "conhecimento '" + baseId + "'.");
        }
        List<KnowledgeVectorIndex.SearchHit> hits = service.search(tenantId, baseId, query,
                integer(limit, "topK"), decimal(minScore));
        if (hits == null) {
            throw new IllegalStateException("Nó " + nodeId + ": a base de conhecimento '" + baseId
                    + "' não existe no tenant '" + tenantId + "'. Escolha outra base no nó.");
        }
        if (mode == Mode.SEARCH) {
            return hits.stream().map(KnowledgeNodeComponent::asMap).toList();
        }
        if (generator == null) {
            throw new IllegalStateException("Nó " + nodeId + " (rag): " + generatorProblem);
        }
        String contexto = hits.stream().map(KnowledgeVectorIndex.SearchHit::content)
                .collect(Collectors.joining("\n"));
        return generator.execute("generate", RAG_PROMPT.formatted(contexto, query), context);
    }

    private static Map<String, Object> asMap(KnowledgeVectorIndex.SearchHit hit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunkId", hit.chunkId());
        out.put("documentId", hit.documentId());
        out.put("content", hit.content());
        out.put("score", hit.score());
        return out;
    }

    /** Número do nó — o painel grava número; um template pode gravar o texto "5". */
    private int integer(Object value, String campo) {
        Double number = number(value, mode == Mode.RAG ? "retrieverMaxResults" : campo);
        return number == null ? DEFAULT_LIMIT : number.intValue();
    }

    private double decimal(Object value) {
        Double number = number(value, mode == Mode.RAG ? "retrieverMinScore" : "minScore");
        return number == null ? DEFAULT_MIN_SCORE : number;
    }

    private Double number(Object value, String campo) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Nó " + nodeId + ": '" + campo + "' deve ser um número, veio \""
                    + value + "\".");
        }
    }

    @Override
    public void shutdown() {
        if (generator != null) {
            generator.shutdown();
        }
    }
}
