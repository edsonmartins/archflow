package br.com.archflow.api.usage;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.model.tenant.DefaultTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * O consumo de modelo medido, por tenant — o mesmo {@link ContadorDeUso} de todos os caminhos que
 * chamam um LLM (RCHFLW-75).
 *
 * <h2>Por que existe</h2>
 *
 * <p>O contador só era criado no VendaX e no invoke, e o que ele somava saía no resultado da
 * chamada e mais nada. Execuções pelo editor, pela lista e pelo AG-UI não contavam token nenhum, o
 * detalhe da execução não tinha uso, e as telas de uso mostravam números fixos no código (4,2M de
 * 5M) ou uma lista sempre vazia. Aqui o que foi medido fica guardado, e as telas somam isso.
 *
 * <h2>Quem relata</h2>
 *
 * <ul>
 *   <li>o motor, no fim de cada travessia de fluxo ({@link RegistroDeUsoListener}) — editor/AG-UI,
 *       lista/REST, retomada, MCP e o VendaX com fluxo;</li>
 *   <li>o laço de agente, no fim de cada execução ({@code McpAgentRunner}) — invoke, streaming,
 *       VendaX por nome, playground;</li>
 *   <li>o chat de agente do AG-UI.</li>
 * </ul>
 *
 * <p>Os dois primeiros podem relatar o mesmo contador (um nó de agente dentro de um fluxo). A linha
 * é a do contador ({@code execucaoId}) e o relato é do total acumulado, então o segundo substitui o
 * primeiro em vez de somar de novo.
 *
 * <p>O custo é o da {@link TabelaDePrecos} — sem preço declarado, {@code null}, nunca zero.
 * Relatar nunca lança: medir não pode derrubar a execução que gastou.
 */
public class RegistroDeUso {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeUso.class);

    private final RepositorioDeUso repositorio;
    private final TabelaDePrecos precos;
    private final Clock relogio;

    public RegistroDeUso(RepositorioDeUso repositorio, TabelaDePrecos precos) {
        this(repositorio, precos, Clock.systemDefaultZone());
    }

    /** Com relógio injetado — o mês de um lançamento é o do relógio, no fuso dele. */
    public RegistroDeUso(RepositorioDeUso repositorio, TabelaDePrecos precos, Clock relogio) {
        this.repositorio = Objects.requireNonNull(repositorio, "repositorio");
        this.precos = precos != null ? precos : TabelaDePrecos.vazia();
        this.relogio = Objects.requireNonNull(relogio, "relogio");
    }

    /**
     * Relata o total acumulado do contador. Sem nenhuma chamada a modelo, não há o que registrar.
     *
     * @param tenantId          o tenant que gastou; {@code null} é o tenant padrão
     * @param execucaoDeFluxoId a execução de fluxo a que o consumo pertence, ou {@code null}
     * @param origem            uma das {@code ORIGEM_*} de {@link LancamentoDeUso}
     */
    public void registrar(String tenantId, String execucaoDeFluxoId, String origem, ContadorDeUso contador) {
        if (contador == null) {
            return;
        }
        try {
            Optional<ContadorDeUso.Resumo> resumo = contador.resumo();
            if (resumo.isEmpty() || resumo.get().semTurnos()) {
                return;
            }
            ContadorDeUso.Resumo r = resumo.get();
            Instant agora = relogio.instant();
            repositorio.salvar(new LancamentoDeUso(contador.execucaoId(), DefaultTenant.orDefault(tenantId),
                    execucaoDeFluxoId, origem, r.modelo(), r.tokensEntrada(), r.tokensSaida(),
                    precos.custoEmCentavos(r), precos.custoEmReais(r), r.custoDeclaradoUsd(), r.turnos(),
                    agora, agora));
        } catch (RuntimeException e) {
            log.warn("Uso de modelo não registrado (tenant={}, execução={}): {}", tenantId, execucaoDeFluxoId,
                    e.toString());
        }
    }

    /** O consumo de uma execução de fluxo (somando as retomadas), ou vazio quando nada foi medido. */
    public Optional<UsoSomado> daExecucao(String tenantId, String execucaoDeFluxoId) {
        List<LancamentoDeUso> lancamentos =
                repositorio.daExecucao(DefaultTenant.orDefault(tenantId), execucaoDeFluxoId);
        return lancamentos.isEmpty() ? Optional.empty() : Optional.of(UsoSomado.de(lancamentos));
    }

    /** O consumo do tenant no mês; {@link UsoSomado#VAZIO} quando nada foi medido. */
    public UsoSomado doTenantNoMes(String tenantId, YearMonth mes) {
        return porTenantNoMes(mes).getOrDefault(DefaultTenant.orDefault(tenantId), UsoSomado.VAZIO);
    }

    /** O consumo de cada tenant que gastou no mês. Tenant sem medição não aparece. */
    public Map<String, UsoSomado> porTenantNoMes(YearMonth mes) {
        ZoneId fuso = relogio.getZone();
        Instant inicio = mes.atDay(1).atStartOfDay(fuso).toInstant();
        Instant fim = mes.plusMonths(1).atDay(1).atStartOfDay(fuso).toInstant();
        return repositorio.doPeriodo(inicio, fim).stream()
                .collect(Collectors.groupingBy(LancamentoDeUso::tenantId, LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), UsoSomado::de)));
    }

    /** O mês corrente no relógio do registro. */
    public YearMonth mesAtual() {
        return YearMonth.now(relogio);
    }
}
