/**
 * Minimal AG-UI client (ADR-0003 / design-0006): POSTs a RunAgentInput to the
 * archflow AG-UI endpoint and parses the SSE event stream into typed AG-UI events.
 *
 * Uses fetch + ReadableStream (not EventSource) because AG-UI runs are started
 * with a POST body and need an auth header. This is the reusable seam a CopilotKit
 * integration would sit on top of later.
 */

export interface AgUiEvent {
    type: string
    [key: string]: unknown
}

export interface AgUiRunInput {
    messages?: { role: string; content: string }[]
    state?: Record<string, unknown>
}

/**
 * Runs a workflow over AG-UI, invoking `onEvent` for each event. Returns a
 * function that aborts the stream (cancels the run server-side on disconnect).
 */
export function runAgUiWorkflow(
    workflowId: string,
    input: AgUiRunInput,
    onEvent: (event: AgUiEvent) => void,
): () => void {
    const controller = new AbortController()

    void (async () => {
        const token = localStorage.getItem('archflow_token')
        const response = await fetch(`/ag-ui/workflows/${encodeURIComponent(workflowId)}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                ...(token ? { Authorization: `Bearer ${token}` } : {}),
            },
            body: JSON.stringify(input),
            signal: controller.signal,
        })
        if (!response.body) return

        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        for (;;) {
            const { done, value } = await reader.read()
            if (done) break
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
                    onEvent(JSON.parse(payload) as AgUiEvent)
                } catch {
                    // ignore malformed frame
                }
            }
        }
    })().catch(() => {
        // network/abort errors surface via the stream ending; callers see no more events
    })

    return () => controller.abort()
}
