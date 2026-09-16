package br.com.archflow.api.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tokens viram custo só quando alguém declarou o preço — e sem arredondar a favor de ninguém
 * além do teto.
 */
@DisplayName("TabelaDePrecos — de tokens a centavos")
class TabelaDePrecosTest {

    private static final String MODELO = "openrouter/google/gemini-2.5-flash-lite";

    private static TabelaDePrecos tabela(String entrada, String saida) {
        return new TabelaDePrecos(Map.of(MODELO,
                new TabelaDePrecos.Preco(new BigDecimal(entrada), new BigDecimal(saida))));
    }

    private static ContadorDeUso.Resumo uso(String modelo, Long entrada, Long saida) {
        return new ContadorDeUso.Resumo(modelo, entrada, saida);
    }

    /**
     * 812 tokens num modelo barato custam uma fração de centavo. Arredondar para baixo faria mil
     * consultas custarem zero para o teto do tenant.
     */
    @Test
    @DisplayName("fração de centavo arredonda para cima")
    void arredondaParaCima() {
        assertThat(tabela("10", "40").custoEmCentavos(uso(MODELO, 750L, 62L))).isEqualTo(1L);
    }

    @Test
    @DisplayName("entrada e saída com preços distintos")
    void precosDistintos() {
        // 1M de entrada a 50 + 500k de saída a 200 = 50 + 100
        assertThat(tabela("50", "200").custoEmCentavos(uso(MODELO, 1_000_000L, 500_000L)))
                .isEqualTo(150L);
    }

    @Test
    @DisplayName("sem preço declarado, custo nulo — não zero")
    void semPreco() {
        assertThat(TabelaDePrecos.vazia().custoEmCentavos(uso(MODELO, 750L, 62L))).isNull();
    }

    @Test
    @DisplayName("sem tokens informados, custo nulo")
    void semTokens() {
        assertThat(tabela("10", "40").custoEmCentavos(uso(MODELO, null, null))).isNull();
    }

    /** Somados, os tokens de dois modelos não dizem mais quanto cada um gastou. */
    @Test
    @DisplayName("mais de um modelo no mesmo consumo: custo nulo")
    void doisModelos() {
        assertThat(tabela("10", "40").custoEmCentavos(uso(MODELO + ",outro/modelo", 750L, 62L)))
                .isNull();
    }

    @Test
    @DisplayName("a chave não diferencia maiúsculas")
    void chaveSemCaixa() {
        assertThat(tabela("10", "40")
                .custoEmCentavos(uso("OpenRouter/Google/Gemini-2.5-Flash-Lite", 750L, 62L)))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("consumo zero custa zero — aí sim é um fato")
    void zeroEZero() {
        assertThat(tabela("10", "40").custoEmCentavos(uso(MODELO, 0L, 0L))).isEqualTo(0L);
    }

    /** Um preço pela metade subestima em silêncio; o modelo fica fora, e o custo sai nulo. */
    @Test
    @DisplayName("da configuração: preço incompleto ou ilegível fica fora da tabela")
    void configuracao() {
        TabelaDePrecos t = TabelaDePrecos.daConfiguracao(Map.of(
                MODELO, Map.of("entrada", "10", "saida", "40"),
                "so/entrada", Map.of("entrada", "10"),
                "ilegivel/modelo", Map.of("entrada", "dez", "saida", "40"),
                "negativo/modelo", Map.of("entrada", "-1", "saida", "40")));

        assertThat(t.custoEmCentavos(uso(MODELO, 750L, 62L))).isEqualTo(1L);
        assertThat(t.custoEmCentavos(uso("so/entrada", 750L, 62L))).isNull();
        assertThat(t.custoEmCentavos(uso("ilegivel/modelo", 750L, 62L))).isNull();
        assertThat(t.custoEmCentavos(uso("negativo/modelo", 750L, 62L))).isNull();
    }
}
