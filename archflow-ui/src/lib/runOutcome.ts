import type { AgUiEvent } from '../services/agui-client'

/**
 * Desfecho de um run AG-UI de workflow.
 *
 * <p>RUN_FINISHED só diz que o run acabou, não que deu certo. O AgUiController manda RUN_ERROR apenas
 * quando o motor lança; um FlowResult normal sai como RUN_FINISHED com <code>result.status</code> =
 * nome do ExecutionStatus (COMPLETED, FAILED, CANCELLED, PAUSED). Um passo que falha sem aresta de
 * erro (ex.: LLM sem chave) dá FAILED; um nó de aprovação humana suspende o run com PAUSED. Ler só
 * o tipo do evento anunciava "terminou com sucesso" nos dois casos.</p>
 */
export type RunOutcome = 'completed' | 'failed' | 'cancelled' | 'paused' | 'unknown'

export function runOutcomeOf(event: AgUiEvent): RunOutcome {
    if (event.type === 'RUN_ERROR') return 'failed'
    if (event.type !== 'RUN_FINISHED') return 'unknown'
    const result = event.result as { status?: unknown } | null | undefined
    const status = typeof result?.status === 'string' ? result.status.toUpperCase() : undefined
    switch (status) {
        // Sem status: o contrato AG-UI antigo (e outros servidores) só manda RUN_FINISHED no sucesso.
        case undefined:
        case 'COMPLETED':
            return 'completed'
        case 'FAILED':
            return 'failed'
        // STOPPED é FlowStatus, não ExecutionStatus; aceito para não depender de qual dos dois chega.
        case 'CANCELLED':
        case 'STOPPED':
            return 'cancelled'
        case 'PAUSED':
        case 'AWAITING_APPROVAL':
            return 'paused'
        // Status desconhecido nunca vira "sucesso".
        default:
            return 'unknown'
    }
}
