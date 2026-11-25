/**
 * Minimal AG-UI client (ADR-0003 / design-0006): POSTs a RunAgentInput to the
 * archflow AG-UI endpoint and parses the SSE event stream into typed AG-UI events.
 *
 * Uses fetch + ReadableStream (not EventSource) because AG-UI runs are started
 * with a POST body and need an auth header. This is the reusable seam a CopilotKit
 * integration would sit on top of later.
 */

import { ApiError, authFetch, executionApi } from './api'

export interface AgUiEvent {
    type: string
    [key: string]: unknown
}

export interface AgUiRunInput {
    messages?: { role: string; content: string }[]
    state?: Record<string, unknown>
}

/**
 * Desfecho do Parar:
 * - `stopped`: não havia o que cancelar, ou o servidor aceitou o cancelamento;
 * - `byDisconnect`: o servidor recusou por permissão (403). Executar pede `workflow:execute`, mas o
 *   `POST /api/executions/{id}/cancel` pede `execution:manage`, que só o ADMIN tem — DESIGNER e
 *   EXECUTOR caem aqui a cada Parar. A execução para mesmo assim: o stream já foi fechado e o
 *   servidor cancela ao notar a desconexão (no próximo batimento do AgUiController ou evento), só
 *   que mais tarde. Dizer "pode continuar rodando" seria alarme falso e esconderia a causa real;
 * - `failed`: o servidor recusou por outro motivo ou não respondeu — a execução pode continuar
 *   rodando, e quem chamou precisa dizer isso ao usuário.
 */
export type StopOutcome = 'stopped' | 'byDisconnect' | 'failed'

/**
 * Para o run: fecha o stream e, se o RUN_STARTED já trouxe o id da execução e o run ainda não
 * terminou, cancela no servidor (POST /api/executions/{id}/cancel). Ver {@link StopOutcome}.
 */
export type StopAgUiRun = () => Promise<StopOutcome>

/**
 * Runs a workflow over AG-UI, invoking `onEvent` for each event. Returns a
 * function that stops the run (see {@link StopAgUiRun}).
 */
export function runAgUiWorkflow(
    workflowId: string,
    input: AgUiRunInput,
    onEvent: (event: AgUiEvent) => void,
): StopAgUiRun {
    const controller = new AbortController()
    // Fechar o fetch só avisa o servidor quando ele tenta escrever — e um passo longo (LLM) não
    // escreve nada até terminar: o passo ia até o fim e a execução ficava COMPLETED (RCHFLW-54).
    // Por isso o Parar também cancela pelo id que o RUN_STARTED trouxe.
    let runId: string | null = null
    let terminado = false
    const dispatch = (event: AgUiEvent) => {
        if (event.type === 'RUN_STARTED' && typeof event.runId === 'string') runId = event.runId
        if (event.type === 'RUN_FINISHED' || event.type === 'RUN_ERROR') terminado = true
        onEvent(event)
    }

    void (async () => {
        // authFetch, não fetch cru: além do Authorization (com refresh), ele manda o
        // X-Impersonate-Tenant ativo. Sem isso, o superadmin personificando o tenant B rodava
        // pelo editor como o próprio tenant, e a aprovação do fluxo ia para a fila errada —
        // enquanto o mesmo fluxo rodado pela lista caía no tenant B (RCHFLW-13).
        const response = await authFetch(`/ag-ui/workflows/${encodeURIComponent(workflowId)}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(input),
            signal: controller.signal,
        })
        // HTTP-level failures (401/500/...) never produce SSE frames — emit a
        // synthetic RUN_ERROR so callers can leave their "executing" state.
        if (!response.ok) {
            dispatch({ type: 'RUN_ERROR', message: `HTTP ${response.status} ${response.statusText}`.trim() })
            return
        }
        if (!response.body) {
            dispatch({ type: 'RUN_ERROR', message: 'Empty response stream' })
            return
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        for (;;) {
            const { done, value } = await reader.read()
            if (done) break
            // A Stop click aborts mid-read; don't dispatch buffered frames (e.g. a
            // late RUN_STARTED) afterwards, or the UI flips back to "executing".
            if (controller.signal.aborted) break
            buffer += decoder.decode(value, { stream: true })

            // SSE frames are separated by a blank line.
            const frames = buffer.split('\n\n')
            buffer = frames.pop() ?? ''
            for (const frame of frames) {
                const dataLine = frame.split('\n').find((l) => l.startsWith('data:'))
                if (!dataLine) continue
                const payload = dataLine.slice(5).trim()
                if (!payload) continue
                try {
                    dispatch(JSON.parse(payload) as AgUiEvent)
                } catch {
                    // ignore malformed frame
                }
            }
        }
    })().catch((err: unknown) => {
        // A user-initiated abort is not an error; anything else must reach the
        // caller as RUN_ERROR or the UI stays stuck in its executing state.
        if (controller.signal.aborted) return
        dispatch({
            type: 'RUN_ERROR',
            message: err instanceof Error ? err.message : String(err),
        })
    })

    return async () => {
        controller.abort()
        if (!runId || terminado) return 'stopped'
        const id = runId
        terminado = true
        try {
            await executionApi.cancel(id)
            return 'stopped'
        } catch (e) {
            return e instanceof ApiError && e.status === 403 ? 'byDisconnect' : 'failed'
        }
    }
}
