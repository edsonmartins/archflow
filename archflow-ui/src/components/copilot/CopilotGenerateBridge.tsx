import { useEffect, useRef } from 'react'
import { useAgent, useCopilotKit } from '@copilotkit/react-core/v2'
import { notifications } from '@mantine/notifications'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useFlowStore } from '../FlowCanvas/store/useFlowStore'
import { hasRunBlockers, runBlockers, runBlockersMessage } from '../FlowCanvas/runReadiness'

export const COPILOT_GENERATE_EVENT = 'archflow:copilot-generate'

/**
 * Agente do "Gerar com IA", separado do agente do chat lateral ({@code archflow}). Mesmo endpoint, mesmas
 * ferramentas e o mesmo contexto da tela, mas uma conversa própria, zerada a cada geração (RCHFLW-95).
 *
 * <p>Antes o pedido entrava na conversa global do copiloto: com o histórico de outro fluxo, o modelo tentava
 * ligar ids de nós que não existiam mais, chamava createFlow e montava tudo num fluxo NOVO, deixando vazio o
 * que o usuário tinha aberto. Zerar a conversa global (o chat lateral) apagaria o que o usuário conversou;
 * uma conversa por geração não toca nela.</p>
 */
export const COPILOT_GENERATE_AGENT_ID = 'archflow-generate'

/**
 * Prefixo do id do toast "O copilot está montando seu fluxo". Quem dispara a geração (editor) mostra
 * o toast com um id derivado deste e o entrega em {@link requestCopilotGeneration}, para que a ponte
 * consiga fechá-lo quando a execução falha — senão o aviso de "montando" fica na tela ao lado do
 * erro, contradizendo-o.
 *
 * <p>Um id novo por geração, e não um fixo: reaproveitar o mesmo id exigiria `hide` + `show` no
 * mesmo tick, que o React junta num render só — o container do Mantine com aquela key não
 * desmonta e o toast novo herda o timer de autoClose do anterior.</p>
 */
export const COPILOT_GENERATE_NOTIFICATION_ID = 'archflow-copilot-generate'

/** Id do toast de "montando" de uma geração; único por chamada (ver o prefixo acima). */
export function newCopilotGenerateNotificationId(): string {
    return `${COPILOT_GENERATE_NOTIFICATION_ID}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * Códigos do CopilotKit (`CopilotKitCoreErrorCode`) que significam "a execução do agente falhou".
 * Ficam de fora os de conexão/transcrição/ferramenta: `agent_connect_failed`, por exemplo, pode
 * disparar ao montar o layout e viraria um toast em toda página.
 */
const RUN_ERROR_CODES = new Set([
    'agent_run_failed',
    'agent_run_failed_event',
    'agent_run_error_event',
    'agent_thread_locked',
])

/**
 * O erro é só o reflexo de um "Parar" pedido pelo usuário? O stop do chat chama `agent.abortRun`, que
 * aborta o fetch do HttpAgent; o @ag-ui/client converte esse AbortError num RUN_ERROR falso (code
 * `abort`, rawEvent = o AbortError) e o CopilotKit o repassa como `agent_run_error_event` — sem este
 * filtro, um cancelamento pedido viraria toast de falha. Mesmos critérios que o `connectAgent` do
 * CopilotKit usa para ignorar abortos.
 */
export function isCopilotAbort(error: unknown, context?: Record<string, unknown>): boolean {
    if (context?.runtimeErrorCode === 'abort') return true
    if (!(error instanceof Error)) return false
    return (
        error.name === 'AbortError' ||
        (error as { code?: unknown }).code === 'abort' ||
        error.message === 'Fetch is aborted' ||
        error.message === 'signal is aborted without reason'
    )
}

/** A mensagem do backend fala de chave de API (ex.: "API key is required for OpenAI")? */
const API_KEY_HINT = /api[\s_-]?key|chave/i

type Translate = (key: string) => string

/**
 * Monta título e mensagem do toast de erro de uma execução do copilot. Separado do componente
 * para ser testável sem o runtime do CopilotKit.
 *
 * <p>A mensagem do erro é concatenada em código, não interpolada pelo i18next: ela vem do
 * servidor e não deve passar pelo escape/formatação de tradução.</p>
 */
export function describeCopilotRunError(
    error: unknown,
    generating: boolean,
    t: Translate,
): { title: string; message: string } {
    const raw = error instanceof Error ? error.message : typeof error === 'string' ? error : ''
    const detail = raw.trim() || t('copilot.errors.unknown')
    const message = API_KEY_HINT.test(detail) ? `${detail} — ${t('copilot.errors.apiKeyHint')}` : detail
    return {
        title: t(generating ? 'copilot.errors.generateFailed' : 'copilot.errors.runFailed'),
        message,
    }
}

/**
 * Fire-and-forget entry point for "build with AI": any component can ask
 * the app-wide copilot to run a prompt without opening the chat sidebar.
 * The agent's frontend tools (createFlow / addNode / connectNodes / configureNode / removeNode /
 * removeEdge) act on the canvas, so the user watches the flow being assembled in place.
 */
export function requestCopilotGeneration(prompt: string, notificationId?: string) {
    window.dispatchEvent(new CustomEvent(COPILOT_GENERATE_EVENT, { detail: { prompt, notificationId } }))
}

/**
 * Bridges the DOM event above into the CopilotKit runtime. Must be mounted
 * inside the CopilotKitProvider (next to CopilotAppOperator) so it can reach
 * the shared agent and the tool-execution pipeline (copilotkit.runAgent runs
 * the same loop the chat input uses, including frontend-tool handling).
 *
 * <p>Também é quem mostra ao usuário as falhas de execução do agente. O `runAgent` do CopilotKit
 * não rejeita: um RUN_ERROR do backend (ex.: sem chave de LLM) só chega aos assinantes `onError`
 * do core — sem um, o CopilotKit apenas loga no console, o toast de "montando" fica na tela e o
 * chat não mostra resposta nenhuma. Vale também para execuções disparadas pelo chat lateral.</p>
 */
export default function CopilotGenerateBridge() {
    const { agent } = useAgent({ agentId: COPILOT_GENERATE_AGENT_ID })
    const { copilotkit } = useCopilotKit()
    const { t } = useTranslation()
    // A execução em andamento veio do "Gerar com IA" (e não do chat)? Define o título do erro.
    const generatingRef = useRef(false)
    // Id do toast de "montando" da geração em andamento, para fechá-lo se ela falhar.
    const notificationIdRef = useRef<string | undefined>(undefined)
    // Um mesmo RUN_ERROR costuma chegar duas vezes (evento + falha da execução): um toast por execução.
    const reportedRef = useRef(false)
    // A geração em andamento foi parada pelo usuário: termina sem erro, mas também sem aviso de "montado".
    const abortedRef = useRef(false)
    const tRef = useRef<Translate>(t)
    tRef.current = t

    useEffect(() => {
        const report = (error: unknown) => {
            if (reportedRef.current) return
            reportedRef.current = true
            const generating = generatingRef.current
            if (generating && notificationIdRef.current) notifications.hide(notificationIdRef.current)
            notifications.show({
                color: 'red',
                autoClose: 10000,
                ...describeCopilotRunError(error, generating, tRef.current),
            })
        }

        const { unsubscribe } = copilotkit.subscribe({
            // Disparado pelas execuções de topo do runAgent (as de acompanhamento, após uma
            // ferramenta do front, continuam a mesma execução e não zeram o "já avisei") e também
            // pelo connectAgent, que só reconecta a uma execução — zerar ali é inofensivo.
            onAgentRunStarted: () => {
                reportedRef.current = false
            },
            onError: ({ code, error, context }) => {
                if (isCopilotAbort(error, context)) {
                    abortedRef.current = true
                    return
                }
                if (!RUN_ERROR_CODES.has(code)) return
                report(error)
            },
        })

        const onGenerate = (e: Event) => {
            const detail = (e as CustomEvent<{ prompt?: string; notificationId?: string }>).detail
            const prompt = detail?.prompt
            if (!prompt || !agent) return
            // Conversa nova a cada geração: só o pedido e o que a tela mostra agora (o fluxo aberto).
            agent.setMessages([])
            agent.threadId = `gen-thread-${Date.now()}`
            agent.addMessage({ id: `gen-${Date.now()}`, role: 'user', content: prompt })
            generatingRef.current = true
            const notificationId = detail?.notificationId
            notificationIdRef.current = notificationId
            reportedRef.current = false
            abortedRef.current = false
            void copilotkit
                .runAgent({ agent })
                .then(() => {
                    if (reportedRef.current || abortedRef.current) return
                    // O resumo do modelo ia para o chat lateral; agora a conversa é só desta geração, então ele
                    // aparece aqui — o usuário vê o que o copiloto diz ter feito ao lado do canvas montado.
                    if (notificationId) notifications.hide(notificationId)
                    // O agente pode desistir de corrigir uma condição que as ferramentas recusaram (ou o fluxo já
                    // tinha uma): o fluxo montado não roda. Dizer "montado" aí seria o silêncio do RCHFLW-116 — o
                    // aviso nomeia o nó e o campo, como o Executar do editor, junto com o resumo do copiloto.
                    const invalid = generatedFlowProblems(tRef.current as unknown as TFunction)
                    if (invalid) {
                        notifications.show({
                            color: 'orange',
                            autoClose: false,
                            title: tRef.current('editor.ai.doneInvalid'),
                            message: [resumoDaGeracao(agent.messages), invalid].filter(Boolean).join(' — '),
                        })
                        return
                    }
                    notifications.show({
                        color: 'teal',
                        autoClose: 12000,
                        title: tRef.current('editor.ai.done'),
                        message: resumoDaGeracao(agent.messages) || tRef.current('editor.ai.doneMsg'),
                    })
                })
                .catch((error: unknown) => {
                    if (!isCopilotAbort(error)) report(error)
                })
                .finally(() => {
                    generatingRef.current = false
                    notificationIdRef.current = undefined
                })
        }
        window.addEventListener(COPILOT_GENERATE_EVENT, onGenerate)
        return () => {
            window.removeEventListener(COPILOT_GENERATE_EVENT, onGenerate)
            unsubscribe()
        }
    }, [agent, copilotkit])

    return null
}

/**
 * O que impede o fluxo montado de executar — a mesma regra do Executar do editor (runBlockers), em texto para o
 * aviso; vazio quando nada impede. Inclui a condição sem expressão: quando o configureNode recusa a expressão e o
 * agente desiste, o nó fica sem ela, e isso também não pode sair como "fluxo montado".
 */
export function generatedFlowProblems(t: TFunction): string {
    const snapshot = useFlowStore.getState().getCanvasSnapshot()
    const steps = snapshot.steps.map(s => ({
        id: s.id, label: s.label, componentId: s.componentId, type: s.type, config: s.config,
    }))
    // Com as arestas, como o Executar: o Gerar com IA que renomeia um caso ("comercial" → "vendas") e deixa a aresta
    // no nome antigo é avisado ao terminar, e não só quando alguém executa (RCHFLW-147).
    const blockers = runBlockers(steps, snapshot.connections)
    return hasRunBlockers(blockers) ? runBlockersMessage(t, blockers) : ''
}

/** O último texto do assistente na conversa da geração (o resumo que o prompt pede), limitado para um aviso. */
function resumoDaGeracao(messages: ReadonlyArray<{ role?: string; content?: unknown }>): string {
    for (let i = messages.length - 1; i >= 0; i--) {
        const m = messages[i]
        if (m.role === 'assistant' && typeof m.content === 'string' && m.content.trim()) {
            const texto = m.content.trim()
            return texto.length > 400 ? `${texto.slice(0, 400)}…` : texto
        }
    }
    return ''
}
