package br.com.archflow.api.usage;

import java.time.Instant;

/**
 * O consumo de modelo de UMA execução, como ficou registrado — uma linha do {@link RegistroDeUso}.
 *
 * <p>A chave é o {@code execucaoId} do {@code ContadorDeUso} que mediu: o mesmo contador relatado
 * de novo (o laço de agente no fim de cada volta, o fluxo no fim da travessia) substitui a linha,
 * em vez de somar outra vez o que já estava somado.
 *
 * @param id                 o {@code execucaoId} do contador
 * @param tenantId           o tenant que gastou
 * @param execucaoDeFluxoId  a execução de fluxo (o id do registro em {@code /api/executions}) a que o
 *                           consumo pertence; {@code null} fora de fluxo (invoke, AG-UI de agente,
 *                           VendaX por nome)
 * @param origem             de onde veio ({@link #ORIGEM_FLUXO}, {@link #ORIGEM_AGENTE},
 *                           {@link #ORIGEM_AG_UI}, {@link #ORIGEM_ORQUESTRACAO})
 * @param modelo             {@code provider/modelo}; mais de um separados por vírgula
 * @param tokensEntrada      {@code null} quando o provedor não informou uso em turno nenhum
 * @param tokensSaida        idem
 * @param custoCentavos      custo pela tabela de preços, arredondado para cima; {@code null} = sem preço
 * @param custoReais         o mesmo custo, sem arredondar; {@code null} = sem preço
 * @param custoDeclaradoUsd  soma do custo que o provedor declarou; {@code null} se algum turno não o trouxe
 * @param turnos             chamadas ao modelo
 * @param registradoEm       quando a execução foi registrada pela primeira vez — é o que decide o mês
 * @param atualizadoEm       o último relato
 */
public record LancamentoDeUso(String id, String tenantId, String execucaoDeFluxoId, String origem,
                              String modelo, Long tokensEntrada, Long tokensSaida, Long custoCentavos,
                              Double custoReais, Double custoDeclaradoUsd, Integer turnos,
                              Instant registradoEm, Instant atualizadoEm) {

    /** Um fluxo executado pelo motor (editor, lista/REST, retomada, MCP, VendaX com fluxo). */
    public static final String ORIGEM_FLUXO = "flow";
    /** Um laço de agente fora de fluxo (invoke, streaming, VendaX por nome, playground). */
    public static final String ORIGEM_AGENTE = "agent";
    /** O chat de agente do AG-UI ({@code POST /ag-ui/agent}). */
    public static final String ORIGEM_AG_UI = "ag-ui";
    /**
     * Uma orquestração dinâmica fora de fluxo ({@code POST /api/orchestration/run}): planejador e
     * agentes-trabalhadores. Dentro de um fluxo (passo {@code ORCHESTRATE}) a origem é a do fluxo.
     */
    public static final String ORIGEM_ORQUESTRACAO = "orchestration";

    /** O mesmo lançamento com um relato mais novo: o primeiro registro e a execução de fluxo ficam. */
    public LancamentoDeUso atualizadoPor(LancamentoDeUso novo) {
        return new LancamentoDeUso(id, tenantId,
                novo.execucaoDeFluxoId != null ? novo.execucaoDeFluxoId : execucaoDeFluxoId,
                // Um fluxo que contém o laço é a origem que diz mais: o laço relata antes do fluxo.
                ORIGEM_FLUXO.equals(origem) ? origem : novo.origem,
                novo.modelo, novo.tokensEntrada, novo.tokensSaida, novo.custoCentavos, novo.custoReais,
                novo.custoDeclaradoUsd, novo.turnos, registradoEm, novo.atualizadoEm);
    }
}
