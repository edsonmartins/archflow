package br.com.archflow.brainsentry;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolResult;
import br.com.archflow.conversation.memory.Episode;
import br.com.archflow.conversation.memory.ScoredEpisode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Supplemental tests covering gaps not addressed in the original
 * {@code BrainSentryInterceptorTest} and {@code BrainSentryMemoryAdapterTest}:
 *
 * <h3>BrainSentryInterceptor</h3>
 * <ul>
 *   <li>beforeExecute enriches input when client returns enhanced prompt</li>
 *   <li>beforeExecute does NOT enrich when enhanced=false</li>
 *   <li>afterExecute captures result as memory when captureResults=true and result is success</li>
 *   <li>afterExecute skips capture when result is not success</li>
 *   <li>afterExecute skips capture when result is null</li>
 *   <li>afterExecute handles memory-creation failure gracefully</li>
 *   <li>truncation of long result data</li>
 * </ul>
 *
 * <h3>BrainSentryMemoryAdapter</h3>
 * <ul>
 *   <li>getByContext(contextId) delegates with "SYSTEM" tenant</li>
 *   <li>getByContext(tenantId, contextId) filters by tenant tag</li>
 *   <li>recall(tenantId, query, contextId, maxResults) overload</li>
 *   <li>clear() does not throw</li>
 *   <li>store(tenantId, episode) maps importance=0.5 to IMPORTANT</li>
 *   <li>store handles client error gracefully</li>
 *   <li>getById returns empty when client throws</li>
 *   <li>recall filters out memories lacking tenant tag</li>
 * </ul>
 */
@DisplayName("BrainSentry — supplemental coverage")
class BrainSentrySupplementalTest {

    // =========================================================================
    // BrainSentryInterceptor — additional paths
    // =========================================================================

    @Nested
    @DisplayName("BrainSentryInterceptor — enrichment paths")
    class InterceptorEnrichment {

        private BrainSentryClient client;
        private ToolContext context;

        @BeforeEach
        void setUp() {
            client = mock(BrainSentryClient.class);
            context = mock(ToolContext.class);
        }

        @Test
        @DisplayName("beforeExecute enriches input when client returns enhanced prompt")
        void enrichesInputWhenEnhanced() throws Exception {
            var enriched = new EnrichedPrompt(true, "original", "injected context", List.of("m1"), 50);
            when(client.intercept("original", 2000)).thenReturn(enriched);
            when(context.getInput()).thenReturn("original");

            var interceptor = new BrainSentryInterceptor(client);
            interceptor.beforeExecute(context);

            // The input should be replaced with the full (enriched) prompt
            verify(context).setInput("injected context\n\noriginal");
            verify(context).setAttribute(eq("brainsentry.enriched"), eq(enriched));
        }

        @Test
        @DisplayName("beforeExecute does NOT modify input when enhanced=false")
        void doesNotModifyInputWhenNotEnhanced() throws Exception {
            var notEnhanced = new EnrichedPrompt(false, "original", null, List.of(), 0);
            when(client.intercept("original", 2000)).thenReturn(notEnhanced);
            when(context.getInput()).thenReturn("original");

            var interceptor = new BrainSentryInterceptor(client);
            interceptor.beforeExecute(context);

            verify(context, never()).setInput(any());
            verify(context, never()).setAttribute(anyString(), any());
        }

        @Test
        @DisplayName("afterExecute captures memory when captureResults=true and result is success")
        void capturesMemoryOnSuccess() throws Exception {
            var result = mock(ToolResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getData()).thenReturn(Optional.of("some output"));
            when(context.getToolName()).thenReturn("myTool");

            var interceptor = new BrainSentryInterceptor(client, true);
            var returned = interceptor.afterExecute(context, result);

            assertThat(returned).isSameAs(result);
            verify(client).createMemory(
                    contains("myTool"),
                    eq("ACTION"),
                    eq("MINOR"),
                    eq("EPISODIC"),
                    argThat(tags -> tags.contains("tool-result") && tags.contains("myTool"))
            );
        }

        @Test
        @DisplayName("afterExecute does NOT capture memory when result is not success")
        void doesNotCaptureWhenNotSuccess() throws Exception {
            var result = mock(ToolResult.class);
            when(result.isSuccess()).thenReturn(false);
            when(context.getToolName()).thenReturn("myTool");

            var interceptor = new BrainSentryInterceptor(client, true);
            interceptor.afterExecute(context, result);

            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("afterExecute does NOT capture memory when result is null")
        void doesNotCaptureWhenResultNull() throws Exception {
            var interceptor = new BrainSentryInterceptor(client, true);
            var returned = interceptor.afterExecute(context, null);

            assertThat(returned).isNull();
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("afterExecute handles createMemory failure gracefully")
        void handlesCreateMemoryFailure() throws Exception {
            var result = mock(ToolResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getData()).thenReturn(Optional.of("output"));
            when(context.getToolName()).thenReturn("failTool");
            when(client.createMemory(anyString(), anyString(), anyString(), anyString(), anyList()))
                    .thenThrow(new RuntimeException("Storage failure"));

            var interceptor = new BrainSentryInterceptor(client, true);

            // Should not throw — failure must be swallowed
            assertThatCode(() -> interceptor.afterExecute(context, result))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("afterExecute truncates data longer than 500 characters")
        void truncatesLongData() throws Exception {
            var result = mock(ToolResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getData()).thenReturn(Optional.of("x".repeat(600))); // 600 chars > 500
            when(context.getToolName()).thenReturn("bigTool");

            var interceptor = new BrainSentryInterceptor(client, true);
            interceptor.afterExecute(context, result);

            // Captured content must be truncated (ends with "...")
            verify(client).createMemory(
                    argThat(content -> content.endsWith("...")),
                    anyString(), anyString(), anyString(), anyList()
            );
        }

        @Test
        @DisplayName("afterExecute does NOT truncate data shorter than or equal to 500 characters")
        void doesNotTruncateShortData() throws Exception {
            var result = mock(ToolResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(result.getData()).thenReturn(Optional.of("short output"));
            when(context.getToolName()).thenReturn("smallTool");

            var interceptor = new BrainSentryInterceptor(client, true);
            interceptor.afterExecute(context, result);

            verify(client).createMemory(
                    argThat(content -> !content.endsWith("...")),
                    anyString(), anyString(), anyString(), anyList()
            );
        }
    }

    // =========================================================================
    // BrainSentryMemoryAdapter — additional paths
    // =========================================================================

    @Nested
    @DisplayName("BrainSentryMemoryAdapter — additional paths")
    class MemoryAdapterAdditional {

        private BrainSentryClient client;
        private BrainSentryMemoryAdapter adapter;

        @BeforeEach
        void setUp() {
            client = mock(BrainSentryClient.class);
            adapter = new BrainSentryMemoryAdapter(client);
        }

        // --- getByContext ---

        @Test
        @DisplayName("getByContext(contextId) delegates with SYSTEM tenant")
        void getByContextSingleArgDelegatesToSystem() throws Exception {
            var memory = memoryWithTags("m1", "SYSTEM");
            when(client.searchMemories("tenant:SYSTEM context:ctx-1", 50))
                    .thenReturn(List.of(memory));

            List<Episode> episodes = adapter.getByContext("ctx-1");

            assertThat(episodes).hasSize(1);
            assertThat(episodes.get(0).content()).isEqualTo("Memory content");
        }

        @Test
        @DisplayName("getByContext(tenantId, contextId) filters out memories for other tenants")
        void getByContextFiltersOtherTenants() throws Exception {
            var mine = memoryWithTags("m1", "tenant-a");
            var other = memoryWithTags("m2", "tenant-b");
            when(client.searchMemories(contains("tenant:tenant-a"), anyInt()))
                    .thenReturn(List.of(mine, other));

            List<Episode> episodes = adapter.getByContext("tenant-a", "ctx-1");

            // Only the memory tagged "tenant:tenant-a" should pass the filter
            assertThat(episodes).hasSize(1);
            assertThat(episodes.get(0).id()).isEqualTo("m1");
        }

        @Test
        @DisplayName("getByContext returns empty list on client error")
        void getByContextHandlesError() throws Exception {
            when(client.searchMemories(anyString(), anyInt())).thenThrow(new RuntimeException("Oops"));

            List<Episode> episodes = adapter.getByContext("ctx");

            assertThat(episodes).isEmpty();
        }

        @Test
        @DisplayName("getByContext results are sorted by timestamp descending")
        void getByContextSortedByTimestamp() throws Exception {
            var older = new Memory("m-old", "Older", null, "KNOWLEDGE", "MINOR", "EPISODIC",
                    List.of("tenant:SYSTEM"), Map.of(),
                    Instant.parse("2024-01-01T00:00:00Z"));
            var newer = new Memory("m-new", "Newer", null, "KNOWLEDGE", "MINOR", "EPISODIC",
                    List.of("tenant:SYSTEM"), Map.of(),
                    Instant.parse("2025-01-01T00:00:00Z"));

            when(client.searchMemories(anyString(), anyInt())).thenReturn(List.of(older, newer));

            List<Episode> episodes = adapter.getByContext("ctx");

            // Newer should come first
            assertThat(episodes.get(0).id()).isEqualTo("m-new");
            assertThat(episodes.get(1).id()).isEqualTo("m-old");
        }

        // --- recall (two-arg overload) ---

        @Test
        @DisplayName("recall(query, contextId, maxResults) delegates to SYSTEM tenant")
        void recallSingleTenantDelegatesToSystem() throws Exception {
            var memory = memoryWithTags("m1", "SYSTEM");
            when(client.searchMemories("tenant:SYSTEM auth query", 3)).thenReturn(List.of(memory));

            List<ScoredEpisode> results = adapter.recall("auth query", "ctx", 3);

            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("recall(tenantId, query, contextId, maxResults) prefixes query with tenant")
        void recallWithTenantPrefixesQuery() throws Exception {
            var memory = memoryWithTags("m1", "tenant-x");
            when(client.searchMemories("tenant:tenant-x search term", 5)).thenReturn(List.of(memory));

            List<ScoredEpisode> results = adapter.recall("tenant-x", "search term", "ctx", 5);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).episode().content()).isEqualTo("Memory content");
        }

        @Test
        @DisplayName("recall filters out memories that lack the expected tenant tag")
        void recallFiltersWrongTenantMemories() throws Exception {
            var correctTenant = memoryWithTags("m1", "t1");
            var wrongTenant = memoryWithTags("m2", "t2");
            when(client.searchMemories(anyString(), anyInt())).thenReturn(List.of(correctTenant, wrongTenant));

            List<ScoredEpisode> results = adapter.recall("t1", "query", "ctx", 10);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).episode().id()).isEqualTo("m1");
        }

        @Test
        @DisplayName("recall returns empty list on client error")
        void recallHandlesError() throws Exception {
            when(client.searchMemories(anyString(), anyInt())).thenThrow(new RuntimeException("err"));

            List<ScoredEpisode> results = adapter.recall("tenant-x", "query", "ctx", 5);

            assertThat(results).isEmpty();
        }

        // --- score mapping ---

        @Test
        @DisplayName("CRITICAL importance maps to score 0.9")
        void criticalImportanceMapsToHighScore() throws Exception {
            var memory = new Memory("m1", "Content", null, "KNOWLEDGE", "CRITICAL", "SEMANTIC",
                    List.of("tenant:SYSTEM"), Map.of(), Instant.now());
            when(client.searchMemories(anyString(), anyInt())).thenReturn(List.of(memory));

            List<ScoredEpisode> results = adapter.recall("q", "ctx", 5);

            assertThat(results.get(0).score()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("IMPORTANT importance maps to score 0.6")
        void importantImportanceMapsToMediumScore() throws Exception {
            var memory = new Memory("m1", "Content", null, "KNOWLEDGE", "IMPORTANT", "SEMANTIC",
                    List.of("tenant:SYSTEM"), Map.of(), Instant.now());
            when(client.searchMemories(anyString(), anyInt())).thenReturn(List.of(memory));

            List<ScoredEpisode> results = adapter.recall("q", "ctx", 5);

            assertThat(results.get(0).score()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("MINOR importance maps to score 0.3")
        void minorImportanceMapsToLowScore() throws Exception {
            var memory = new Memory("m1", "Content", null, "KNOWLEDGE", "MINOR", "SEMANTIC",
                    List.of("tenant:SYSTEM"), Map.of(), Instant.now());
            when(client.searchMemories(anyString(), anyInt())).thenReturn(List.of(memory));

            List<ScoredEpisode> results = adapter.recall("q", "ctx", 5);

            assertThat(results.get(0).score()).isEqualTo(0.3);
        }

        @Test
        @DisplayName("null importance maps to score 0.5")
        void nullImportanceMapsToDefaultScore() throws Exception {
            var memory = new Memory("m1", "Content", null, "KNOWLEDGE", null, "SEMANTIC",
                    List.of("tenant:SYSTEM"), Map.of(), Instant.now());
            when(client.searchMemories(anyString(), anyInt())).thenReturn(List.of(memory));

            List<ScoredEpisode> results = adapter.recall("q", "ctx", 5);

            assertThat(results.get(0).score()).isEqualTo(0.5);
        }

        // --- store ---

        @Test
        @DisplayName("store(episode) uses SYSTEM as default tenantId")
        void storeSingleArgUsesSystemTenant() throws Exception {
            var episode = Episode.of("ctx", "Event content", 0.5);

            adapter.store(episode);

            verify(client).createMemory(eq("Event content"), anyString(), anyString(),
                    eq("EPISODIC"),
                    argThat(tags -> tags.contains("tenant:SYSTEM")));
        }

        @Test
        @DisplayName("store(tenantId, episode) tags memory with provided tenant")
        void storeTwoArgTagsWithTenant() throws Exception {
            var episode = Episode.of("ctx", "Event for acme", 0.8);

            adapter.store("acme-corp", episode);

            verify(client).createMemory(eq("Event for acme"), anyString(), eq("CRITICAL"),
                    eq("EPISODIC"),
                    argThat(tags -> tags.contains("tenant:acme-corp")));
        }

        @Test
        @DisplayName("store handles client error gracefully — no exception thrown")
        void storeHandlesClientError() throws Exception {
            when(client.createMemory(anyString(), anyString(), anyString(), anyString(), anyList()))
                    .thenThrow(new RuntimeException("DB down"));
            var episode = Episode.of("ctx", "content", 0.5);

            assertThatCode(() -> adapter.store(episode)).doesNotThrowAnyException();
        }

        // --- episode type mapping ---

        @Test
        @DisplayName("INTERACTION episode type maps to CONTEXT category")
        void interactionMapsToContext() throws Exception {
            var episode = new Episode("id", "tenant", "ctx", "content", null,
                    Episode.EpisodeType.INTERACTION, 0.5, Map.of(), Instant.now());

            adapter.store(episode);

            verify(client).createMemory(anyString(), eq("CONTEXT"), anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("ACTION episode type maps to ACTION category")
        void actionMapsToAction() throws Exception {
            var episode = new Episode("id", "tenant", "ctx", "content", null,
                    Episode.EpisodeType.ACTION, 0.5, Map.of(), Instant.now());

            adapter.store(episode);

            verify(client).createMemory(anyString(), eq("ACTION"), anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("ERROR episode type maps to WARNING category")
        void errorMapsToWarning() throws Exception {
            var episode = new Episode("id", "tenant", "ctx", "content", null,
                    Episode.EpisodeType.ERROR, 0.5, Map.of(), Instant.now());

            adapter.store(episode);

            verify(client).createMemory(anyString(), eq("WARNING"), anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("FEEDBACK episode type maps to KNOWLEDGE category")
        void feedbackMapsToKnowledge() throws Exception {
            var episode = new Episode("id", "tenant", "ctx", "content", null,
                    Episode.EpisodeType.FEEDBACK, 0.5, Map.of(), Instant.now());

            adapter.store(episode);

            verify(client).createMemory(anyString(), eq("KNOWLEDGE"), anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("OUTCOME episode type maps to INSIGHT category")
        void outcomeMapsToInsight() throws Exception {
            var episode = new Episode("id", "tenant", "ctx", "content", null,
                    Episode.EpisodeType.OUTCOME, 0.5, Map.of(), Instant.now());

            adapter.store(episode);

            verify(client).createMemory(anyString(), eq("INSIGHT"), anyString(), anyString(), anyList());
        }

        // --- getById ---

        @Test
        @DisplayName("getById returns empty Optional when client throws")
        void getByIdHandlesError() throws Exception {
            when(client.getMemory(anyString())).thenThrow(new RuntimeException("timeout"));

            Optional<Episode> result = adapter.getById("some-id");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getById extracts tenantId from 'tenant:' tag")
        void getByIdExtractsTenantFromTag() throws Exception {
            var memory = new Memory("ep-1", "Content", null, "KNOWLEDGE", "IMPORTANT", "SEMANTIC",
                    List.of("tenant:acme", "archflow"), Map.of(), Instant.now());
            when(client.getMemory("ep-1")).thenReturn(Optional.of(memory));

            Optional<Episode> result = adapter.getById("ep-1");

            assertThat(result).isPresent();
            assertThat(result.get().tenantId()).isEqualTo("acme");
        }

        @Test
        @DisplayName("getById falls back to SYSTEM tenant when no tenant tag present")
        void getByIdFallsBackToSystem() throws Exception {
            var memory = new Memory("ep-1", "Content", null, null, null, null,
                    List.of("archflow"), Map.of(), Instant.now());
            when(client.getMemory("ep-1")).thenReturn(Optional.of(memory));

            Optional<Episode> result = adapter.getById("ep-1");

            assertThat(result).isPresent();
            assertThat(result.get().tenantId()).isEqualTo("SYSTEM");
        }

        // --- clear / evict / size ---

        @Test
        @DisplayName("clear() does not throw")
        void clearDoesNotThrow() {
            assertThatCode(() -> adapter.clear("ctx")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("evict() returns 0 (delegated to Brain Sentry)")
        void evictReturnsZero() {
            assertThat(adapter.evict("ctx", 100)).isZero();
        }

        @Test
        @DisplayName("size() returns -1 (unknown)")
        void sizeReturnsNegativeOne() {
            assertThat(adapter.size()).isEqualTo(-1);
        }

        // ---------------------------------------------------------------------------
        // Helper
        // ---------------------------------------------------------------------------

        private Memory memoryWithTags(String id, String tenantId) {
            return new Memory(id, "Memory content", null, "KNOWLEDGE", "IMPORTANT", "SEMANTIC",
                    List.of("tenant:" + tenantId, "archflow"), Map.of(), Instant.now());
        }
    }
}
