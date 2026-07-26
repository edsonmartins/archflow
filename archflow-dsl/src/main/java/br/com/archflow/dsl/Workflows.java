package br.com.archflow.dsl;

/**
 * Ponto de entrada da DSL: escrever em Java um workflow que hoje se escreve em
 * JSON à mão ou arrastando no designer.
 *
 * <pre>{@code
 * WorkflowDocument doc = Workflows.define("rag-doc-qa")
 *         .named("Document Q&A")
 *         .describedAs("Responde citando a base de conhecimento")
 *         .allowing("embedding", "vector-search", "openai")
 *         .step("s1", Nodes.component("input"))
 *         .step("s2", Nodes.embedding("openai").with("model", "text-embedding-3-small"))
 *         .step("s3", Nodes.vectorSearch("pgvector").with("topK", 6))
 *         .step("s4", Nodes.llmChat("openai").with("model", "gpt-4o"))
 *         .edge("s1", "s2")
 *         .edge("s2", "s3")
 *         .edge("s3", "s4")
 *         .build();
 *
 * doc.toYaml();   // mesmo texto que GET /api/workflows/{id}/yaml devolve
 * doc.toJson();   // documento canônico que o motor desserializa
 * }</pre>
 *
 * <h2>Por que {@code Workflows} e não {@code Workflow}</h2>
 * {@code br.com.archflow.model.Workflow} já existe e é outra coisa — uma
 * abstração de template. Um segundo tipo com o mesmo nome simples obrigaria
 * quem usa os dois a qualificar um deles por extenso.
 *
 * <h2>O que a DSL garante e o que ela não garante</h2>
 * Ela é tipada onde o motor tem um contrato fechado: os {@link
 * br.com.archflow.model.flow.StepType tipos de passo}, a forma das arestas e
 * os tipos de nó servidos por adapter (ver {@link Nodes}). Ela é aberta a
 * string no {@code componentId} porque <b>o catálogo de componentes é
 * populado em runtime</b>, por SPI e por jars de plugin: não existe lista
 * fechada em tempo de compilação, e fingir que existe produziria uma API que
 * envelhece em silêncio quando um plugin some.
 *
 * <p>O que ela consegue pegar antes de rodar está em {@link WorkflowBuilder#build()}.
 *
 * @since 1.1.0
 */
public final class Workflows {

    private Workflows() {
    }

    /**
     * Começa a definição de um workflow.
     *
     * @param id identificador do fluxo; é a chave com que o motor o carrega,
     *           então não pode ser vazio
     */
    public static WorkflowBuilder define(String id) {
        return new WorkflowBuilder(id);
    }
}
