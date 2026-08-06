package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Controle de raciocínio: o nó declara, o corpo da requisição carrega.
 *
 * <h2>O que motivou</h2>
 *
 * <p>Comparação de quatro modelos contra o mesmo pedido, 06/08. Só o modelo em
 * produção raciocina — {@code gemini-2.5-flash-lite}, 399 tokens de raciocínio
 * por chamada e 2969 ms de média, contra ~940 ms dos que não raciocinam. A ~8,1
 * chamadas por cotação, são ~24 s de tempo de modelo contra ~8 s.
 *
 * <p>E o raciocínio não custou só tempo: foi ele que causou os {@code MAX_TOKENS}.
 * Com teto de 4096, três chamadas gastaram ~3930 pensando e sobraram ~110 para a
 * resposta — o modelo era cortado <b>antes</b> de emitir a chamada de tool.
 * Subir para 8192 contornou, mas trata o sintoma: o teto só era disputado porque
 * o raciocínio disputava.
 *
 * <p>Para um agente cuja saída útil é uma <i>chamada de tool</i>, raciocínio
 * longo é desperdício que ainda por cima corta a resposta.
 */
@DisplayName("reasoning no corpo da requisição")
class ReasoningNoCorpoTest {

    private static final ResolvedLLMConfig PLATAFORMA = ResolvedLLMConfig.builder()
            .provider("openrouter").model("google/gemini-2.5-flash-lite").maxTokens(4096).build();

    private static DefaultLLMConfigResolver resolvedor() {
        return new DefaultLLMConfigResolver(LLMProviderHub.getInstance());
    }

    @Nested
    @DisplayName("o nó declara e o campo atravessa")
    class Atravessa {

        @Test
        @DisplayName("effort chega ao LLMProviderConfig, na forma declarada")
        void effortAtravessa() {
            LLMConfigPatch patch = LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("effort", "none")));

            ResolvedLLMConfig resolvido = resolvedor().resolve(
                    LLMResolutionRequest.builder(PLATAFORMA).stepPatch(patch).build());
            LLMProviderConfig provider = resolvedor().toProviderConfig(resolvido, "k");

            assertThat(provider.getReasoning())
                    .as("sem isto o campo morre no patch e nunca vira corpo de requisicao")
                    .isEqualTo(Map.of("effort", "none"));
        }

        @Test
        @DisplayName("max_tokens atravessa como número")
        void maxTokensAtravessa() {
            LLMConfigPatch patch = LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("max_tokens", 512)));

            ResolvedLLMConfig r = resolvedor().resolve(
                    LLMResolutionRequest.builder(PLATAFORMA).stepPatch(patch).build());

            assertThat(r.reasoning()).contains(Map.of("max_tokens", 512));
        }

        /**
         * A forma não é interpretada: a documentação do OpenRouter é ambígua
         * sobre qual variante o Gemini 2.5 honra, e fixar um palpite aqui
         * transformaria uma incerteza do provedor numa decisão nossa.
         */
        @Test
        @DisplayName("chave desconhecida passa — o provedor é a autoridade")
        void chaveDesconhecidaPassa() {
            LLMConfigPatch patch = LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("thinkingLevel", "off")));

            assertThat(patch.reasoning()).contains(Map.of("thinkingLevel", "off"));
        }

        @Test
        @DisplayName("o passo vence o fluxo, e substitui em vez de mesclar")
        void passoSubstituiFluxo() {
            LLMConfigPatch fluxo = LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("max_tokens", 2048)));
            LLMConfigPatch passo = LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("effort", "none")));

            ResolvedLLMConfig r = resolvedor().resolve(LLMResolutionRequest.builder(PLATAFORMA)
                    .flowPatch(fluxo).stepPatch(passo).build());

            assertThat(r.reasoning())
                    .as("as formas sao ALTERNATIVAS; mesclar daria um objeto com duas intencoes")
                    .contains(Map.of("effort", "none"));
        }

        @Test
        @DisplayName("declarado só no fluxo, o passo herda")
        void herdaDoFluxo() {
            ResolvedLLMConfig r = resolvedor().resolve(LLMResolutionRequest.builder(PLATAFORMA)
                    .flowPatch(LLMConfigPatch.fromMap(Map.of("reasoning", Map.of("effort", "low"))))
                    .build());

            assertThat(r.reasoning()).contains(Map.of("effort", "low"));
        }
    }

    @Nested
    @DisplayName("sem reasoning, nada muda")
    class CaminhoComum {

        /**
         * O requisito é "corpo inalterado, byte a byte". O observável equivalente
         * aqui é que nada de {@code reasoning} chega ao {@link LLMProviderConfig}
         * — é ele que decide se o builder do modelo é tocado.
         */
        @Test
        @DisplayName("nó sem a chave não produz reasoning nenhum")
        void semChaveNadaChega() {
            LLMConfigPatch patch = LLMConfigPatch.fromMap(
                    Map.of("model", "openai/gpt-4o", "maxTokens", 1024));

            ResolvedLLMConfig resolvido = resolvedor().resolve(
                    LLMResolutionRequest.builder(PLATAFORMA).stepPatch(patch).build());

            assertThat(resolvido.reasoning()).isEmpty();
            assertThat(resolvedor().toProviderConfig(resolvido, "k").getReasoning())
                    .as("um agente que nao pediu controle de raciocinio nao pode pagar por ele")
                    .isNull();
        }

        @Test
        @DisplayName("patch vazio continua vazio")
        void patchVazio() {
            assertThat(LLMConfigPatch.empty().reasoning()).isEmpty();
            assertThat(LLMConfigPatch.empty().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("o construtor de compatibilidade continua existindo")
        void compat() {
            LLMConfigPatch p = new LLMConfigPatch(
                    java.util.Optional.of("openai"), java.util.Optional.empty(),
                    java.util.OptionalDouble.empty(), java.util.OptionalInt.empty(),
                    java.util.OptionalLong.empty(), Map.of());

            assertThat(p.reasoning()).isEmpty();
        }

        @Test
        @DisplayName("toMap só traz reasoning quando ele existe")
        void toMapOmiteQuandoAusente() {
            assertThat(LLMConfigPatch.fromMap(Map.of("model", "x")).toMap())
                    .doesNotContainKey("reasoning");
            assertThat(LLMConfigPatch.fromMap(Map.of("reasoning", Map.of("effort", "none"))).toMap())
                    .containsKey("reasoning");
        }
    }

    /**
     * A demanda pede falha na inicialização, e não omissão silenciosa.
     *
     * <p>As outras chaves deste parser são tolerantes de propósito — um
     * {@code maxTokens} ilegível não entra e o default vale. Aqui isso seria
     * péssimo: quem declara {@code reasoning} está tentando cortar tempo e teto,
     * e um valor mal escrito ignorado devolveria exatamente o comportamento que
     * se quis evitar, com o operador convencido de que resolveu.
     */
    @Nested
    @DisplayName("valor inválido falha, não é ignorado")
    class Validacao {

        @Test
        @DisplayName("reasoning que não é objeto")
        void naoEObjeto() {
            assertThatThrownBy(() -> LLMConfigPatch.fromMap(Map.of("reasoning", "none")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deve ser um objeto");
        }

        @Test
        @DisplayName("reasoning vazio")
        void objetoVazio() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("reasoning", Map.of());

            assertThatThrownBy(() -> LLMConfigPatch.fromMap(config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vazio");
        }

        @Test
        @DisplayName("effort com tipo errado")
        void effortErrado() {
            assertThatThrownBy(() -> LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("effort", 3))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("effort");
        }

        @Test
        @DisplayName("max_tokens negativo")
        void maxTokensNegativo() {
            assertThatThrownBy(() -> LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("max_tokens", -1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max_tokens");
        }

        @Test
        @DisplayName("exclude com tipo errado")
        void excludeErrado() {
            assertThatThrownBy(() -> LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("exclude", "sim"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exclude");
        }

        /**
         * {@code exclude: true} apenas <b>esconde</b> o raciocínio da resposta —
         * não o evita. Não economiza tempo nem token. É aceito porque é forma
         * válida do provedor, e registrado aqui porque é a opção que mais parece
         * resolver e a que menos resolve.
         */
        @Test
        @DisplayName("exclude válido é aceito, ainda que não economize nada")
        void excludeValidoPassa() {
            assertThatCode(() -> LLMConfigPatch.fromMap(
                    Map.of("reasoning", Map.of("exclude", true))))
                    .doesNotThrowAnyException();
        }
    }
}
