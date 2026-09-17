package br.com.archflow.api.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** O contador de uma execução: o id, o detalhamento e o recorte depois do prazo. */
@DisplayName("ContadorDeUso")
class ContadorDeUsoTest {

    @Test
    @DisplayName("uma execução, um id — o mesmo em todo resumo, e diferente entre execuções")
    void execucaoId() {
        ContadorDeUso a = new ContadorDeUso();
        a.somar("openrouter", "openrouter/m", 10, 2);
        String primeiro = a.resumo().orElseThrow().execucaoId();
        a.somar("openrouter", "openrouter/m", 10, 2);

        assertThat(a.resumo().orElseThrow().execucaoId()).isEqualTo(primeiro).isEqualTo(a.execucaoId());
        assertThat(new ContadorDeUso().execucaoId()).isNotEqualTo(primeiro);
    }

    @Test
    @DisplayName("sem turno de modelo, nada a relatar")
    void semTurno() {
        ContadorDeUso c = new ContadorDeUso();
        c.somarChamadaDeTool();

        assertThat(c.resumo()).isEmpty();
    }

    @Test
    @DisplayName("detalha provedor, entrada, saída, turnos, tools e duração")
    void detalhamento() {
        AtomicLong agora = new AtomicLong(1_000_000_000L);
        ContadorDeUso c = new ContadorDeUso(agora::get);
        c.somar("openrouter", "openrouter/google/gemini-2.5-flash-lite", 600, 40);
        c.somarChamadaDeTool();
        c.somar("openrouter", "openrouter/google/gemini-2.5-flash-lite", null, null);
        c.somar("openrouter", "openrouter/google/gemini-2.5-flash-lite", 150, 22);
        agora.addAndGet(1_500_000_000L);

        ContadorDeUso.Resumo r = c.resumo().orElseThrow();

        assertThat(r.provedor()).isEqualTo("openrouter");
        assertThat(r.tokensEntrada()).isEqualTo(750);
        assertThat(r.tokensSaida()).isEqualTo(62);
        assertThat(r.tokens()).isEqualTo(812);
        assertThat(r.turnos())
                .as("o turno sem uso informado também foi uma chamada ao modelo")
                .isEqualTo(3);
        assertThat(r.chamadasDeTool()).isEqualTo(1);
        assertThat(r.duracaoMs()).isEqualTo(1500);
    }

    /**
     * O ERROR por prazo sai com o gasto até o prazo; o relato posterior leva só a diferença. Somar o
     * total de novo contaria o trecho anterior duas vezes.
     */
    @Test
    @DisplayName("menos: só o que foi gasto depois do retrato anterior")
    void menos() {
        AtomicLong agora = new AtomicLong(0);
        ContadorDeUso c = new ContadorDeUso(agora::get);
        c.somar("openrouter", "openrouter/m", 600, 40);
        agora.set(20_000_000_000L);
        ContadorDeUso.Resumo noPrazo = c.resumo().orElseThrow();

        c.somar("openrouter", "openrouter/m", 150, 22);
        c.somarChamadaDeTool();
        agora.set(23_000_000_000L);
        ContadorDeUso.Resumo depois = c.resumo().orElseThrow().menos(noPrazo);

        assertThat(depois.execucaoId()).isEqualTo(noPrazo.execucaoId());
        assertThat(depois.tokensEntrada()).isEqualTo(150);
        assertThat(depois.tokensSaida()).isEqualTo(22);
        assertThat(depois.tokens()).isEqualTo(172);
        assertThat(depois.turnos()).isEqualTo(1);
        assertThat(depois.chamadasDeTool()).isEqualTo(1);
        assertThat(depois.duracaoMs()).isEqualTo(3000);
        assertThat(depois.semTurnos()).isFalse();
    }

    @Test
    @DisplayName("nada gasto depois do prazo: sem turnos, nada a relatar")
    void nadaDepois() {
        ContadorDeUso c = new ContadorDeUso();
        c.somar("openrouter", "openrouter/m", 600, 40);
        ContadorDeUso.Resumo noPrazo = c.resumo().orElseThrow();

        assertThat(c.resumo().orElseThrow().menos(noPrazo).semTurnos()).isTrue();
    }

    @Test
    @DisplayName("menos de nulo é o próprio resumo")
    void menosNulo() {
        ContadorDeUso c = new ContadorDeUso();
        c.somar("openrouter", "openrouter/m", 1, 1);
        ContadorDeUso.Resumo r = c.resumo().orElseThrow();

        assertThat(r.menos(null)).isSameAs(r);
    }
}
