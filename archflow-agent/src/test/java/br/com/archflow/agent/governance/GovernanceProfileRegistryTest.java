package br.com.archflow.agent.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@DisplayName("GovernanceProfileRegistry")
class GovernanceProfileRegistryTest {
    private GovernanceProfileRegistry registry;

    @BeforeEach void setUp() { registry = new GovernanceProfileRegistry(); }

    @Test @DisplayName("should resolve default") void shouldResolveDefault() {
        assertThat(registry.resolve("x").id()).isEqualTo("default");
    }
    @Test @DisplayName("should register and resolve") void shouldRegister() {
        registry.register(GovernanceProfile.builder().id("t1").name("T1").build());
        assertThat(registry.resolve("t1").name()).isEqualTo("T1");
    }
    @Test @DisplayName("should list all") void shouldList() {
        registry.register(GovernanceProfile.builder().id("a").name("A").build());
        registry.register(GovernanceProfile.builder().id("b").name("B").build());
        assertThat(registry.listAll()).hasSize(2);
    }
    @Test @DisplayName("should remove") void shouldRemove() {
        registry.register(GovernanceProfile.builder().id("a").name("A").build());
        assertThat(registry.remove("a")).isTrue();
        assertThat(registry.get("a")).isEmpty();
    }
    @Test @DisplayName("should resolve null to default") void shouldResolveNull() {
        assertThat(registry.resolve(null).id()).isEqualTo("default");
    }
}
