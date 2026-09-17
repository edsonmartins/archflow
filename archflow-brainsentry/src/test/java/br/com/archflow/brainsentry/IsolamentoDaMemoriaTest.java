package br.com.archflow.brainsentry;

import br.com.archflow.conversation.memory.Episode;
import br.com.archflow.conversation.memory.ScoredEpisode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A memória de um tenant não chega a outro — nem a outra conversa.
 *
 * <p>Pré-requisito da ADR-0005 (fase 0). Memória é conteúdo de terceiros que persiste e volta ao
 * prompt em outras execuções; um recall que atravessa tenants entrega a informação de um cliente
 * na conversa de outro, e isso não produz erro nenhum.</p>
 *
 * <p>Os testes afirmam resultado <b>não vazio</b> antes de afirmar o escopo: um recall que devolve
 * sempre vazio passaria em "não veio o outro tenant" sem nada ter sido filtrado.</p>
 */
@DisplayName("BrainSentryMemoryAdapter — isolamento")
class IsolamentoDaMemoriaTest {

    private static Memory memoria(String id, String... tags) {
        return new Memory(id, "conteúdo " + id, null, "CONTEXT", "IMPORTANT", "EPISODIC",
                tags == null ? null : List.of(tags), Map.of(), Instant.parse("2026-09-10T12:00:00Z"));
    }

    @Nested
    @DisplayName("tags")
    class Tags {

        private final BrainSentryClient client = mock(BrainSentryClient.class);
        private final BrainSentryMemoryAdapter adapter = new BrainSentryMemoryAdapter(client);

        /** O defeito mais grave: "no tags = no filtering possible" devolvia a memória a todos. */
        @Test
        @DisplayName("memória sem tags não volta para ninguém")
        void semTags() throws Exception {
            when(client.searchMemories(anyString(), anyInt(), anyList())).thenReturn(List.of(
                    memoria("certa", "tenant:acme", "context:cli-7"),
                    memoria("sem-tags", (String[]) null),
                    memoria("tags-vazias")));

            List<ScoredEpisode> r = adapter.recall("acme", "caixa", "cli-7", 10);

            assertThat(r).extracting(s -> s.episode().id()).containsExactly("certa");
        }

        @Test
        @DisplayName("o escopo vai ao servidor como tags, e a consulta vai limpa")
        void recorteNoServidor() throws Exception {
            when(client.searchMemories(anyString(), anyInt(), anyList()))
                    .thenReturn(List.of(memoria("m", "tenant:acme", "context:cli-7")));

            adapter.recall("acme", "prefere caixa?", "cli-7", 3);

            verify(client).searchMemories("prefere caixa?", 3, List.of("tenant:acme", "context:cli-7"));
        }

        /**
         * Mesmo que o servidor devolva além do recorte (versão antiga, sem o filtro por tag), a
         * conferência local segura: outro tenant e outra conversa ficam de fora.
         */
        @Test
        @DisplayName("outro tenant e outra conversa são descartados")
        void outroTenantOutraConversa() throws Exception {
            when(client.searchMemories(anyString(), anyInt(), anyList())).thenReturn(List.of(
                    memoria("certa", "tenant:acme", "context:cli-7"),
                    memoria("outro-tenant", "tenant:beta", "context:cli-7"),
                    memoria("outra-conversa", "tenant:acme", "context:cli-8")));

            List<ScoredEpisode> r = adapter.recall("acme", "caixa", "cli-7", 10);

            assertThat(r).extracting(s -> s.episode().id()).containsExactly("certa");
        }

        /** Antes, o contexto pedido era carimbado em cada resultado, fosse de onde fosse. */
        @Test
        @DisplayName("o episódio devolvido traz o tenant e o contexto da memória")
        void rotuloReal() throws Exception {
            when(client.searchMemories(anyString(), anyInt(), anyList()))
                    .thenReturn(List.of(memoria("m", "tenant:acme", "context:cli-7")));

            Episode e = adapter.recall("acme", "caixa", "cli-7", 10).get(0).episode();

            assertThat(e.tenantId()).as("antes vinha 'SYSTEM'").isEqualTo("acme");
            assertThat(e.contextId()).isEqualTo("cli-7");
        }

        @Test
        @DisplayName("sem contexto pedido, o recorte é só o tenant")
        void semContexto() throws Exception {
            when(client.searchMemories(anyString(), anyInt(), anyList()))
                    .thenReturn(List.of(memoria("m", "tenant:acme", "context:cli-9")));

            List<ScoredEpisode> r = adapter.recall("acme", "caixa", "", 10);

            verify(client).searchMemories("caixa", 10, List.of("tenant:acme"));
            assertThat(r).singleElement()
                    .satisfies(s -> assertThat(s.episode().contextId()).isEqualTo("cli-9"));
        }

        @Test
        @DisplayName("a gravação leva origem, tenant e contexto")
        void gravacao() throws Exception {
            adapter.store("acme", Episode.of("cli-7", "prefere caixa fechada", 0.5));

            verify(client).createMemory(eq("prefere caixa fechada"), anyString(), anyString(),
                    eq("EPISODIC"),
                    eq(List.of("archflow", "tenant:acme", "context:cli-7")));
        }

        @Test
        @DisplayName("tenant vazio vira o padrão, não 'tenant:'")
        void tenantVazio() throws Exception {
            adapter.store("  ", Episode.of("cli-7", "x", 0.5));

            verify(client).createMemory(anyString(), anyString(), anyString(), anyString(),
                    argThat(tags -> tags.contains("tenant:SYSTEM") && !tags.contains("tenant:  ")));
        }
    }

    @Nested
    @DisplayName("leitura por id")
    class PorId {

        private final BrainSentryClient client = mock(BrainSentryClient.class);
        private final BrainSentryMemoryAdapter adapter = new BrainSentryMemoryAdapter(client);

        @Test
        @DisplayName("devolve ao tenant dono e a mais ninguém")
        void soAoDono() throws Exception {
            when(client.getMemory("m-1")).thenReturn(Optional.of(memoria("m-1", "tenant:acme", "context:cli-7")));

            assertThat(adapter.getById("acme", "m-1")).isPresent();
            assertThat(adapter.getById("beta", "m-1")).isEmpty();
            assertThat(adapter.getById("m-1"))
                    .as("sem tenant, vale o padrão — e a memória não é dele")
                    .isEmpty();
        }
    }

    /**
     * Chave por tenant: o isolamento é do servidor, e um tenant sem chave não tem memória.
     */
    @Nested
    @DisplayName("chave por tenant")
    class ChavePorTenant {

        private final BrainSentryClient acme = mock(BrainSentryClient.class);
        private final BrainSentryClient beta = mock(BrainSentryClient.class);
        private final BrainSentryMemoryAdapter adapter = new BrainSentryMemoryAdapter(tenant ->
                switch (tenant) {
                    case "acme" -> Optional.of(acme);
                    case "beta" -> Optional.of(beta);
                    default -> Optional.empty();
                });

        @Test
        @DisplayName("cada tenant fala só com o próprio client")
        void cadaUmComOSeu() throws Exception {
            when(acme.searchMemories(anyString(), anyInt(), anyList()))
                    .thenReturn(List.of(memoria("m", "tenant:acme", "context:cli-7")));

            assertThat(adapter.recall("acme", "caixa", "cli-7", 5)).hasSize(1);
            adapter.store("beta", Episode.of("cli-1", "x", 0.5));

            verify(acme, never()).createMemory(any(), any(), any(), any(), any());
            verify(beta, never()).searchMemories(anyString(), anyInt(), anyList());
            verify(beta).createMemory(anyString(), anyString(), anyString(), anyString(),
                    argThat(tags -> tags.contains("tenant:beta")));
        }

        /** Sem chave, cair num client "padrão" gravaria a memória no tenant errado. */
        @Test
        @DisplayName("tenant sem chave não grava nem lê, e o descarte é contado")
        void semChave() {
            adapter.store("gama", Episode.of("cli-1", "x", 0.5));

            assertThat(adapter.recall("gama", "caixa", "cli-1", 5)).isEmpty();
            assertThat(adapter.getByContext("gama", "cli-1")).isEmpty();
            assertThat(adapter.getById("gama", "m-1")).isEmpty();
            assertThat(adapter.gravacoesSemChave()).isEqualTo(1);
            verifyNoInteractions(acme, beta);
        }

        @Test
        @DisplayName("falha ao resolver a chave não derruba quem chamou")
        void resolverQueFalha() {
            BrainSentryMemoryAdapter quebrado = new BrainSentryMemoryAdapter(tenant -> {
                throw new IllegalStateException("cofre fora do ar");
            });

            assertThat(quebrado.recall("acme", "caixa", "cli-7", 5)).isEmpty();
            quebrado.store("acme", Episode.of("cli-7", "x", 0.5));
            assertThat(quebrado.gravacoesSemChave()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("falha de gravação é tolerada, mas contada")
    void falhaDeGravacaoContada() throws Exception {
        BrainSentryClient client = mock(BrainSentryClient.class);
        when(client.createMemory(any(), any(), any(), any(), any()))
                .thenThrow(new java.io.IOException("Brain Sentry fora"));
        BrainSentryMemoryAdapter adapter = new BrainSentryMemoryAdapter(client);

        adapter.store("acme", Episode.of("cli-7", "x", 0.5));

        assertThat(adapter.falhasDeGravacao()).isEqualTo(1);
    }
}
