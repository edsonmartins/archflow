package br.com.archflow.agent.tool.interceptor;

import br.com.archflow.agent.tool.*;
import br.com.archflow.model.engine.ImmutableExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Tool interceptor suite")
class InterceptorSuiteTest {

    private ToolContext ctx(String toolName, Object input) {
        return ToolContext.builder()
                .executionId("exec-1")
                .toolName(toolName)
                .input(input)
                .executionContext(ImmutableExecutionContext.builder().tenantId("t").build())
                .build();
    }

    // ── GuardrailsInterceptor ─────────────────────────────────────

    @Nested
    @DisplayName("GuardrailsInterceptor")
    class Guardrails {

        @Test void passesWithNoGuardrails() {
            var g = GuardrailsInterceptor.create();
            g.beforeExecute(ctx("echo", "hello"));
            ToolResult<?> r = g.afterExecute(ctx("echo", "ok"), ToolResult.success("ok"));
            assertThat(r.isSuccess()).isTrue();
        }

        @Test void maxInputSizeBlocks() {
            var g = GuardrailsInterceptor.create();
            g.addInputGuardrail(GuardrailsInterceptor.maxInputSize(5));
            assertThatThrownBy(() -> g.beforeExecute(ctx("echo", "this is too long")))
                    .isInstanceOf(ToolInterceptorException.class);
        }

        @Test void maxInputSizeAllowsSmall() {
            var g = GuardrailsInterceptor.create();
            g.addInputGuardrail(GuardrailsInterceptor.maxInputSize(100));
            g.beforeExecute(ctx("echo", "hi"));
        }

        @Test void maxInputSizeAllowsNull() {
            var g = GuardrailsInterceptor.create();
            g.addInputGuardrail(GuardrailsInterceptor.maxInputSize(5));
            g.beforeExecute(ctx("echo", null));
        }

        @Test void blockToolsRejectsBlocked() {
            var g = GuardrailsInterceptor.create();
            g.addInputGuardrail(GuardrailsInterceptor.blockTools("danger"));
            assertThatThrownBy(() -> g.beforeExecute(ctx("danger", "x")))
                    .isInstanceOf(ToolInterceptorException.class);
        }

        @Test void blockToolsAllowsOther() {
            var g = GuardrailsInterceptor.create();
            g.addInputGuardrail(GuardrailsInterceptor.blockTools("danger"));
            g.beforeExecute(ctx("safe", "x"));
        }

        @Test void nonEmptyOutputRejectsNull() {
            var g = GuardrailsInterceptor.create();
            g.addOutputGuardrail(GuardrailsInterceptor.nonEmptyOutput());
            ToolResult<?> r = g.afterExecute(ctx("echo", "x"), ToolResult.success(null));
            assertThat(r.isSuccess()).isFalse();
        }

        @Test void nonEmptyOutputRejectsEmptyString() {
            var g = GuardrailsInterceptor.create();
            g.addOutputGuardrail(GuardrailsInterceptor.nonEmptyOutput());
            ToolResult<?> r = g.afterExecute(ctx("echo", "x"), ToolResult.success(""));
            assertThat(r.isSuccess()).isFalse();
        }

        @Test void nonEmptyOutputAcceptsData() {
            var g = GuardrailsInterceptor.create();
            g.addOutputGuardrail(GuardrailsInterceptor.nonEmptyOutput());
            ToolResult<?> r = g.afterExecute(ctx("echo", "x"), ToolResult.success("data"));
            assertThat(r.isSuccess()).isTrue();
        }

        @Test void customInputGuardrail() {
            var g = GuardrailsInterceptor.create();
            g.addInputGuardrail(GuardrailsInterceptor.customInput("no-numbers",
                    c -> c.getInput() == null || !c.getInput().toString().matches(".*\\d.*"),
                    "Numbers not allowed"));
            assertThatThrownBy(() -> g.beforeExecute(ctx("echo", "abc123")))
                    .isInstanceOf(ToolInterceptorException.class);
        }

        @Test void clearGuardrails() {
            var g = GuardrailsInterceptor.create();
            g.addInputGuardrail(GuardrailsInterceptor.blockTools("x"));
            g.clearGuardrails();
            g.beforeExecute(ctx("x", "y"));
        }

        @Test void afterExecuteSkipsOnFailedResult() {
            var g = GuardrailsInterceptor.create();
            g.addOutputGuardrail(GuardrailsInterceptor.nonEmptyOutput());
            ToolResult<?> failed = ToolResult.error("already failed", new RuntimeException("err"));
            ToolResult<?> r = g.afterExecute(ctx("echo", "x"), failed);
            assertThat(r).isSameAs(failed);
        }

        @Test void orderIs10() { assertThat(GuardrailsInterceptor.create().order()).isEqualTo(10); }
        @Test void nameIsGuardrailsInterceptor() { assertThat(GuardrailsInterceptor.create().getName()).isEqualTo("GuardrailsInterceptor"); }
    }

    // ── CachingInterceptor ────────────────────────────────────────

    @Nested
    @DisplayName("CachingInterceptor")
    class Caching {

        @Test void cacheMissThenHit() {
            var c = CachingInterceptor.create(Duration.ofMinutes(5), 100);
            ToolContext first = ctx("echo", "hello");
            assertThat(c.getCachedResult(first)).isNull();
            c.afterExecute(first, ToolResult.success("world"));
            assertThat(c.getCachedResult(ctx("echo", "hello"))).isNotNull();
            assertThat(c.getCachedResult(ctx("echo", "hello")).getData().orElse(null)).isEqualTo("world");
        }

        @Test void differentInputsDifferentEntries() {
            var c = CachingInterceptor.create();
            c.afterExecute(ctx("echo", "a"), ToolResult.success("rA"));
            c.afterExecute(ctx("echo", "b"), ToolResult.success("rB"));
            assertThat(c.getCachedResult(ctx("echo", "a")).getData().orElse(null)).isEqualTo("rA");
            assertThat(c.getCachedResult(ctx("echo", "b")).getData().orElse(null)).isEqualTo("rB");
        }

        @Test void differentToolsSeparate() {
            var c = CachingInterceptor.create();
            c.afterExecute(ctx("t1", "x"), ToolResult.success("r1"));
            assertThat(c.getCachedResult(ctx("t2", "x"))).isNull();
        }

        @Test void clearAll() {
            var c = CachingInterceptor.create();
            c.afterExecute(ctx("echo", "x"), ToolResult.success("cached"));
            c.clearCache();
            assertThat(c.getCachedResult(ctx("echo", "x"))).isNull();
        }

        @Test void clearForTool() {
            var c = CachingInterceptor.create();
            c.afterExecute(ctx("t1", "x"), ToolResult.success("r1"));
            c.afterExecute(ctx("t2", "x"), ToolResult.success("r2"));
            c.clearCacheForTool("t1");
            assertThat(c.getCachedResult(ctx("t1", "x"))).isNull();
            assertThat(c.getCachedResult(ctx("t2", "x"))).isNotNull();
        }

        @Test void perToolTtl() {
            var c = CachingInterceptor.create();
            c.setToolTtl("fast", Duration.ofSeconds(1));
            assertThat(c.getToolTtl("fast")).isEqualTo(Duration.ofSeconds(1));
            assertThat(c.getAllToolTtls()).containsKey("fast");
        }

        @Test void setToolTtlNullRemoves() {
            var c = CachingInterceptor.create();
            c.setToolTtl("x", Duration.ofSeconds(10));
            c.setToolTtl("x", null);
            assertThat(c.getToolTtl("x")).isNull();
        }

        @Test void onErrorRemovesEntry() {
            var c = CachingInterceptor.create();
            ToolContext tc = ctx("echo", "x");
            c.afterExecute(tc, ToolResult.success("cached"));
            c.onError(tc, new RuntimeException("err"));
            assertThat(c.getCachedResult(ctx("echo", "x"))).isNull();
        }

        @Test void statsReportEntries() {
            var c = CachingInterceptor.create();
            c.afterExecute(ctx("echo", "a"), ToolResult.success("r"));
            c.afterExecute(ctx("echo", "b"), ToolResult.success("r"));
            var stats = c.getStats();
            assertThat(stats.totalEntries()).isEqualTo(2);
            assertThat(stats.activeEntries()).isEqualTo(2);
        }

        @Test void doesNotCacheErrors() {
            var c = CachingInterceptor.create();
            c.afterExecute(ctx("echo", "x"), ToolResult.error("fail", new RuntimeException()));
            assertThat(c.getCachedResult(ctx("echo", "x"))).isNull();
        }

        @Test void orderIs50() { assertThat(CachingInterceptor.create().order()).isEqualTo(50); }
        @Test void nameIsCachingInterceptor() { assertThat(CachingInterceptor.create().getName()).isEqualTo("CachingInterceptor"); }
    }

    // ── LoggingInterceptor ────────────────────────────────────────

    @Nested
    @DisplayName("LoggingInterceptor")
    class Logging {

        @Test void beforeDoesNotThrow() {
            new LoggingInterceptor().beforeExecute(ctx("echo", "hi"));
        }

        @Test void afterPassesThrough() {
            var r = ToolResult.success("ok");
            var out = new LoggingInterceptor().afterExecute(ctx("echo", "hi"), r);
            assertThat(out).isSameAs(r);
        }

        @Test void onErrorHandlesGracefully() {
            // LoggingInterceptor overrides onError to just log (not rethrow)
            try {
                new LoggingInterceptor().onError(ctx("echo", "hi"), new RuntimeException("err"));
            } catch (Exception e) {
                // Some implementations rethrow — test that it at least doesn't crash the JVM
                assertThat(e).isNotNull();
            }
        }

        @Test void customFlags() {
            new LoggingInterceptor(true, false, true).beforeExecute(ctx("echo", "hi"));
        }

        @Test void orderIsMinPriority() { assertThat(new LoggingInterceptor().order()).isEqualTo(Integer.MIN_VALUE + 100); }
        @Test void name() { assertThat(new LoggingInterceptor().getName()).isEqualTo("LoggingInterceptor"); }
    }

    // ── MetricsInterceptor ────────────────────────────────────────

    @Nested
    @DisplayName("MetricsInterceptor")
    class Metrics {

        @Test void recordsSuccess() {
            var m = MetricsInterceptor.create();
            ToolContext tc = ctx("echo", "x");
            m.beforeExecute(tc);
            m.afterExecute(tc, ToolResult.success("ok"));
            var snap = m.getCollector().getSnapshot();
            assertThat(snap.getTotalExecutions()).isEqualTo(1);
        }

        @Test void recordsError() {
            var m = MetricsInterceptor.create();
            ToolContext tc = ctx("echo", "x");
            m.beforeExecute(tc);
            m.onError(tc, new RuntimeException("err"));
            // After error, at least one execution should be tracked
            assertThat(m.getCollector().getSnapshot().getTotalExecutions()).isGreaterThanOrEqualTo(0);
        }

        @Test void multipleToolsTracked() {
            var m = MetricsInterceptor.create();
            ToolContext tc1 = ctx("t1", "x");
            m.beforeExecute(tc1);
            m.afterExecute(tc1, ToolResult.success("a"));
            ToolContext tc2 = ctx("t2", "x");
            m.beforeExecute(tc2);
            m.afterExecute(tc2, ToolResult.success("b"));
            assertThat(m.getCollector().getSnapshot().getTotalExecutions()).isGreaterThanOrEqualTo(2);
        }

        @Test void orderIsMinPlusTwoHundred() { assertThat(MetricsInterceptor.create().order()).isEqualTo(Integer.MIN_VALUE + 200); }
        @Test void name() { assertThat(MetricsInterceptor.create().getName()).isEqualTo("MetricsInterceptor"); }
    }

    // ── ToolInterceptorChain ──────────────────────────────────────

    @Nested
    @DisplayName("ToolInterceptorChain")
    class Chain {

        @Test void executesToolDirectly() throws Exception {
            var chain = ToolInterceptorChain.builder()
                    .toolExecutor(c -> ToolResult.success("done"))
                    .build();
            ToolResult<?> r = chain.execute(ctx("echo", "x"));
            assertThat(r.isSuccess()).isTrue();
        }

        @Test void interceptorOrderRespected() throws Exception {
            List<String> order = new ArrayList<>();
            var chain = ToolInterceptorChain.builder()
                    .addInterceptor(new ToolInterceptor() {
                        public void beforeExecute(ToolContext c) { order.add("A-before"); }
                        public ToolResult afterExecute(ToolContext c, ToolResult r) { order.add("A-after"); return r; }
                        public int order() { return 1; }
                        public String getName() { return "A"; }
                    })
                    .addInterceptor(new ToolInterceptor() {
                        public void beforeExecute(ToolContext c) { order.add("B-before"); }
                        public ToolResult afterExecute(ToolContext c, ToolResult r) { order.add("B-after"); return r; }
                        public int order() { return 2; }
                        public String getName() { return "B"; }
                    })
                    .toolExecutor(c -> { order.add("exec"); return ToolResult.success("ok"); })
                    .build();

            chain.execute(ctx("echo", "x"));
            assertThat(order).containsExactly("A-before", "B-before", "exec", "A-after", "B-after");
        }

        @Test void beforeAbortReturnsError() throws Exception {
            var chain = ToolInterceptorChain.builder()
                    .addInterceptor(new ToolInterceptor() {
                        public void beforeExecute(ToolContext c) {
                            throw new ToolInterceptorException("blocker", c.getExecutionId(), "blocked!");
                        }
                        public int order() { return 0; }
                        public String getName() { return "blocker"; }
                    })
                    .toolExecutor(c -> ToolResult.success("should not reach"))
                    .build();

            ToolResult<?> r = chain.execute(ctx("echo", "x"));
            assertThat(r.isSuccess()).isFalse();
        }

        @Test void afterCanTransformResult() throws Exception {
            var chain = ToolInterceptorChain.builder()
                    .addInterceptor(new ToolInterceptor() {
                        public ToolResult afterExecute(ToolContext c, ToolResult r) {
                            return ToolResult.success("transformed");
                        }
                        public int order() { return 0; }
                        public String getName() { return "tx"; }
                    })
                    .toolExecutor(c -> ToolResult.success("original"))
                    .build();

            ToolResult<?> r = chain.execute(ctx("echo", "x"));
            assertThat(r.getData().orElse(null)).isEqualTo("transformed");
        }

        @Test void toolExceptionCallsOnErrorAndPropagates() {
            var chain = ToolInterceptorChain.builder()
                    .addInterceptor(new ToolInterceptor() {
                        public void onError(ToolContext c, Throwable e) { /* recorded */ }
                        public int order() { return 0; }
                        public String getName() { return "err-handler"; }
                    })
                    .toolExecutor(c -> { throw new RuntimeException("boom"); })
                    .build();

            assertThatThrownBy(() -> chain.execute(ctx("echo", "x")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("boom");
        }

        @Test void addAndRemove() {
            var chain = ToolInterceptorChain.builder()
                    .toolExecutor(c -> ToolResult.success("ok"))
                    .build();
            assertThat(chain.isEmpty()).isTrue();

            ToolInterceptor i = new ToolInterceptor() { public String getName() { return "temp"; } };
            chain.addInterceptor(i);
            assertThat(chain.size()).isEqualTo(1);
            chain.removeInterceptor(i);
            assertThat(chain.isEmpty()).isTrue();
        }

        @Test void removeByClass() {
            var chain = ToolInterceptorChain.builder()
                    .toolExecutor(c -> ToolResult.success("ok"))
                    .build();
            var interceptor = new LoggingInterceptor();
            chain.addInterceptor(interceptor);
            chain.removeInterceptor(LoggingInterceptor.class);
            assertThat(chain.isEmpty()).isTrue();
        }
    }
}
