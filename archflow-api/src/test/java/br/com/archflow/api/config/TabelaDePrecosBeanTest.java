package br.com.archflow.api.config;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chave de preço é {@code provider/modelo}, com barras e pontos — exatamente os caracteres que o
 * binder do Spring usa para aninhar. Sem colchetes, {@code google/gemini-2.5-flash} viraria um
 * mapa dentro de outro, a tabela sairia vazia, e o custo sairia nulo sem nenhum aviso.
 */
@DisplayName("Bean da TabelaDePrecos")
class TabelaDePrecosBeanTest {

    private final ArchflowBeanConfiguration config = new ArchflowBeanConfiguration();

    private static ContadorDeUso.Resumo uso(String modelo) {
        return new ContadorDeUso.Resumo(modelo, 1_000_000L, 1_000_000L);
    }

    @Test
    @DisplayName("chave entre colchetes preserva barras e pontos")
    void chaveComColchetes() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.llm.precos[openrouter/google/gemini-2.5-flash-lite].entrada", "10")
                .withProperty("archflow.llm.precos[openrouter/google/gemini-2.5-flash-lite].saida", "40.5");

        TabelaDePrecos tabela = config.tabelaDePrecos(env, "");

        assertThat(tabela.custoEmCentavos(uso("openrouter/google/gemini-2.5-flash-lite")))
                .isEqualTo(51L);
    }

    @Test
    @DisplayName("sem configuração, tabela vazia e custo nulo")
    void semConfiguracao() {
        TabelaDePrecos tabela = config.tabelaDePrecos(new MockEnvironment(), "");

        assertThat(tabela.custoEmCentavos(uso("openrouter/google/gemini-2.5-flash-lite"))).isNull();
    }

    /** Na VPS a configuração vem por variável de ambiente, que não carrega barra nem ponto no nome. */
    @Test
    @DisplayName("forma em texto (ARCHFLOW_LLM_PRECOS), inclusive com ':' no id do modelo")
    void formaEmTexto() {
        TabelaDePrecos tabela = config.tabelaDePrecos(new MockEnvironment(),
                "openrouter/google/gemini-2.5-flash-lite=10,40.5 ; openrouter/meta/llama-3:free=0,0");

        assertThat(tabela.custoEmCentavos(uso("openrouter/google/gemini-2.5-flash-lite"))).isEqualTo(51L);
        assertThat(tabela.custoEmCentavos(uso("openrouter/meta/llama-3:free"))).isZero();
    }

    @Test
    @DisplayName("as duas formas juntas; em conflito, vale o texto")
    void duasFormas() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.llm.precos[a/b].entrada", "1")
                .withProperty("archflow.llm.precos[a/b].saida", "1")
                .withProperty("archflow.llm.precos[c/d].entrada", "5")
                .withProperty("archflow.llm.precos[c/d].saida", "5");

        TabelaDePrecos tabela = config.tabelaDePrecos(env, "a/b=100,100");

        assertThat(tabela.custoEmCentavos(uso("a/b"))).isEqualTo(200L);
        assertThat(tabela.custoEmCentavos(uso("c/d"))).isEqualTo(10L);
    }

    /**
     * A queda de produção de 17 a 21/09/2026. A variável que a documentação manda usar é, pelo
     * relaxed binding, também a propriedade {@code archflow.llm.precos} — e o binder tentava
     * converter o texto num mapa. Qualquer valor derrubava a subida. O teste acima não pegava porque
     * passa o texto direto ao método: ele nunca pôs a variável no ambiente, que é onde ela mora.
     */
    @Test
    @DisplayName("ARCHFLOW_LLM_PRECOS no ambiente: sobe, e o preço vale pelo texto")
    void variavelDeAmbienteNaoDerrubaASubida() {
        String valor = "openrouter/google/gemini-2.5-flash-lite=51.527,206.108";
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("ARCHFLOW_LLM_PRECOS", valor)));

        // É o que o application.yml faz: precos-texto: ${ARCHFLOW_LLM_PRECOS:}
        TabelaDePrecos tabela = config.tabelaDePrecos(env, env.getProperty("ARCHFLOW_LLM_PRECOS"));

        assertThat(tabela.custoEmCentavos(uso("openrouter/google/gemini-2.5-flash-lite")))
                .isEqualTo(258L);
    }

    /**
     * O compose repassa {@code ARCHFLOW_LLM_PRECOS: ${ARCHFLOW_LLM_PRECOS:-}}: sem a linha no .env,
     * a variável chega VAZIA — e vazia derrubava a subida do mesmo jeito. Comentar o preço no .env
     * não bastou para tirar produção do erro; foi preciso mexer no compose.
     */
    @Test
    @DisplayName("ARCHFLOW_LLM_PRECOS vazia no ambiente: sobe, sem preço")
    void variavelVaziaNaoDerrubaASubida() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("ARCHFLOW_LLM_PRECOS", "")));

        TabelaDePrecos tabela = config.tabelaDePrecos(env, "");

        assertThat(tabela.modelos()).isEmpty();
    }

    /** O mapa continua valendo ao lado da variável — são duas formas de declarar, não uma ou outra. */
    @Test
    @DisplayName("variável no ambiente e mapa por propriedade, juntos")
    void variavelEMapaJuntos() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("ARCHFLOW_LLM_PRECOS", "a/b=100,100")));
        env.getPropertySources().addFirst(new MapPropertySource("arquivo", Map.of(
                "archflow.llm.precos[c/d].entrada", "5",
                "archflow.llm.precos[c/d].saida", "5")));

        TabelaDePrecos tabela = config.tabelaDePrecos(env, env.getProperty("ARCHFLOW_LLM_PRECOS"));

        assertThat(tabela.custoEmCentavos(uso("a/b"))).isEqualTo(200L);
        assertThat(tabela.custoEmCentavos(uso("c/d"))).isEqualTo(10L);
    }

    /** Um erro de digitação apagaria o preço, e o custo sairia nulo — fora do teto — sem aviso. */
    @Test
    @DisplayName("forma em texto: entrada malformada fica fora, e é informada")
    void textoMalformado() {
        TabelaDePrecos.Leitura leitura = TabelaDePrecos.doTexto(
                "a/b=10,40;sem-preco;c/d=10;e/f=dez,40;;g/h=-1,2");

        assertThat(leitura.tabela().modelos()).containsExactly("a/b");
        assertThat(leitura.ignorados())
                .containsExactlyInAnyOrder("sem-preco", "c/d=10", "e/f", "g/h");
    }
}
