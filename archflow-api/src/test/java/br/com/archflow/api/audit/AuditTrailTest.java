package br.com.archflow.api.audit;

import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("AuditTrail")
class AuditTrailTest {

    private static List<AuditEvent> all(AuditRepository repo) {
        return repo.query(AuditRepository.AuditQuery.builder().limit(100));
    }

    @Test
    @DisplayName("no-op sem repositório: nunca lança")
    void noopWithoutRepository() {
        assertThatCode(() -> AuditTrail.noop()
                .record(AuditAction.CREATE, "workflow", "wf-1", true, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("grava evento com ator explícito, recurso e contexto (tenant incluído)")
    void recordsEventWithExplicitActor() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        AuditTrail trail = new AuditTrail(() -> repo);

        trail.record("user-1", "john", AuditAction.CREATE, "apikey", "key-1",
                true, null, Map.of("name", "My Key"));

        List<AuditEvent> events = all(repo);
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(event.getUserId()).isEqualTo("user-1");
        assertThat(event.getUsername()).isEqualTo("john");
        assertThat(event.getResourceType()).isEqualTo("apikey");
        assertThat(event.getResourceId()).isEqualTo("key-1");
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getContext())
                .containsEntry("name", "My Key")
                .containsKey(AuditTrail.CONTEXT_TENANT);
    }

    @Test
    @DisplayName("falha: success=false e errorMessage preservados")
    void recordsFailure() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        AuditTrail trail = new AuditTrail(() -> repo);

        trail.record(null, "john", AuditAction.LOGIN_FAILED, "auth", "john",
                false, "Invalid username or password", null);

        AuditEvent event = all(repo).get(0);
        assertThat(event.isSuccess()).isFalse();
        assertThat(event.getErrorMessage()).isEqualTo("Invalid username or password");
    }

    @Test
    @DisplayName("repositório que lança não propaga para o chamador")
    void swallowsRepositoryErrors() {
        AuditRepository broken = org.mockito.Mockito.mock(AuditRepository.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(broken).save(org.mockito.Mockito.any());
        AuditTrail trail = new AuditTrail(() -> broken);

        assertThatCode(() -> trail.record(AuditAction.DELETE, "workflow", "wf-1",
                true, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("supplier que lança não propaga para o chamador")
    void swallowsSupplierErrors() {
        AuditTrail trail = new AuditTrail(() -> {
            throw new IllegalStateException("provider broken");
        });

        assertThatCode(() -> trail.record(AuditAction.UPDATE, "tenant", "t-1",
                true, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fora de um request: ator resolvido como nulo, evento ainda gravado")
    void recordsWithoutRequestContext() {
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        AuditTrail trail = new AuditTrail(() -> repo);

        trail.record(AuditAction.CONFIG_CHANGE, "global-config", "featureToggles",
                true, null, null);

        AuditEvent event = all(repo).get(0);
        assertThat(event.getUserId()).isNull();
        assertThat(event.getUsername()).isNull();
        assertThat(event.getResourceId()).isEqualTo("featureToggles");
    }
}
