package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Modelos permitidos por tenant aplicados na execução (RCHFLW-79). O seletor do designer já recorta
 * a lista, mas o YAML e a API escrevem qualquer id — sem a conferência aqui a restrição era visual.
 */
@DisplayName("TenantModelPolicy — allowedModels do tenant na execução")
class TenantModelPolicyTest {

    /** O tenant "acme" só pode usar dois modelos; os demais não têm restrição. */
    private static final TenantModelPolicy ACME = tenantId -> "acme".equals(tenantId)
            ? Optional.of(List.of("openai/gpt-6-luna", " google/gemma-4-31b-it:free "))
            : Optional.empty();

    @Test
    @DisplayName("modelo permitido passa (a lista é comparada aparada)")
    void allowedModelPasses() {
        assertThatCode(() -> ACME.check("acme", "openrouter", "openai/gpt-6-luna")).doesNotThrowAnyException();
        assertThatCode(() -> ACME.check("acme", "openrouter", "google/gemma-4-31b-it:free"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("modelo fora da lista falha dizendo o modelo, o tenant e a lista")
    void deniedModelFails() {
        assertThatThrownBy(() -> ACME.check("acme", "openrouter", "anthropic/claude-sonnet-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[LLM-MODELO]")
                .hasMessageContaining("anthropic/claude-sonnet-4")
                .hasMessageContaining("'acme'")
                .hasMessageContaining("openai/gpt-6-luna");
    }

    @Test
    @DisplayName("com restrição, nó sem modelo falha — o default do adapter ninguém conferiu")
    void restrictedWithoutModelFails() {
        assertThatThrownBy(() -> ACME.check("acme", "openai", (String) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não diz o modelo");
    }

    @Test
    @DisplayName("o modelo de fallback também é conferido")
    void fallbackModelIsChecked() {
        assertThatThrownBy(() -> ACME.check("acme", "openrouter", List.of("openai/gpt-6-luna", "llama3.1")))
                .hasMessageContaining("llama3.1");
    }

    @Test
    @DisplayName("tenant sem restrição (lista vazia ou sem cadastro) usa qualquer modelo")
    void unrestrictedTenantUsesAnything() {
        TenantModelPolicy empty = tenantId -> Optional.of(List.of());
        assertThatCode(() -> empty.check("acme", "openai", "qualquer")).doesNotThrowAnyException();
        assertThatCode(() -> ACME.check("outro", "openai", "qualquer")).doesNotThrowAnyException();
        assertThatCode(() -> ACME.check("outro", "openai", (String) null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SYSTEM / __default__ / __global__ / nulo não são restringidos (a política nem é consultada)")
    void noTenantIsNotRestricted() {
        TenantModelPolicy everyoneRestricted = tenantId -> {
            throw new AssertionError("consultou a política para o sentinela " + tenantId);
        };
        for (String sentinel : Arrays.asList(null, "", "SYSTEM", "__default__", "__global__")) {
            assertThatCode(() -> everyoneRestricted.check(sentinel, "openai", "x")).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("UNRESTRICTED não restringe ninguém")
    void unrestrictedConstant() {
        assertThatCode(() -> TenantModelPolicy.UNRESTRICTED.check("acme", "openai", "x"))
                .doesNotThrowAnyException();
    }

    /** O caminho dos agentes: o resolver confere o modelo FINAL da cadeia, antes de criar o modelo. */
    @Nested
    @DisplayName("no DefaultLLMConfigResolver")
    class NoResolver {

        private DefaultLLMConfigResolver resolver;

        private static ResolvedLLMConfig ollama() {
            return ResolvedLLMConfig.builder()
                    .provider("ollama")
                    .model("llama3.2")
                    .temperature(0.2)
                    .additionalConfig(Map.of("baseUrl", "http://localhost:11434"))
                    .build();
        }

        @BeforeEach
        void setUp() {
            LLMProviderHub.reset();
            TenantModelPolicy policy = tenantId -> "acme".equals(tenantId)
                    ? Optional.of(List.of("llama3.2")) : Optional.empty();
            resolver = new DefaultLLMConfigResolver(LLMProviderHub.getInstance(), TenantKeyResolver.NOOP,
                    TierModelResolver.NOOP, ProviderPreferences.NONE, false, BaseUrlPolicy.officialOnly(),
                    policy);
        }

        @AfterEach
        void tearDown() {
            LLMProviderHub.reset();
        }

        @Test
        @DisplayName("tenant restrito: modelo do patch do passo fora da lista falha antes de criar o modelo")
        void stepPatchOutsideListFails() {
            LLMResolutionRequest req = LLMResolutionRequest.builder(ollama())
                    .tenantId("acme")
                    .stepPatch(LLMConfigPatch.builder().model("mistral").build())
                    .build();

            assertThatThrownBy(() -> resolver.resolveModel(req))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[LLM-MODELO]")
                    .hasMessageContaining("mistral");
        }

        @Test
        @DisplayName("tenant restrito com modelo permitido cria o modelo")
        void allowedModelIsBuilt() {
            LLMResolutionRequest req = LLMResolutionRequest.builder(ollama()).tenantId("acme").build();

            ChatModel model = resolver.resolveModel(req);

            assertThat(model).isNotNull();
        }

        @Test
        @DisplayName("SYSTEM (as execuções sem tenant hoje) não é restringido")
        void systemIsNotRestricted() {
            LLMResolutionRequest req = LLMResolutionRequest.builder(ollama())
                    .tenantId("SYSTEM")
                    .stepPatch(LLMConfigPatch.builder().model("mistral").build())
                    .build();

            assertThat(resolver.resolveModel(req)).isNotNull();
        }
    }
}
