package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O tier de LLM decide o modelo — e, quando não decide, diz por quê.
 *
 * <p><b>O defeito.</b> O invoke carregava {@code tier} ({@code LIGHT}/{@code STRONG}),
 * escolhido pela política de quem aciona, e ele era <b>descartado</b>: nenhuma
 * linha do runtime o lia, e o resolvedor sequer conhecia o conceito. Medido em
 * 06/08: invoke com {@code tier=STRONG} resolvia para o modelo da config do
 * tenant, e nada registrava a discordância.
 *
 * <p>O contorno foi declarar o modelo direto no nó do fluxo. Isso obriga quem
 * decide <i>política</i> ("esta tarefa é difícil") a conhecer <i>nome de modelo
 * de provedor</i> — a fronteira que a arquitetura não quer — e faz a política
 * virar decoração.
 *
 * <p><b>Metade da correção é o log.</b> Roteamento que falha em silêncio é pior
 * que roteamento ausente: o segundo é uma capacidade que se sabe não ter, o
 * primeiro é uma capacidade que parece existir. Por isso os testes aqui afirmam
 * sobre o log tanto quanto sobre o modelo.
 */
@DisplayName("roteamento de LLM por tier")
class TierRoutingTest {

    private static final ResolvedLLMConfig PLATAFORMA = ResolvedLLMConfig.builder()
            .provider("openrouter")
            .model("google/gemini-2.5-flash-lite")
            .temperature(0.2)
            .maxTokens(4096)
            .timeout(30)
            .build();

    private static final Map<String, String> MAPA = Map.of(
            "LIGHT", "google/gemini-2.5-flash-lite",
            "STRONG", "anthropic/claude-sonnet-4");

    /** Captura o log do resolvedor — o comportamento sob teste, não um efeito colateral. */
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void capturarLog() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultLLMConfigResolver.class);
        appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void soltarLog() {
        logger.detachAppender(appender);
    }

    private List<String> avisos() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static DefaultLLMConfigResolver resolvedorCom(TierModelResolver tiers) {
        return new DefaultLLMConfigResolver(LLMProviderHub.getInstance(),
                TenantKeyResolver.NOOP, tiers);
    }

    @Nested
    @DisplayName("com mapa para o tenant")
    class ComMapa {

        @Test
        @DisplayName("tier=STRONG resolve o modelo do tier, não o default")
        void strongUsaOModeloDoTier() {
            ResolvedLLMConfig r = resolvedorCom(TierModelResolver.fixed(MAPA))
                    .resolve(LLMResolutionRequest.builder(PLATAFORMA)
                            .tenantId("acme")
                            .tier("STRONG")
                            .build());

            assertThat(r.model())
                    .as("a decisao de roteamento do Playbook nao tinha efeito nenhum")
                    .isEqualTo("anthropic/claude-sonnet-4");
            assertThat(avisos()).as("tier honrado nao deve gerar ruido").isEmpty();
        }

        @Test
        @DisplayName("tier=LIGHT resolve o modelo dele")
        void lightTambem() {
            ResolvedLLMConfig r = resolvedorCom(TierModelResolver.fixed(MAPA))
                    .resolve(LLMResolutionRequest.builder(PLATAFORMA)
                            .tenantId("acme").tier("LIGHT").build());

            assertThat(r.model()).isEqualTo("google/gemini-2.5-flash-lite");
        }

        @Test
        @DisplayName("a caixa do tier não decide se a política se aplica")
        void caixaNaoImporta() {
            ResolvedLLMConfig r = resolvedorCom(TierModelResolver.fixed(MAPA))
                    .resolve(LLMResolutionRequest.builder(PLATAFORMA)
                            .tenantId("acme").tier("strong").build());

            assertThat(r.model())
                    .as("politica nao aplicada por diferenca de caixa e o mesmo defeito de novo")
                    .isEqualTo("anthropic/claude-sonnet-4");
        }

        /**
         * O caso do contorno atual: o nó declara {@code model}. Ele continua
         * vencendo — é o nível mais específico da precedência documentada, e
         * alguém o escolheu de propósito. Mas a discordância fica visível, que é
         * o que faltava.
         */
        @Test
        @DisplayName("modelo declarado no passo vence o tier — e o log diz isso")
        void patchMaisEspecificoVence() {
            ResolvedLLMConfig r = resolvedorCom(TierModelResolver.fixed(MAPA))
                    .resolve(LLMResolutionRequest.builder(PLATAFORMA)
                            .tenantId("acme")
                            .tier("STRONG")
                            .stepPatch(LLMConfigPatch.builder().model("openai/gpt-4o").build())
                            .build());

            assertThat(r.model()).isEqualTo("openai/gpt-4o");
            assertThat(avisos()).anySatisfy(m -> assertThat(m)
                    .contains("tier=STRONG")
                    .contains("anthropic/claude-sonnet-4")
                    .contains("openai/gpt-4o"));
        }
    }

    @Nested
    @DisplayName("sem mapa para o tenant")
    class SemMapa {

        @Test
        @DisplayName("resolve o default E avisa — o silêncio era o defeito")
        void caiNoDefaultMasAvisa() {
            ResolvedLLMConfig r = resolvedorCom(TierModelResolver.NOOP)
                    .resolve(LLMResolutionRequest.builder(PLATAFORMA)
                            .tenantId("sem-mapa").tier("STRONG").build());

            assertThat(r.model())
                    .as("sem mapa, o comportamento anterior tem que continuar valendo")
                    .isEqualTo("google/gemini-2.5-flash-lite");
            assertThat(avisos())
                    .as("ficou dois dias sendo ignorado justamente por nao avisar")
                    .anySatisfy(m -> assertThat(m)
                            .contains("tier=STRONG")
                            .contains("NAO honrado"));
        }

        /**
         * Um resolvedor de produto que estoura não pode derrubar a execução: o
         * pior caso aceitável é cair no comportamento anterior, que funcionava.
         */
        @Test
        @DisplayName("resolvedor de tier que estoura não derruba a resolução")
        void resolvedorQueFalha() {
            TierModelResolver explode = (tenant, tier) -> {
                throw new IllegalStateException("storage fora do ar");
            };

            ResolvedLLMConfig r = resolvedorCom(explode)
                    .resolve(LLMResolutionRequest.builder(PLATAFORMA)
                            .tenantId("acme").tier("STRONG").build());

            assertThat(r.model()).isEqualTo("google/gemini-2.5-flash-lite");
            assertThat(avisos()).anySatisfy(m -> assertThat(m).contains("storage fora do ar"));
        }
    }

    @Nested
    @DisplayName("sem tier")
    class SemTier {

        @Test
        @DisplayName("comportamento anterior, intacto e silencioso")
        void nadaMuda() {
            ResolvedLLMConfig r = resolvedorCom(TierModelResolver.fixed(MAPA))
                    .resolve(LLMResolutionRequest.builder(PLATAFORMA)
                            .tenantId("acme")
                            .stepPatch(LLMConfigPatch.builder().model("openai/gpt-4o").build())
                            .build());

            assertThat(r.model()).isEqualTo("openai/gpt-4o");
            assertThat(avisos())
                    .as("quem nao pede tier nao pode pagar por ele — nem em log")
                    .isEmpty();
        }

        @Test
        @DisplayName("o construtor de compatibilidade continua existindo")
        void compatSemTier() {
            LLMResolutionRequest req = new LLMResolutionRequest(
                    "acme", PLATAFORMA, LLMConfigPatch.empty(), LLMConfigPatch.empty(),
                    LLMConfigPatch.empty(), LLMConfigPatch.empty());

            assertThat(req.tier()).isNull();
            assertThat(resolvedorCom(TierModelResolver.fixed(MAPA)).resolve(req).model())
                    .isEqualTo("google/gemini-2.5-flash-lite");
        }

        @Test
        @DisplayName("tier em branco é o mesmo que não ter tier")
        void tierEmBrancoNaoConta() {
            assertThat(LLMResolutionRequest.builder(PLATAFORMA).tier("   ").build().tier()).isNull();
        }
    }
}
