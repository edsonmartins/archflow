package br.com.archflow.api.usage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** {@link RepositorioDeUso} em memória — dev e testes; some no restart. */
public final class InMemoryRepositorioDeUso implements RepositorioDeUso {

    private final ConcurrentHashMap<String, LancamentoDeUso> porId = new ConcurrentHashMap<>();

    @Override
    public void salvar(LancamentoDeUso lancamento) {
        porId.merge(lancamento.id(), lancamento, LancamentoDeUso::atualizadoPor);
    }

    @Override
    public List<LancamentoDeUso> doPeriodo(Instant inicio, Instant fim) {
        return porId.values().stream()
                .filter(l -> !l.registradoEm().isBefore(inicio) && l.registradoEm().isBefore(fim))
                .toList();
    }

    @Override
    public List<LancamentoDeUso> daExecucao(String tenantId, String execucaoDeFluxoId) {
        return porId.values().stream()
                .filter(l -> Objects.equals(tenantId, l.tenantId()))
                .filter(l -> execucaoDeFluxoId != null && execucaoDeFluxoId.equals(l.execucaoDeFluxoId()))
                .toList();
    }
}
