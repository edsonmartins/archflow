package br.com.archflow.api.agent.mcp;

import br.com.archflow.api.agent.vendax.VendaxResult;
import br.com.archflow.decision.DecisionUsageListener;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("consumo das decisões no contador de uso")
class UsoDeDecisaoTest {

    @Test
    @DisplayName("o contador injetado no contexto é também o ouvinte de decisões")
    void ouvinteNoContexto() {
        ExecutionContext ctx = new DefaultExecutionContext("t1", null, null, null);
        ContadorDeUso uso = new ContadorDeUso();

        ContadorDeUso.injetar(ctx, uso);

        assertThat(DecisionUsageListener.from(ctx)).containsSame(uso);
    }

    @Test
    @DisplayName("cada decisão conta como turno, soma tokens e o custo declarado em USD")
    void somaDecisoes() {
        ContadorDeUso uso = new ContadorDeUso();

        uso.onDecision("TypeSafe", "jev-1.13", 439L, 70L, 0.000018);
        uso.onDecision("TypeSafe", "jev-1.13", 100L, 20L, 0.000007);

        var resumo = uso.resumo().orElseThrow();
        assertThat(resumo.turnos()).isEqualTo(2);
        assertThat(resumo.tokensEntrada()).isEqualTo(539);
        assertThat(resumo.custoDeclaradoUsd()).isEqualTo(0.000025);
        assertThat(VendaxResult.Uso.de(resumo, null).declaredCostUsd()).isEqualTo(0.000025);
    }

    @Test
    @DisplayName("custo não informado fica ausente — nunca zero")
    void semCusto() {
        ContadorDeUso uso = new ContadorDeUso();
        uso.onDecision("rules", "rules", null, null, null);
        uso.somar("openrouter", "m", 10, 5);

        assertThat(uso.resumo().orElseThrow().custoDeclaradoUsd()).isNull();
    }

    @Test
    @DisplayName("o relato do trecho depois do prazo desconta o custo declarado do trecho anterior")
    void menosDescontaOCusto() {
        ContadorDeUso uso = new ContadorDeUso();
        uso.onDecision("p", "m", 1L, 1L, 0.5);
        var ate = uso.resumo().orElseThrow();
        uso.onDecision("p", "m", 1L, 1L, 0.25);

        assertThat(uso.resumo().orElseThrow().menos(ate).custoDeclaradoUsd()).isEqualTo(0.25);
    }
}
