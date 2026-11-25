import { useEffect, useMemo, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { COPILOT_GENERATE_AGENT_ID } from './CopilotGenerateBridge'
import { notifications } from '@mantine/notifications'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { useAgentContext, useFrontendTool, type JsonSerializable } from '@copilotkit/react-core/v2'
import { api, workflowApi } from '../../services/api'
import { useFlowStore, type CanvasOp } from '../FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'
import { newNodeId } from '../FlowCanvas/nodePlacement'
import { NOT_EXECUTABLE_NODE_TYPES } from '../FlowCanvas/constants'
import {
    applyConfig, configKeysSummary, COPILOT_NODE_TYPES, isAnnotationType, parseConfigArg, validateNodeConfig,
    visibleConfig, REDACTED, type ConfigValueType,
} from './nodeConfigKeys'
import { confirmCopilotNodeRemoval } from './confirmCopilotRemoval'
import CopilotClockContext from './CopilotClockContext'

/** page key -> route. Exposed to the agent so it can navigate by name. */
const ROUTES: Record<string, string> = {
    dashboard: '/',
    workflows: '/workflows',
    editor: '/editor',
    executions: '/executions',
    conversations: '/conversations',
    approvals: '/approvals',
    templates: '/templates',
    // marketplace removido — decisão 0.2 do plano de homologação (rota desativada em App.tsx)
    'dynamic-workflow': '/playground/orchestration',
    'ag-ui': '/playground/ag-ui',
    copilot: '/playground/copilot',
}

type NavigateToParams = { page: string }
type RunWorkflowParams = { workflowId: string }
type ShowStatusParams = { message: string }
type CreateFlowParams = { name?: string }
type AddNodeParams = { nodeType: string; label?: string; config?: unknown }
type ConnectNodesParams = { sourceId: string; targetId: string }
type ConfigureNodeParams = { nodeId: string; config?: unknown; label?: unknown }
type RemoveNodeParams = { nodeId: string }
type RemoveEdgeParams = { sourceId: string; targetId: string }

/*
 * Resultados devolvidos ao agente. O CopilotKit serializa o retorno do handler (JSON) e o põe no histórico
 * como a mensagem role:tool daquela chamada; é por ele que o agente fica sabendo o id de cada nó criado.
 */
type CanvasNodeRef = { id: string; label: string; nodeType: string }
type CanvasEdgeRef = { sourceId: string; targetId: string; branch?: string }
/**
 * Recusa devolvida ao agente (e não lançada): um erro encerraria o laço sem followUp, e é o resultado com o
 * que vale (ids, arestas, chaves de config) que deixa o agente corrigir na rodada seguinte.
 */
export type ToolRefusal = {
    ok: false
    error: string
    nodes?: CanvasNodeRef[]
    edges?: CanvasEdgeRef[]
    nodeTypes?: readonly string[]
    allowedKeys?: Record<string, ConfigValueType>
    /** O usuário recusou a remoção de um nó dele (removeNode): o agente não deve insistir. */
    declined?: boolean
}
export type CreateFlowResult = { workflowId: string; name: string }
export type AddNodeResult = { ok: true; id: string; nodeType: string; label: string; queued: boolean } | ToolRefusal
export type ConnectNodesResult =
    | { ok: true; sourceId: string; targetId: string; queued: boolean }
    | { ok: false; error: string; nodes?: CanvasNodeRef[] }
export type ConfigureNodeResult =
    | { ok: true; nodeId: string; label: string; config: Record<string, unknown>; queued: boolean }
    | ToolRefusal
export type RemoveNodeResult = { ok: true; nodeId: string; removedEdges: number; queued: boolean } | ToolRefusal
export type RemoveEdgeResult =
    | { ok: true; sourceId: string; targetId: string; removed: number; queued: boolean }
    | ToolRefusal

/**
 * Por que a ligação não pode ser feita, se uma das pontas é uma anotação do canvas. Sticky note, moldura e
 * divisor não têm handles nem executam (o motor os ignora): uma aresta neles some do desenho e do fluxo.
 */
function annotationError(sourceId: string, targetId: string, nodes: CanvasNodeRef[]): string | undefined {
    const byId = new Map(nodes.map(n => [n.id, n]))
    for (const id of [sourceId, targetId]) {
        const node = byId.get(id)
        if (node && isAnnotationType(node.nodeType)) {
            return `node ${id} is a ${node.nodeType} (canvas annotation) and cannot be connected`
        }
    }
    return undefined
}

type AgentMessageLike = { id: string; role: string; toolCalls?: { id: string }[] }
/** O que o CopilotKit entrega como 2º argumento do handler (FrontendToolHandlerContext). */
type ToolContext = { toolCall?: { id: string }; agent?: { agentId?: string; messages?: AgentMessageLike[] } }

/**
 * Id da mensagem do assistente que trouxe esta chamada — identifica o lote. Todas as chamadas de um lote
 * vêm na mesma mensagem e o CopilotKit as executa em sequência; sem contexto (chamada direta), undefined.
 */
function batchOf(ctx?: ToolContext): string | undefined {
    const callId = ctx?.toolCall?.id
    if (!callId) return undefined
    return ctx?.agent?.messages?.find(m => m.role === 'assistant' && m.toolCalls?.some(c => c.id === callId))?.id
}

type QueuedAdd = Extract<CanvasOp, { kind: 'add' }>

/**
 * Os nós da fila que o próximo canvas vai criar: com destino (o fluxo que o createFlow acabou de criar),
 * só os marcados para ele — os de outro fluxo não existirão neste.
 */
function queuedAdds(): QueuedAdd[] {
    const { pendingCanvasOps, pendingCanvasTarget: target } = useFlowStore.getState()
    return pendingCanvasOps.filter((op): op is QueuedAdd => op.kind === 'add' && !!op.id
        && (!target || op.workflowId === target))
}

function queuedNodeRefs(): CanvasNodeRef[] {
    return queuedAdds().map(op => ({ id: op.id!, label: op.label ?? op.nodeType, nodeType: op.nodeType }))
}

/** Reescreve a fila sem passar pelo enqueue (que carimbaria o destino de novo). */
function rewriteQueue(fn: (ops: CanvasOp[]) => CanvasOp[]) {
    useFlowStore.setState(s => ({ pendingCanvasOps: fn(s.pendingCanvasOps) }))
}

/**
 * Makes the global copilot OPERATE the app (ADR-0003): exposes the current route
 * and the user's workflows (useAgentContext) and registers app-wide actions
 * (useFrontendTool) the agent can call — navigate between pages and run a
 * workflow. Mounted once inside the app-level CopilotKitProvider so the sidebar
 * (and these tools) persist across navigation.
 */
export default function CopilotAppOperator() {
    const navigate = useNavigate()
    const location = useLocation()
    const { t } = useTranslation()
    // Lote cujo createFlow falhou: o addNode/connectNodes do mesmo lote não podem cair no canvas que está aberto.
    const failedCreateBatchRef = useRef<string | undefined>(undefined)
    // Ids dos nós que o próprio copiloto criou (addNode, no canvas ou na fila). Só esses ele remove sem perguntar:
    // um nó que já estava no fluxo é do usuário, e o removeNode pede confirmação (confirmCopilotNodeRemoval).
    const createdByCopilotRef = useRef(new Set<string>())

    /**
     * Guarda comum das ferramentas de edição; lança para recusar. Um erro de handler não pede a rodada seguinte
     * (executeSpecificTool só devolve followUp sem erro), e o texto dele volta ao agente como resultado.
     */
    const guardEdit = (ctx: ToolContext | undefined, tool: string) => {
        const batch = batchOf(ctx)
        if (batch && batch === failedCreateBatchRef.current) {
            // O createFlow que abriria o fluxo novo falhou: sem awaitCanvasFor, estes nós iriam para o fluxo que
            // está aberto (ou para a fila sem destino, que o próximo fluxo aberto drenaria) e, com followUp, o
            // agente seguiria ligando-os ali. Recusar todos faz o lote terminar sem rodada seguinte.
            throw new Error(`${tool} not applied: createFlow failed in this same batch`)
        }
    }

    /** Toast vermelho e a recusa que volta ao agente (ver ToolRefusal). */
    const refuse = (tool: string, error: string, extra: Omit<ToolRefusal, 'ok' | 'error'> = {}): ToolRefusal => {
        notifications.show({ color: 'red', message: `${tool} failed — ${error}` })
        return { ok: false, error, ...extra }
    }
    // Shared workflow list (deduped fetch in the store) instead of a private
    // GET /workflows on every route change.
    const storeWorkflows = useWorkflowStore((s) => s.workflows)
    const fetchWorkflows = useWorkflowStore((s) => s.fetchWorkflows)
    const workflows = useMemo(
        () => storeWorkflows.map((w) => ({ id: w.id, name: w.name ?? w.id })),
        [storeWorkflows])
    // Live mirror of the editor canvas (store is kept in sync from React Flow).
    const canvasNodes = useFlowStore((s) => s.nodes)
    const canvasEdges = useFlowStore((s) => s.edges)
    // Fixo (a tabela é estática): calculado uma vez, e não a cada render do operador.
    const configKeys = useMemo(() => configKeysSummary(), [])

    useEffect(() => {
        fetchWorkflows().catch(() => { /* unauthenticated / empty */ })
    }, [fetchWorkflows])

    useAgentContext({ description: 'Current route (where the user is)', value: location.pathname })
    useAgentContext({
        description: 'Pages you can open with navigateTo (page key)',
        value: Object.keys(ROUTES),
    })
    useAgentContext({ description: "The user's workflows (id and name)", value: workflows })
    useAgentContext({
        description: 'Node types you can add to the canvas with addNode (nodeType)',
        value: [...COPILOT_NODE_TYPES],
    })
    useAgentContext({
        description: 'Config keys (key:type) you can set per node type, with addNode `config` or configureNode. '
            + 'json keys must follow the shape shown next to them. '
            + 'provider/model are plain ids (e.g. openai / gpt-4o-mini); omit them to inherit the flow default',
        value: configKeys,
    })
    useAgentContext({
        description: 'Nodes currently on the editor canvas (id, label, nodeType, config) — use these ids with '
            + `connectNodes, configureNode and removeNode. Credentials in the config are shown as ${REDACTED}: `
            + 'leave those keys out of configureNode to keep them',
        value: canvasNodes.map((n) => ({
            id: n.id, label: n.data?.label, nodeType: n.data?.nodeType,
            // A config veio do painel/JSON do fluxo: é dado serializável, só o tipo do FlowNodeData é aberto.
            config: visibleConfig(String(n.data?.nodeType ?? ''), n.data?.config) as JsonSerializable,
        })),
    })
    useAgentContext({
        description: 'Edges currently on the editor canvas (sourceId -> targetId) — use them with removeEdge',
        value: canvasEdges.map((e) => ({ sourceId: e.source, targetId: e.target })),
    })

    useFrontendTool({
        name: 'navigateTo',
        description: 'Open a page in the app. The `page` must be one of: ' + Object.keys(ROUTES).join(', ') + '.',
        parameters: z.object({ page: z.string().describe('the page key to open') }),
        followUp: false,
        handler: async ({ page }: NavigateToParams) => {
            const path = ROUTES[page] ?? (page.startsWith('/') ? page : undefined)
            if (!path) {
                notifications.show({ color: 'red', message: `Unknown page: ${page}` })
                return
            }
            notifications.show({ message: `Opening "${page}"…` })
            navigate(path)
        },
    })

    useFrontendTool({
        name: 'runWorkflow',
        description: 'Execute a workflow by its id, then open the executions page. '
            + 'Use the workflow ids from the context.',
        parameters: z.object({ workflowId: z.string().describe('the id of the workflow to run') }),
        followUp: false,
        handler: async ({ workflowId }: RunWorkflowParams) => {
            try {
                const res = await api.post<{ executionId: string; status: string }>(
                    `/workflows/${workflowId}/execute`, {})
                notifications.show({
                    color: 'teal',
                    title: 'Workflow started',
                    message: `${workflowId} → ${res.executionId}`,
                })
                navigate('/executions')
            } catch (e) {
                notifications.show({ color: 'red', title: 'Run failed', message: String(e) })
            }
        },
    })

    useFrontendTool({
        name: 'showStatus',
        description: 'Show a short status/notification message to the user.',
        parameters: z.object({ message: z.string().describe('the message to show') }),
        followUp: false,
        handler: async ({ message }: ShowStatusParams) => {
            notifications.show({ message: String(message) })
        },
    })

    // ── Canvas editing ──
    // As ferramentas de edição têm followUp (o padrão do CopilotKit): depois do lote o agente roda de novo e
    // lê o que cada uma devolveu. Com followUp:false o agente parava no primeiro lote — os nós saíam soltos,
    // porque o connectNodes precisa dos ids que só o resultado do addNode traz (RCHFLW-76).
    useFrontendTool({
        name: 'createFlow',
        description: 'Create a new, empty workflow and open it in the editor (mounts the canvas). '
            + 'Call this before addNode when no flow is open. Returns {workflowId, name}.',
        parameters: z.object({ name: z.string().optional().describe('optional name for the flow') }),
        handler: async ({ name }: CreateFlowParams, ctx?: ToolContext): Promise<CreateFlowResult> => {
            guardEdit(ctx, 'createFlow')
            // O "Gerar com IA" é disparado do editor de UM fluxo e monta aquele fluxo. Criar outro ali deixava o
            // fluxo que o usuário abriu vazio e levava tudo para um novo (RCHFLW-95). Recusar devolve o motivo ao
            // modelo, que segue adicionando os nós no canvas aberto.
            if (ctx?.agent?.agentId === COPILOT_GENERATE_AGENT_ID) {
                throw new Error('createFlow not applied: "Gerar com IA" builds the flow that is open in the editor. '
                    + 'Add the nodes to the current canvas with addNode.')
            }
            try {
                const wf = await workflowApi.create({
                    metadata: { name: name || 'New flow', description: '', version: '1.0.0', category: '', tags: [] },
                    steps: [],
                    configuration: {},
                })
                notifications.show({ color: 'teal', message: `Created flow "${wf.metadata?.name ?? wf.id}"` })
                // Com outro fluxo aberto, o canvas dele segue montado (e registrado) até o editor trocar de
                // rota: os addNode que o agente chama logo depois iriam para o fluxo ERRADO e sumiriam na
                // troca. Sem a API, eles entram na fila marcados para este fluxo, e só o canvas dele os
                // aplica — se ele não carregar, não aparecem no próximo fluxo que o usuário abrir.
                useFlowStore.getState().awaitCanvasFor(wf.id)
                navigate(`/editor/${wf.id}`)
                return { workflowId: wf.id, name: wf.metadata?.name ?? name ?? wf.id }
            } catch (e) {
                notifications.show({ color: 'red', message: `Create failed: ${String(e)}` })
                // Relançar sozinho não encerra o laço: o CopilotKit pede a rodada seguinte se QUALQUER
                // ferramenta do lote der certo com followUp, e o modelo costuma mandar createFlow + addNode no
                // mesmo lote. Por isso o lote é marcado, e o guardEdit recusa o addNode/connectNodes dele — sem
                // isso eles cairiam no fluxo que já está aberto e o agente seguiria ligando-os ali.
                failedCreateBatchRef.current = batchOf(ctx)
                throw e
            }
        },
    })

    useFrontendTool({
        name: 'addNode',
        description: 'Add a node to the workflow editor canvas. nodeType is one of the available node types. '
            + 'Returns {ok, id, nodeType, label}: the id is assigned by the canvas and is the ONLY valid way to '
            + 'refer to this node in connectNodes/configureNode/removeNode — never invent node ids. To build a '
            + 'flow, add the nodes first, then connect them using the returned ids. Optional `config` sets the '
            + 'node configuration right away (a JSON object using only the config keys listed for that node '
            + 'type). If no flow is open the node is queued and applied as soon as a flow is opened/created '
            + '(so you can call createFlow then addNode); the returned id stays valid. '
            + 'Returns {ok:false, error} for an unknown node type or config key: fix it and retry.',
        parameters: z.object({
            nodeType: z.string().describe('the node type to add, e.g. input, llm-chat, condition, output'),
            label: z.string().optional().describe('optional label for the node'),
            config: z.union([z.string(), z.record(z.string(), z.unknown())]).optional()
                .describe('optional JSON object with the node config, e.g. {"systemPrompt":"..."}'),
        }),
        handler: async ({ nodeType, label, config }: AddNodeParams, ctx?: ToolContext): Promise<AddNodeResult> => {
            guardEdit(ctx, 'addNode')
            // Um tipo fora da paleta virava, calado, um nó da categoria 'tool' (toast "Added") que o motor
            // não sabe executar: o fluxo parecia pronto e falhava com "component not found".
            if (!COPILOT_NODE_TYPES.includes(nodeType)) {
                // Tipo da paleta sem execução (RCHFLW-11): o motor recusaria o fluxo. Diz isso, em vez de
                // "desconhecido", para o agente trocar por um tipo que roda.
                const reason = NOT_EXECUTABLE_NODE_TYPES.has(nodeType)
                    ? `node type "${nodeType}" is not yet supported at run time`
                    : `unknown node type "${nodeType}"`
                return refuse('Add node', reason, { nodeTypes: COPILOT_NODE_TYPES })
            }
            const parsed = parseConfigArg(config)
            if (!parsed.ok) return refuse('Add node', parsed.error)
            let initial: Record<string, unknown> | undefined
            if (parsed.value) {
                const checked = validateNodeConfig(nodeType, parsed.value)
                if (!checked.ok) return refuse('Add node', checked.error, { allowedKeys: checked.allowedKeys })
                initial = Object.keys(checked.config.set).length ? checked.config.set : undefined
            }
            const canvas = useFlowStore.getState().canvasApi
            if (canvas) {
                const id = canvas.addNode({ nodeType, label, placement: 'free', ...(initial ? { config: initial } : {}) })
                createdByCopilotRef.current.add(id)
                notifications.show({ color: 'teal', message: `Added "${nodeType}" node (${id})` })
                return { ok: true, id, nodeType, label: label ?? nodeType, queued: false }
            }
            // O id é escolhido já na fila (e não na drenagem): é por ele que um connectNodes enfileirado
            // em seguida cita este nó, e sem ele todos os nós da fila saíam com o mesmo node-<ms>.
            const id = newNodeId()
            createdByCopilotRef.current.add(id)
            useFlowStore.getState().enqueueCanvasOp({
                kind: 'add', id, nodeType, label, ...(initial ? { config: initial } : {}),
            })
            notifications.show({
                color: 'blue',
                message: `Queued "${nodeType}" (${id}) — create or open a flow in the editor to apply it.`,
            })
            return { ok: true, id, nodeType, label: label ?? nodeType, queued: true }
        },
    })

    useFrontendTool({
        name: 'connectNodes',
        description: 'Connect two nodes on the editor canvas with an edge (source -> target). '
            + 'Use the ids returned by addNode (or the ids of the nodes already on the canvas, from the '
            + 'context). Annotations (sticky-note, group-frame, section-divider) cannot be connected. '
            + 'Returns {ok: true} or {ok: false, error, nodes}: on failure, retry with an id from `nodes`.',
        parameters: z.object({
            sourceId: z.string().describe('id of the source node, as returned by addNode'),
            targetId: z.string().describe('id of the target node, as returned by addNode'),
        }),
        handler: async ({ sourceId, targetId }: ConnectNodesParams, ctx?: ToolContext): Promise<ConnectNodesResult> => {
            guardEdit(ctx, 'connectNodes')
            const store = useFlowStore.getState()
            const canvas = store.canvasApi
            if (!canvas) {
                // Sem canvas, os nós conhecidos são os da fila: uma anotação enfileirada também não liga.
                const target = store.pendingCanvasTarget
                const queued = queuedNodeRefs()
                // Com destino (o fluxo que o createFlow acabou de criar), o fluxo é novo e vazio: os adds
                // enfileirados para ele são TODOS os ids válidos, e um id fora deles certamente não existirá.
                // Devolver ok:true aqui faria o agente resumir "fluxo ligado" e o erro só apareceria na
                // drenagem, num toast. Sem destino, o próximo canvas que abrir pode ser um fluxo existente
                // (com nós que a fila não conhece), então só a anotação é recusada.
                const unknown = target
                    ? [sourceId, targetId].filter(id => !queued.some(n => n.id === id))
                    : []
                const refused = unknown.length
                    ? `unknown node id: ${unknown.join(', ')}`
                    : annotationError(sourceId, targetId, queued)
                if (refused) {
                    notifications.show({ color: 'red', message: `Connect failed — ${refused}` })
                    return { ok: false, error: refused, nodes: queued }
                }
                store.enqueueCanvasOp({ kind: 'connect', sourceId, targetId })
                notifications.show({ color: 'blue', message: 'Queued connection — open a flow to apply it.' })
                return { ok: true, sourceId, targetId, queued: true }
            }
            const nodes = canvas.listNodes()
            const known = new Set(nodes.map(n => n.id))
            const unknown = [sourceId, targetId].filter(id => !known.has(id))
            // Devolvido ao agente (e não lançado): um erro encerraria o laço sem followUp, e é justamente o
            // resultado com os ids válidos que deixa o agente corrigir a ligação na rodada seguinte.
            const error = unknown.length
                ? `unknown node id: ${unknown.join(', ')}`
                : annotationError(sourceId, targetId, nodes)
            if (error || !canvas.connectNodes(sourceId, targetId)) {
                const message = error ?? 'the canvas refused the connection'
                notifications.show({ color: 'red', message: `Connect failed — ${message}` })
                return { ok: false, error: message, nodes }
            }
            notifications.show({ color: 'teal', message: `Connected ${sourceId} → ${targetId}` })
            return { ok: true, sourceId, targetId, queued: false }
        },
    })

    // RCHFLW-78: sem estas, todo nó gerado ficava com config {} (llm-chat sem modelo nem prompt, condition
    // sem expressão) e um pedido de "troque o modelo" ou "remova o nó X" só tinha o addNode — que duplicava.
    useFrontendTool({
        name: 'configureNode',
        description: 'Set the configuration and/or the label of a node on the editor canvas. `config` is a JSON '
            + 'object merged into the node config, using ONLY the config keys listed for that node type '
            + '(a null value resets that key to its default). Use the node ids returned by addNode or listed '
            + 'in the context. Returns {ok:true, config} with the resulting config, or {ok:false, error, '
            + 'allowedKeys}: then retry with the allowed keys. Nothing is applied when any key is invalid.',
        parameters: z.object({
            nodeId: z.string().describe('id of the node, as returned by addNode'),
            config: z.union([z.string(), z.record(z.string(), z.unknown())]).optional()
                .describe('JSON object with the config keys to set, e.g. {"systemPrompt":"...","temperature":0.2}'),
            label: z.string().optional().describe('optional new label for the node'),
        }),
        handler: async ({ nodeId, config, label }: ConfigureNodeParams, ctx?: ToolContext)
            : Promise<ConfigureNodeResult> => {
            guardEdit(ctx, 'configureNode')
            const tool = 'Configure node'
            const parsed = parseConfigArg(config)
            if (!parsed.ok) return refuse(tool, parsed.error)
            if (label !== undefined && (typeof label !== 'string' || !label.trim())) {
                return refuse(tool, 'label must be a non-empty string')
            }
            const newLabel = typeof label === 'string' ? label.trim() : undefined
            if (!parsed.value && newLabel === undefined) {
                return refuse(tool, 'nothing to change: pass config and/or label')
            }

            const canvas = useFlowStore.getState().canvasApi
            const nodes = canvas ? canvas.listNodes() : queuedNodeRefs()
            const node = nodes.find(n => n.id === nodeId)
            if (!node) {
                return refuse(tool, canvas
                    ? `unknown node id: ${nodeId}`
                    : `unknown node id: ${nodeId} (no flow is open in the editor: open the flow first)`, { nodes })
            }
            // Sem config (só o rótulo), não há o que validar — nem um tipo sem chaves conhecidas impede renomear.
            const checked = parsed.value ? validateNodeConfig(node.nodeType, parsed.value) : undefined
            if (checked && !checked.ok) return refuse(tool, checked.error, { allowedKeys: checked.allowedKeys })
            const patch = checked?.ok ? checked.config : { set: {}, unset: [] }

            if (canvas) {
                const result = canvas.configureNode(nodeId, { config: patch.set, unset: patch.unset, label: newLabel })
                if (!result) return refuse(tool, `unknown node id: ${nodeId}`, { nodes })
                notifications.show({ color: 'teal', message: `Configured "${newLabel ?? node.label}" (${nodeId})` })
                return {
                    ok: true, nodeId, label: newLabel ?? node.label,
                    config: visibleConfig(node.nodeType, result), queued: false,
                }
            }
            // Nó ainda na fila: a config entra no próprio op de criação (o nó só passa a existir na drenagem).
            let merged: Record<string, unknown> = {}
            rewriteQueue(ops => ops.map(op => {
                if (op.kind !== 'add' || op.id !== nodeId) return op
                merged = applyConfig(op.config, patch)
                return { ...op, config: merged, ...(newLabel !== undefined ? { label: newLabel } : {}) }
            }))
            notifications.show({ color: 'blue', message: `Queued configuration for "${newLabel ?? node.label}"` })
            return {
                ok: true, nodeId, label: newLabel ?? node.label,
                config: visibleConfig(node.nodeType, merged), queued: true,
            }
        },
    })

    useFrontendTool({
        name: 'removeNode',
        description: 'Remove a node, and every edge to or from it, from the editor canvas. Use it to fix a wrong '
            + 'or duplicated node YOU added (instead of adding another one), or when the user explicitly asks to '
            + 'remove a node. Do not remove nodes that were already in the flow on your own initiative: removing '
            + 'one asks the user to confirm, and {ok:false, declined:true} means the user said no — keep the node '
            + 'and do not ask again. Returns {ok:true, removedEdges} or {ok:false, error, nodes} for an unknown id.',
        parameters: z.object({ nodeId: z.string().describe('id of the node to remove') }),
        handler: async ({ nodeId }: RemoveNodeParams, ctx?: ToolContext): Promise<RemoveNodeResult> => {
            guardEdit(ctx, 'removeNode')
            const tool = 'Remove node'
            const canvas = useFlowStore.getState().canvasApi
            if (canvas) {
                const nodes = canvas.listNodes()
                const node = nodes.find(n => n.id === nodeId)
                if (!node) return refuse(tool, `unknown node id: ${nodeId}`, { nodes })
                if (!createdByCopilotRef.current.has(nodeId)
                    && !(await confirmCopilotNodeRemoval(t, { id: nodeId, label: node.label }))) {
                    // Recusa, não erro: o agente precisa ler que foi o usuário que disse não, para não insistir.
                    notifications.show({ color: 'orange', message: `Kept "${node.label}" (${nodeId})` })
                    return {
                        ok: false, declined: true,
                        error: `the user declined removing node ${nodeId}: it was already in the flow; keep it`,
                    }
                }
                // Relido depois da confirmação: com o modal aberto, o usuário pode ter apagado o nó ou saído do
                // editor (o canvas desmonta e a API dele deixa de valer).
                const live = useFlowStore.getState().canvasApi
                if (!live) return refuse(tool, `node ${nodeId} not removed: the flow was closed in the editor`)
                const liveNodes = live.listNodes()
                if (!liveNodes.some(n => n.id === nodeId)) {
                    return refuse(tool, `unknown node id: ${nodeId}`, { nodes: liveNodes })
                }
                const removedEdges = live.listEdges()
                    .filter(e => e.sourceId === nodeId || e.targetId === nodeId).length
                if (!live.removeNode(nodeId)) return refuse(tool, `unknown node id: ${nodeId}`, { nodes: liveNodes })
                notifications.show({ color: 'teal', message: `Removed "${node.label}" (${nodeId})` })
                return { ok: true, nodeId, removedEdges, queued: false }
            }
            const queued = queuedNodeRefs()
            if (!queued.some(n => n.id === nodeId)) {
                return refuse(tool, `unknown node id: ${nodeId} (no flow is open in the editor: open the flow first)`,
                    { nodes: queued })
            }
            // Na fila, o nó e as ligações pedidas para ele saem juntos — um connect órfão viraria toast de erro
            // na drenagem.
            let removedEdges = 0
            rewriteQueue(ops => ops.filter(op => {
                if (op.kind === 'add') return op.id !== nodeId
                const touches = op.sourceId === nodeId || op.targetId === nodeId
                if (touches) removedEdges++
                return !touches
            }))
            notifications.show({ color: 'blue', message: `Removed queued node ${nodeId}` })
            return { ok: true, nodeId, removedEdges, queued: true }
        },
    })

    useFrontendTool({
        name: 'removeEdge',
        description: 'Remove the edge(s) from sourceId to targetId on the editor canvas (e.g. to reconnect a node '
            + 'elsewhere). Returns {ok:true, removed} or {ok:false, error, edges} when there is no such edge.',
        parameters: z.object({
            sourceId: z.string().describe('id of the source node of the edge'),
            targetId: z.string().describe('id of the target node of the edge'),
        }),
        handler: async ({ sourceId, targetId }: RemoveEdgeParams, ctx?: ToolContext): Promise<RemoveEdgeResult> => {
            guardEdit(ctx, 'removeEdge')
            const tool = 'Remove edge'
            const canvas = useFlowStore.getState().canvasApi
            if (canvas) {
                const removed = canvas.removeEdge(sourceId, targetId)
                if (!removed) {
                    const edges = canvas.listEdges().map(({ sourceId: s, targetId: t, branch }) => (
                        { sourceId: s, targetId: t, ...(branch ? { branch } : {}) }))
                    return refuse(tool, `no edge ${sourceId} -> ${targetId}`, { edges })
                }
                notifications.show({ color: 'teal', message: `Removed edge ${sourceId} → ${targetId}` })
                return { ok: true, sourceId, targetId, removed, queued: false }
            }
            // Mesmo recorte do queuedAdds: com destino, só as ligações pedidas para o fluxo que vai abrir.
            const target = useFlowStore.getState().pendingCanvasTarget
            const queuedConnects = useFlowStore.getState().pendingCanvasOps
                .filter(op => op.kind === 'connect' && (!target || op.workflowId === target))
            const isMatch = (op: CanvasOp) => queuedConnects.includes(op)
                && op.kind === 'connect' && op.sourceId === sourceId && op.targetId === targetId
            const removed = queuedConnects.filter(isMatch).length
            if (!removed) {
                const edges = queuedConnects.flatMap(op => (op.kind === 'connect'
                    ? [{ sourceId: op.sourceId, targetId: op.targetId }] : []))
                return refuse(tool, `no edge ${sourceId} -> ${targetId} (no flow is open in the editor)`, { edges })
            }
            rewriteQueue(ops => ops.filter(op => !isMatch(op)))
            notifications.show({ color: 'blue', message: `Removed queued edge ${sourceId} → ${targetId}` })
            return { ok: true, sourceId, targetId, removed, queued: true }
        },
    })

    // Data e hora atuais (RCHFLW-98) num filho: o relógio vira a cada minuto sem renderizar o operador de novo.
    return <CopilotClockContext />
}
