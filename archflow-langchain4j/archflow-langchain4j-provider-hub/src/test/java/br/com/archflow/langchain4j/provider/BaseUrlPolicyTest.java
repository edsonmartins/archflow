package br.com.archflow.langchain4j.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BaseUrlPolicy — para onde uma chave governada pode ir")
class BaseUrlPolicyTest {

    @Test
    @DisplayName("o endereço oficial do provider passa, qualquer caminho")
    void officialPasses() {
        var p = BaseUrlPolicy.officialOnly();
        assertThat(p.isAllowed("openrouter", "https://openrouter.ai/api/v1")).isTrue();
        assertThat(p.isAllowed("openrouter", "https://OPENROUTER.ai/api")).isTrue();
        assertThat(p.isAllowed("openai", "https://api.openai.com/v1")).isTrue();
    }

    @Test
    @DisplayName("host estranho, http no lugar de https e porta diferente não passam")
    void foreignDoesNotPass() {
        var p = BaseUrlPolicy.officialOnly();
        assertThat(p.isAllowed("openrouter", "https://evil.example/api/v1")).isFalse();
        assertThat(p.isAllowed("openrouter", "http://openrouter.ai/api/v1")).isFalse();
        assertThat(p.isAllowed("openrouter", "https://openrouter.ai:8443/api/v1")).isFalse();
        assertThat(p.isAllowed("openrouter", "https://openrouter.ai.evil.example/api/v1")).isFalse();
        assertThat(p.isAllowed("openrouter", "nem-url")).isFalse();
        assertThat(p.isAllowed("openai", "https://openrouter.ai/api/v1"))
                .as("o oficial de um provider não vale para outro").isFalse();
    }

    @Test
    @DisplayName("parte variável do endereço oficial vale um rótulo, não um ponto")
    void templatedOfficial() {
        var p = BaseUrlPolicy.officialOnly();
        assertThat(p.isAllowed("azure-openai", "https://minha-empresa.openai.azure.com")).isTrue();
        assertThat(p.isAllowed("azure-openai", "https://evil.com.x.openai.azure.com")).isFalse();
    }

    @Test
    @DisplayName("a lista do operador: URL inteira (com http e porta) ou host (só https)")
    void operatorList() {
        var p = BaseUrlPolicy.of(List.of("http://127.0.0.1:9999", "gateway.acme.com", " "));
        assertThat(p.isAllowed("openrouter", "http://127.0.0.1:9999/api/v1")).isTrue();
        assertThat(p.isAllowed("openrouter", "http://127.0.0.1:9998/api/v1")).isFalse();
        assertThat(p.isAllowed("provider-qualquer", "https://gateway.acme.com/v1")).isTrue();
        assertThat(p.isAllowed("provider-qualquer", "http://gateway.acme.com/v1")).isFalse();
    }

    @Test
    @DisplayName("entrada inválida na lista falha na subida")
    void invalidEntryFailsFast() {
        assertThatThrownBy(() -> BaseUrlPolicy.of(List.of("https://")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-base-urls");
    }

    @Test
    @DisplayName("a recusa diz o campo, o host e como resolver")
    void checkMessage() {
        assertThatThrownBy(() -> BaseUrlPolicy.officialOnly()
                .check("openrouter", "https://evil.example/v1", "base.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[LLM-HOST]")
                .hasMessageContaining("base.url")
                .hasMessageContaining("evil.example")
                .hasMessageContaining("archflow.llm.allowed-base-urls");
    }
}
