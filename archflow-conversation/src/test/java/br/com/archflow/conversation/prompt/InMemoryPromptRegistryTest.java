package br.com.archflow.conversation.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryPromptRegistryTest {

    private InMemoryPromptRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPromptRegistry();
    }

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        void firstVersionBecomesActive() {
            PromptVersion v = registry.register("tenant-1", "sac.greeting", "Olá {{nome}}");

            assertThat(v.version()).isEqualTo(1);
            assertThat(v.active()).isTrue();
            assertThat(v.tenantId()).isEqualTo("tenant-1");
            assertThat(v.promptId()).isEqualTo("sac.greeting");
        }

        @Test
        void subsequentVersionsAreInactiveByDefault() {
            registry.register("tenant-1", "sac.greeting", "v1");
            PromptVersion v2 = registry.register("tenant-1", "sac.greeting", "v2");
            PromptVersion v3 = registry.register("tenant-1", "sac.greeting", "v3");

            assertThat(v2.version()).isEqualTo(2);
            assertThat(v2.active()).isFalse();
            assertThat(v3.version()).isEqualTo(3);
            assertThat(v3.active()).isFalse();

            assertThat(registry.getActive("tenant-1", "sac.greeting"))
                    .map(PromptVersion::version)
                    .contains(1);
        }

        @Test
        void differentTenantsAreIsolated() {
            registry.register("tenant-A", "sac.greeting", "Hello A");
            registry.register("tenant-B", "sac.greeting", "Hello B");

            assertThat(registry.getActive("tenant-A", "sac.greeting"))
                    .map(PromptVersion::template)
                    .contains("Hello A");
            assertThat(registry.getActive("tenant-B", "sac.greeting"))
                    .map(PromptVersion::template)
                    .contains("Hello B");
        }
    }

    @Nested
    @DisplayName("activateVersion()")
    class Activate {

        @Test
        void promotesNewVersionAndDeactivatesOld() {
            registry.register("t1", "p1", "v1");
            registry.register("t1", "p1", "v2");
            registry.register("t1", "p1", "v3");

            registry.activateVersion("t1", "p1", 3);

            assertThat(registry.getActive("t1", "p1"))
                    .map(PromptVersion::version)
                    .contains(3);

            // Check v1 was deactivated
            assertThat(registry.getVersion("t1", "p1", 1))
                    .map(PromptVersion::active)
                    .contains(false);
            assertThat(registry.getVersion("t1", "p1", 2))
                    .map(PromptVersion::active)
                    .contains(false);
        }

        @Test
        void rollbackToOlderVersion() {
            registry.register("t1", "p1", "v1");
            registry.register("t1", "p1", "v2");
            registry.activateVersion("t1", "p1", 2);

            // rollback
            registry.activateVersion("t1", "p1", 1);

            assertThat(registry.getActive("t1", "p1"))
                    .map(PromptVersion::version)
                    .contains(1);
        }
    }

    @Nested
    @DisplayName("listVersions()")
    class ListVersions {

        @Test
        void returnsNewestFirst() {
            registry.register("t1", "p1", "v1");
            registry.register("t1", "p1", "v2");
            registry.register("t1", "p1", "v3");

            List<PromptVersion> versions = registry.listVersions("t1", "p1");

            assertThat(versions).hasSize(3);
            assertThat(versions).extracting(PromptVersion::version).containsExactly(3, 2, 1);
        }

        @Test
        void emptyForUnknownPrompt() {
            assertThat(registry.listVersions("t1", "missing")).isEmpty();
        }
    }

    @Nested
    @DisplayName("listPromptIds()")
    class ListPromptIds {

        @Test
        void listsAllPromptIdsSorted() {
            registry.register("t1", "sac.greeting", "g");
            registry.register("t1", "sac.tracking", "t");
            registry.register("t1", "sac.complaint", "c");

            assertThat(registry.listPromptIds("t1"))
                    .containsExactly("sac.complaint", "sac.greeting", "sac.tracking");
        }

        @Test
        void emptyForUnknownTenant() {
            assertThat(registry.listPromptIds("ghost")).isEmpty();
        }
    }

    @Nested
    @DisplayName("PromptVersion.render()")
    class Render {

        @Test
        void substitutesPlaceholders() {
            PromptVersion v = registry.register("t1", "p", "Olá {{nome}}, seu pedido {{numero}} está {{status}}.");

            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("nome", "Edson");
            vars.put("numero", "12345");
            vars.put("status", "EM_TRANSITO");

            assertThat(v.render(vars))
                    .isEqualTo("Olá Edson, seu pedido 12345 está EM_TRANSITO.");
        }

        @Test
        void leavesUnresolvedPlaceholdersIntact() {
            PromptVersion v = registry.register("t1", "p", "Hi {{nome}}, your {{tipo}} is ready");
            assertThat(v.render(Map.of("nome", "Ana"))).isEqualTo("Hi Ana, your {{tipo}} is ready");
        }

        @Test
        void handlesNullAndEmptyVariables() {
            PromptVersion v = registry.register("t1", "p", "Static text");
            assertThat(v.render(null)).isEqualTo("Static text");
            assertThat(v.render(Map.of())).isEqualTo("Static text");
        }

        @Test
        void escapesRegexCharactersInReplacement() {
            PromptVersion v = registry.register("t1", "p", "value: {{x}}");
            assertThat(v.render(Map.of("x", "$1\\\\money"))).isEqualTo("value: $1\\\\money");
        }
    }

    @Test
    void getVersionReturnsEmptyForUnknown() {
        Optional<PromptVersion> v = registry.getVersion("t1", "missing", 99);
        assertThat(v).isEmpty();
    }

    @Test
    void promptVersionConstructorRejectsInvalidVersion() {
        assertThatThrownBy(() -> new PromptVersion("p", "t", 0, "x", true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
