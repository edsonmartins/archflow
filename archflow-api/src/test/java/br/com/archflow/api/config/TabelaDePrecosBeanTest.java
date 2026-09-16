package br.com.archflow.api.config;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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

        TabelaDePrecos tabela = config.tabelaDePrecos(env);

        assertThat(tabela.custoEmCentavos(uso("openrouter/google/gemini-2.5-flash-lite")))
                .isEqualTo(51L);
    }

    @Test
    @DisplayName("sem configuração, tabela vazia e custo nulo")
    void semConfiguracao() {
        TabelaDePrecos tabela = config.tabelaDePrecos(new MockEnvironment());

        assertThat(tabela.custoEmCentavos(uso("openrouter/google/gemini-2.5-flash-lite"))).isNull();
    }
}
