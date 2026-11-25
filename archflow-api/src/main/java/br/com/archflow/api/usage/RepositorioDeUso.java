package br.com.archflow.api.usage;

import java.time.Instant;
import java.util.List;

/**
 * Onde os {@link LancamentoDeUso} ficam. Em memória para dev; {@link JdbcRepositorioDeUso} quando
 * {@code archflow.persistence.jdbc.enabled=true} — o consumo do mês não pode sumir num restart.
 */
public interface RepositorioDeUso {

    /**
     * Grava o lançamento. Já existindo um com o mesmo id, ele é substituído pelo relato novo
     * ({@link LancamentoDeUso#atualizadoPor}): o contador relata o total acumulado, e somar de novo
     * contaria o mesmo gasto duas vezes.
     */
    void salvar(LancamentoDeUso lancamento);

    /** Os lançamentos registrados pela primeira vez em {@code [inicio, fim)}, de todos os tenants. */
    List<LancamentoDeUso> doPeriodo(Instant inicio, Instant fim);

    /** Os lançamentos de uma execução de fluxo, no tenant dela. */
    List<LancamentoDeUso> daExecucao(String tenantId, String execucaoDeFluxoId);
}
