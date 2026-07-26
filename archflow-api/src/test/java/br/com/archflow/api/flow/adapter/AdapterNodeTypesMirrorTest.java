package br.com.archflow.api.flow.adapter;

import br.com.archflow.dsl.Nodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As constantes de tipo de nó da DSL espelham as que o motor reconhece.
 *
 * <p>{@link Nodes} declara os tipos como constantes próprias em vez de importar
 * {@link AdapterNodeTypes}: o archflow-dsl depende só do archflow-model, e
 * importar daqui traria o archflow-api — Spring inteiro — para dentro de uma
 * biblioteca cujo propósito é gerar um arquivo.
 *
 * <p>O preço de espelhar é a divergência silenciosa. Um tipo acrescentado ao
 * mapa do motor e esquecido na DSL não quebra nada: o método simplesmente não
 * existe, e quem usa a DSL escreve o tipo à mão sem saber que havia um atalho.
 * Pior é o contrário — um tipo removido do motor continuaria oferecido pela
 * DSL, produzindo nós que caem no catálogo em vez de no adapter, com o fluxo
 * falhando em runtime. Este teste é o que torna as duas direções barulhentas.
 *
 * <p>Ele vive no archflow-api porque é o único lado que enxerga os dois.
 */
@DisplayName("Nodes da DSL espelha AdapterNodeTypes")
class AdapterNodeTypesMirrorTest {

    /** Tudo que a DSL oferece como tipo de nó de adapter. */
    private static final List<String> DSL_TYPES = List.of(
            Nodes.LLM_CHAT, Nodes.LLM_STREAMING, Nodes.CHAT, Nodes.EMBEDDING,
            Nodes.MEMORY, Nodes.VECTOR_STORE, Nodes.VECTOR_SEARCH, Nodes.VECTORSTORE,
            Nodes.RAG);

    @Test
    @DisplayName("todo tipo oferecido pela DSL é reconhecido pelo motor")
    void everyDslTypeIsKnownToTheEngine() {
        List<String> desconhecidos = DSL_TYPES.stream()
                .filter(type -> AdapterNodeTypes.adapterTypeFor(type) == null)
                .toList();

        assertThat(desconhecidos)
                .as("a DSL ofereceria um nó que o motor resolveria pelo catálogo, não pelo adapter")
                .isEmpty();
    }

    /**
     * A direção inversa. Não dá para ler o mapa privado do {@link AdapterNodeTypes},
     * então a lista esperada é escrita aqui — e é justamente por isso que ela
     * serve: alterar o mapa do motor sem tocar nesta lista quebra o teste, que
     * é o alarme desejado.
     */
    @Test
    @DisplayName("todo tipo do motor tem cobertura na DSL")
    void everyEngineTypeIsOfferedByTheDsl() {
        Set<String> doMotor = Set.of(
                "llm-chat", "llm-streaming", "chat", "embedding", "memory",
                "vector-store", "vector-search", "vectorstore", "rag");

        // Guarda da própria lista: se um destes sumir do motor, o espelho está
        // desatualizado e o teste precisa ser revisto, não a DSL.
        assertThat(doMotor.stream().filter(t -> AdapterNodeTypes.adapterTypeFor(t) == null).toList())
                .as("esta lista saiu de sincronia com AdapterNodeTypes")
                .isEmpty();

        assertThat(Set.copyOf(DSL_TYPES))
                .as("tipo servido por adapter sem atalho na DSL")
                .isEqualTo(doMotor);
    }

    @Test
    @DisplayName("os atalhos produzem o tipo de adapter esperado")
    void shortcutsMapToTheRightAdapterType() {
        assertThat(DSL_TYPES.stream()
                .collect(Collectors.toMap(t -> t, AdapterNodeTypes::adapterTypeFor)))
                .containsEntry("llm-chat", "chat")
                .containsEntry("llm-streaming", "chat")
                .containsEntry("embedding", "embedding")
                .containsEntry("vector-search", "vectorstore")
                .containsEntry("memory", "memory")
                .containsEntry("rag", "chain");
    }
}
