package br.com.archflow.api.usage;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O registro guarda o total de cada contador uma vez só, soma por execução e por mês, e não
 * transforma "sem preço" em zero (RCHFLW-75).
 */
@DisplayName("RegistroDeUso")
class RegistroDeUsoTest {

    private final TabelaDePrecos precos = TabelaDePrecos.doTexto("openrouter/m=100,200").tabela();

    @Test
    @DisplayName("relatar o mesmo contador de novo substitui a linha — o gasto não é contado duas vezes")
    void sameCounterReplacesInsteadOfAdding() {
        var repositorio = new InMemoryRepositorioDeUso();
        var registro = new RegistroDeUso(repositorio, precos);
        var uso = new ContadorDeUso();
        uso.somar("openrouter", "openrouter/m", 1_000, 100);
        registro.registrar("acme", null, LancamentoDeUso.ORIGEM_AGENTE, uso);
        uso.somar("openrouter", "openrouter/m", 500, 50);

        registro.registrar("acme", "exec-1", LancamentoDeUso.ORIGEM_FLUXO, uso);

        UsoSomado doMes = registro.doTenantNoMes("acme", registro.mesAtual());
        assertThat(doMes.execucoes()).isEqualTo(1);
        assertThat(doMes.tokensEntrada()).isEqualTo(1_500);
        assertThat(doMes.tokensSaida()).isEqualTo(150);
        assertThat(registro.daExecucao("acme", "exec-1")).get().extracting(UsoSomado::tokens).isEqualTo(1_650L);
    }

    @Test
    @DisplayName("sem preço declarado o custo é nulo, nunca zero; a soma diz quantas ficaram de fora")
    void unknownPriceStaysNull() {
        var registro = new RegistroDeUso(new InMemoryRepositorioDeUso(), precos);
        var semPreco = new ContadorDeUso();
        semPreco.somar("openai", "openai/outro", 10, 10);
        registro.registrar("acme", "exec-1", LancamentoDeUso.ORIGEM_FLUXO, semPreco);

        UsoSomado so = registro.daExecucao("acme", "exec-1").orElseThrow();
        assertThat(so.custoCentavos()).isNull();
        assertThat(so.custoReais()).isNull();
        assertThat(so.execucoesSemPreco()).isEqualTo(1);

        var comPreco = new ContadorDeUso();
        comPreco.somar("openrouter", "openrouter/m", 1_000_000, 0);
        registro.registrar("acme", "exec-1", LancamentoDeUso.ORIGEM_FLUXO, comPreco);
        UsoSomado juntos = registro.daExecucao("acme", "exec-1").orElseThrow();
        assertThat(juntos.custoCentavos()).isEqualTo(100L);
        assertThat(juntos.custoReais()).isEqualTo(1.0);
        assertThat(juntos.execucoesSemPreco()).isEqualTo(1);
    }

    @Test
    @DisplayName("contador sem chamada a modelo não registra nada; a execução fica sem uso")
    void nothingCalledNothingRecorded() {
        var registro = new RegistroDeUso(new InMemoryRepositorioDeUso(), precos);

        registro.registrar("acme", "exec-1", LancamentoDeUso.ORIGEM_FLUXO, new ContadorDeUso());

        assertThat(registro.daExecucao("acme", "exec-1")).isEmpty();
        assertThat(registro.doTenantNoMes("acme", registro.mesAtual())).isEqualTo(UsoSomado.VAZIO);
    }

    @Test
    @DisplayName("o mês é o do primeiro registro; tenant e execução de outro tenant não se misturam")
    void groupsByMonthAndTenant() {
        var repositorio = new InMemoryRepositorioDeUso();
        var novembro = new RegistroDeUso(repositorio, precos,
                Clock.fixed(Instant.parse("2026-11-30T23:00:00Z"), ZoneOffset.UTC));
        var uso = new ContadorDeUso();
        uso.somar("openrouter", "openrouter/m", 10, 10);
        novembro.registrar("acme", "exec-1", LancamentoDeUso.ORIGEM_FLUXO, uso);

        var dezembro = new RegistroDeUso(repositorio, precos,
                Clock.fixed(Instant.parse("2026-12-01T01:00:00Z"), ZoneOffset.UTC));
        assertThat(dezembro.porTenantNoMes(YearMonth.of(2026, 11))).containsOnlyKeys("acme");
        assertThat(dezembro.porTenantNoMes(YearMonth.of(2026, 12))).isEmpty();
        assertThat(dezembro.daExecucao("globex", "exec-1")).isEmpty();
        assertThat(dezembro.doTenantNoMes(null, YearMonth.of(2026, 11))).as("nulo é o padrão, não acme")
                .isEqualTo(UsoSomado.VAZIO);
    }

    @Test
    @DisplayName("uma execução com uso antes e depois de uma aprovação conta como uma (RCHFLW-195 item 3)")
    void retomadaContaUmaExecucao() {
        var registro = new RegistroDeUso(new InMemoryRepositorioDeUso(), precos);
        // A retomada abre um contador novo: são duas linhas da mesma execução, ambas sem preço.
        var antes = new ContadorDeUso();
        antes.somar("openai", "openai/outro", 54, 3);
        registro.registrar("acme", "exec-13f1e7f0", LancamentoDeUso.ORIGEM_FLUXO, antes);
        var depois = new ContadorDeUso();
        depois.somar("openai", "openai/outro", 20, 5);
        registro.registrar("acme", "exec-13f1e7f0", LancamentoDeUso.ORIGEM_FLUXO, depois);
        var outra = new ContadorDeUso();
        outra.somar("openai", "openai/outro", 1, 1);
        registro.registrar("acme", "exec-1b1dadea", LancamentoDeUso.ORIGEM_FLUXO, outra);

        UsoSomado daExecucao = registro.daExecucao("acme", "exec-13f1e7f0").orElseThrow();
        assertThat(daExecucao.execucoes()).isEqualTo(1);
        assertThat(daExecucao.execucoesSemPreco()).isEqualTo(1);
        assertThat(daExecucao.tokens()).as("os tokens das duas travessias somam").isEqualTo(82);

        UsoSomado doMes = registro.doTenantNoMes("acme", registro.mesAtual());
        assertThat(doMes.execucoes()).isEqualTo(2);
        assertThat(doMes.execucoesSemPreco()).isEqualTo(2);
        assertThat(doMes.tokens()).isEqualTo(84);
    }
}
