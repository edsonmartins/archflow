import { useEffect } from 'react'
import { useAgent, useCopilotKit } from '@copilotkit/react-core/v2'
import { notifications } from '@mantine/notifications'

export const COPILOT_GENERATE_EVENT = 'archflow:copilot-generate'

/**
 * Fire-and-forget entry point for "build with AI": any component can ask
 * the app-wide copilot to run a prompt without opening the chat sidebar.
 * The agent's frontend tools (addNode / connectNodes / createFlow) act on
 * the canvas, so the user watches the flow being assembled in place.
 */
export function requestCopilotGeneration(prompt: string) {
    window.dispatchEvent(new CustomEvent(COPILOT_GENERATE_EVENT, { detail: { prompt } }))
}

/**
 * Bridges the DOM event above into the CopilotKit runtime. Must be mounted
 * inside the CopilotKitProvider (next to CopilotAppOperator) so it can reach
 * the shared agent and the tool-execution pipeline (copilotkit.runAgent runs
 * the same loop the chat input uses, including frontend-tool handling).
 */
export default function CopilotGenerateBridge() {
    const { agent } = useAgent({ agentId: 'archflow' })
    const { copilotkit } = useCopilotKit()

    useEffect(() => {
        const onGenerate = (e: Event) => {
            const prompt = (e as CustomEvent<{ prompt?: string }>).detail?.prompt
            if (!prompt || !agent) return
            agent.addMessage({ id: `gen-${Date.now()}`, role: 'user', content: prompt })
            void copilotkit.runAgent({ agent }).catch((err: unknown) => {
                notifications.show({
                    color: 'red',
                    message: err instanceof Error ? err.message : String(err),
                })
            })
        }
        window.addEventListener(COPILOT_GENERATE_EVENT, onGenerate)
        return () => window.removeEventListener(COPILOT_GENERATE_EVENT, onGenerate)
    }, [agent, copilotkit])

    return null
}
