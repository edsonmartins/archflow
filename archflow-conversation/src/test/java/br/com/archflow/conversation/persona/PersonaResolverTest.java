package br.com.archflow.conversation.persona;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaResolverTest {

    private static final Persona ORDER_TRACKING = Persona.of(
            "order_tracking", "Rastreamento", "sac.tracking",
            List.of("tracking_pedido"),
            "rastrear", "\\bentrega\\b", "\\bpedido\\b"
    );

    private static final Persona COMPLAINT = Persona.of(
            "complaint", "Reclamação", "sac.complaint",
            List.of("criar_ticket_reclamacao"),
            "reclamação", "reclamar", "atras[ao]"
    );

    private static final Persona DEFAULT = Persona.of(
            "general", "Atendimento Geral", "sac.general",
            List.of()
    );

    @Test
    void resolvesPersonaByKeywordMatch() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING, COMPLAINT), DEFAULT);

        Optional<Persona> p = resolver.resolve("conv-1", "quero rastrear meu pedido 12345");

        assertThat(p).map(Persona::id).contains("order_tracking");
    }

    @Test
    void resolvesSecondPersonaByDifferentKeyword() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING, COMPLAINT), DEFAULT);

        Optional<Persona> p = resolver.resolve("conv-1", "quero abrir uma reclamação");

        assertThat(p).map(Persona::id).contains("complaint");
    }

    @Test
    void fallsBackToDefaultWhenNoMatch() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING, COMPLAINT), DEFAULT);

        Optional<Persona> p = resolver.resolve("conv-1", "bom dia");

        assertThat(p).map(Persona::id).contains("general");
    }

    @Test
    void stickyContextReusesPreviousPersonaForSameConversation() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING, COMPLAINT), DEFAULT);

        // First turn matches keyword
        resolver.resolve("conv-1", "rastrear pedido");

        // Second turn has no keyword — should stick to order_tracking
        Optional<Persona> p = resolver.resolve("conv-1", "e agora?");

        assertThat(p).map(Persona::id).contains("order_tracking");
    }

    @Test
    void stickyContextIsPerConversation() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING, COMPLAINT), DEFAULT);

        resolver.resolve("conv-A", "rastrear pedido");
        resolver.resolve("conv-B", "reclamação");

        assertThat(resolver.resolve("conv-A", "continue").map(Persona::id)).contains("order_tracking");
        assertThat(resolver.resolve("conv-B", "continue").map(Persona::id)).contains("complaint");
    }

    @Test
    void clearStickyForgetsContext() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING, COMPLAINT), DEFAULT);
        resolver.resolve("conv-1", "rastrear pedido");

        resolver.clearSticky("conv-1");

        Optional<Persona> p = resolver.resolve("conv-1", "agora sim");
        assertThat(p).map(Persona::id).contains("general"); // back to default
    }

    @Test
    void llmClassifierWinsOverKeyword() {
        // User says "rastrear pedido" but LLM decides it's a complaint
        var resolver = new PersonaResolver(
                List.of(ORDER_TRACKING, COMPLAINT),
                DEFAULT,
                msg -> Optional.of("complaint")
        );

        Optional<Persona> p = resolver.resolve("conv-1", "rastrear pedido");
        assertThat(p).map(Persona::id).contains("complaint");
    }

    @Test
    void llmClassifierFailureFallsBackToKeyword() {
        var resolver = new PersonaResolver(
                List.of(ORDER_TRACKING, COMPLAINT),
                DEFAULT,
                msg -> { throw new RuntimeException("boom"); }
        );

        Optional<Persona> p = resolver.resolve("conv-1", "rastrear pedido");
        assertThat(p).map(Persona::id).contains("order_tracking");
    }

    @Test
    void llmClassifierReturningUnknownIdFallsBackToKeyword() {
        var resolver = new PersonaResolver(
                List.of(ORDER_TRACKING, COMPLAINT),
                DEFAULT,
                msg -> Optional.of("nonexistent")
        );

        Optional<Persona> p = resolver.resolve("conv-1", "rastrear pedido");
        assertThat(p).map(Persona::id).contains("order_tracking");
    }

    @Test
    void llmClassifierCalledOncePerResolve() {
        AtomicInteger calls = new AtomicInteger(0);
        var resolver = new PersonaResolver(
                List.of(ORDER_TRACKING),
                DEFAULT,
                msg -> {
                    calls.incrementAndGet();
                    return Optional.empty();
                }
        );

        resolver.resolve("c", "rastrear");
        resolver.resolve("c", "mais um");

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void returnsEmptyWhenNoMatchAndNoDefault() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING), null);
        assertThat(resolver.resolve("conv-1", "blabla")).isEmpty();
    }

    @Test
    void findByIdLocatesPersona() {
        var resolver = new PersonaResolver(List.of(ORDER_TRACKING, COMPLAINT), DEFAULT);
        assertThat(resolver.findById("complaint")).map(Persona::id).contains("complaint");
        assertThat(resolver.findById("missing")).isEmpty();
    }

    @Test
    void personaMatchesKeywordsIsCaseInsensitive() {
        assertThat(ORDER_TRACKING.matchesKeywords("QUERO RASTREAR")).isTrue();
        assertThat(ORDER_TRACKING.matchesKeywords("nada aqui")).isFalse();
        assertThat(ORDER_TRACKING.matchesKeywords(null)).isFalse();
    }
}
