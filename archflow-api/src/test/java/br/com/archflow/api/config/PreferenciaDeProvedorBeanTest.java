package br.com.archflow.api.config;

import br.com.archflow.decision.DecisionCall;
import br.com.archflow.decision.DecisionException;
import br.com.archflow.decision.DecisionProvider;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.decision.DecisionRequest;
import br.com.archflow.decision.HttpDecisionProvider;
import br.com.archflow.decision.Question;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Fiação da preferência de provedor (ZDR): a variável de ambiente chega à propriedade que o
 * {@code @Value} lê, e o nó {@code decision} usa o provedor HTTP que a carrega — não o embutido.
 */
@DisplayName("Bean da preferência de provedor (ZDR)")
class PreferenciaDeProvedorBeanTest {

    private final ArchflowBeanConfiguration config = new ArchflowBeanConfiguration();

    /** Na VPS a configuração vem por variável de ambiente; o hífen da propriedade vira '_'. */
    @Test
    @DisplayName("ARCHFLOW_LLM_PROVIDER_PREFERENCES_* resolve as propriedades lidas pelo @Value")
    void variavelDeAmbiente() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", Map.of(
                "ARCHFLOW_LLM_PROVIDER_PREFERENCES_ZDR", "true",
                "ARCHFLOW_LLM_PROVIDER_PREFERENCES_DATA_COLLECTION", "deny",
                "ARCHFLOW_LLM_PROVIDER_PREFERENCES_REQUIRED", "true")));

        assertThat(env.resolvePlaceholders("${archflow.llm.provider-preferences.zdr:}")).isEqualTo("true");
        assertThat(env.resolvePlaceholders("${archflow.llm.provider-preferences.data-collection:}"))
                .isEqualTo("deny");
        assertThat(env.resolvePlaceholders("${archflow.llm.provider-preferences.required:false}"))
                .isEqualTo("true");
        // E a propriedade vizinha, de nome-prefixo, não é tocada.
        assertThat(env.resolvePlaceholders("${archflow.llm.provider:openai}")).isEqualTo("openai");
    }

    @Test
    @DisplayName("sem propriedade, nenhuma preferência")
    void semPropriedade() {
        assertThat(config.llmProviderPreferences("", "")).isEqualTo(ProviderPreferences.NONE);
        assertThat(config.llmProviderPreferences("true", "deny"))
                .isEqualTo(new ProviderPreferences(true, "deny"));
    }

    /**
     * Se o bean registrasse o embutido, o {@code required} não valeria para o nó de decisão. A prova:
     * com {@code required} e preferência vazia, a decisão no endpoint padrão (OpenRouter) falha
     * <b>antes</b> de qualquer rede — o embutido tentaria a chamada.
     */
    @Test
    @DisplayName("o http-decisions do bean é o que carrega a preferência e o required")
    void decisaoUsaOProvedorConfigurado() {
        DecisionProviders providers = config.decisionProviders(mock(LLMConfigResolver.class),
                ResolvedLLMConfig.builder().provider("openrouter").model("m").build(),
                ProviderPreferences.NONE, true);
        DecisionProvider http = providers.find(HttpDecisionProvider.ID).orElseThrow();

        DecisionRequest pedido = new DecisionRequest(Map.of("x", 1),
                List.of(new Question.YesNo("ok", "Ok?", null, null)));
        assertThatThrownBy(() -> http.decide(pedido,
                new DecisionCall("t", "typesafe/jev-1.14", "k", Duration.ofSeconds(1), Map.of())))
                .isInstanceOf(DecisionException.class)
                .hasMessageContaining("provider-preferences.required");
    }
}
