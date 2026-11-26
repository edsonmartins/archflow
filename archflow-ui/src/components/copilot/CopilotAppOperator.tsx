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
import { branchOptions } from '../FlowCanvas/branches'
import {
    CONTROL_EXPRESSION_FIELD, USER_MESSAGE_FIELD, controlExpressionFindings, describeFindingForAgent, flowRefsOf,
    userMessageFindings,
    type ExpressionProblem,
} from '../FlowCanvas/controlExpressions'
import {
    decisionAutoUnreachable, decisionConfigFindings, decisionModelMissing, decisionProviderOf,
    describeDecisionFindingForAgent, type DecisionFinding,
} from '../FlowCanvas/decisionReadiness'
import {
    applyConfig, configKeysSummary, COPILOT_NODE_TYPES, isAnnotationType, parseConfigArg, validateNodeConfig,
    visibleConfig, REDACTED, type ConfigValueType, type ValidatedConfig,
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
type AddNodeParams = { nodeType: string; label?: string; config?: unknown; insertBefore?: string }
type ConnectNodesParams = { sourceId: string; targetId: string; branch?: string }
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
export type AddNodeResult =
    | {
        ok: true; id: string; nodeType: string; label: string; queued: boolean
        /** Com insertBefore: o nó antes do qual entrou e quantas arestas passaram a chegar nele. */
        insertedBefore?: string; rewiredEdges?: number
    }
    | ToolRefusal
export type ConnectNodesResult =
    | { ok: true; sourceId: string; targetId: string; branch?: string; queued: boolean }
    | { ok: false; error: string; nodes?: CanvasNodeRef[]; branches?: string[] }
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
 * Como classificar e ramificar, para o agente (RCHFLW-116). Na 1ª rodada ele escrevia `ticket.urgente == true` depois
 * de um llm-chat; na 2ª, montou um decision sem provider nem model e ramificou por AUTO/ESCALATE. As duas falharam em
 * silêncio: sem model o http-decisions (o padrão) nem decide e, com onError ESCALATE, todo pedido escala; e a rota é a
 * CONFIANÇA da resposta (RoutePolicy), não a categoria — um pedido comum e um urgente, respondidos com a mesma
 * confiança, seguem a mesma rota. O ramo vem da resposta, que o DecisionComponent devolve em `answers` (montar), e a
 * rota fica como portão de confiança. Os exemplos usam só campos que o componente grava e a gramática do avaliador
 * (ConditionEvaluator / controlExpressions); o provedor é o `llm`, o único que roda sem um model que ninguém tem como
 * escolher (não há catálogo de modelos de decisão), e cujo teto de confiança (0,89) nunca chega ao AUTO padrão (0,9).
 */
const EXPRESSION_RULES: string[] = [
    'To classify and then branch, use a decision node and branch on its ANSWER with a condition or switch node right '
        + 'after it. The decision route (AUTO/REVIEW/ESCALATE) is the CONFIDENCE of the answer, not the category: an '
        + 'urgent and an ordinary request answered with the same confidence get the same route, so never branch the '
        + 'categories on the route.',
    'Decision provider: set "provider":"llm" (the platform LLM as classifier) and leave "model" out to inherit the '
        + 'platform model. Do not use http-decisions or langchain4j-decisions unless the user gave you a decision model '
        + 'id: they refuse to run without "model" and there is no model catalog to pick one from — never invent a model. '
        + 'With provider llm the confidence is capped at 0.89, below thresholds.auto 0.9: the AUTO route never happens, '
        + 'so an AUTO edge is never followed.',
    'Decision output, under its node id D: ${D.answers.<qid>.result} (noul: true/false), ${D.answers.<qid>.choice} '
        + '(choice: the option), ${D.answers.<qid>.score} (score: the level index), ${D.answers.<qid>.confidence}; '
        + '${D.decision} is the value of the "primary" question (the option of a choice — but the PROBABILITY of a noul, '
        + 'so read a noul through answers.<qid>.result); ${D.route} and ${D.confidence} come from the minimum confidence '
        + 'over the "gate" questions (default: all). A failed decision has route ESCALATE, decision null and no answers.',
    'Example — "send urgent requests to a human, answer the others with an LLM": decision D with config '
        + '{"provider":"llm","questions":{"assunto":{"type":"choice","instructions":"Qual o assunto do pedido?",'
        + '"criteria":{"financeiro":"cobrança, pagamento","suporte_tecnico":"defeito, sistema fora do ar",'
        + '"outro":"qualquer outro assunto"}},"urgente":{"type":"noul","instructions":"O pedido é urgente?",'
        + '"criteria":{"true":"algo parado ou fora do ar, prazo vencendo","false":"dúvida ou pedido que pode esperar"}}},'
        + '"primary":"assunto","gate":["urgente"]}; then a condition C with conditionExpression '
        + '"${D.answers.urgente.result} == true || ${D.route} == \'ESCALATE\'"; connectNodes D -> C without branch, '
        + 'C -> approval with branch "true", C -> llm-chat with branch "false". The ESCALATE part sends a failed or '
        + 'low-confidence (< 0.5) decision to the human: a failure never becomes the ordinary path. That llm-chat gets '
        + '{"userMessage":"${<inputNodeId>}"} (see the llm-chat userMessage rule).',
    'Example by category: primary = a choice question, then a switch with switchExpression "${D.decision}" and '
        + 'switchCases = the options, one per line; connect one edge per case (branch = the case) and the "default" '
        + 'edge to the human (a failed decision has decision null and falls in default). The decision route edges '
        + '(connectNodes branch AUTO/REVIEW/ESCALATE from the decision itself) are only a confidence gate.',
    'conditionExpression grammar: <operand> <op> <operand> with op ==, !=, >, >=, <, <= or contains, joined by && or '
        + "||, no parentheses. Each operand is ${path}, quoted text ('urgent'), a number or true/false — never a bare "
        + 'word (ticket.urgente == true compares the literal text and never branches).',
    'A path starts with the id (from addNode, never the label) of a node that runs before: ${<nodeId>} is its whole '
        + 'output, ${<nodeId>.<field>} a field of it. llm-chat output is the reply TEXT, with no fields: compare '
        + "${<llmId>} contains 'URGENT' (tell it in systemPrompt to answer with that word), or use a decision node. "
        + 'A top-level key of the run input JSON is ${<key>}; a nested field of it, ${<key>.<field>}, needs <key> '
        + 'declared in the input node ioFields. ${input} is the output of the previous step.',
    'llm-chat userMessage: without it, an llm-chat receives the previous step output as the user message. After a '
        + 'decision, condition or switch that output is the classification JSON (condition and switch pass on what '
        + 'they received), and the LLM answers the JSON instead of the customer. So an llm-chat that answers after a '
        + 'decision/condition/switch must get config {"userMessage":"${<inputNodeId>}"} — the input node output is the '
        + 'message the run received. userMessage is text with ${path} references, same paths as conditions: text '
        + 'outside ${} is sent as is ("Customer: ${<inputNodeId>}"), ${input} is the previous step output, a non-text '
        + 'value is sent as JSON, and a reference with no value fails the step. Use ${<inputNodeId>} alone (never '
        + '${<inputNodeId>.message}): as a single reference it already yields the message both for text typed in the '
        + 'run dialog and for an API caller sending {"message":"..."}.',
    'Invalid expressions and decision configs the engine would refuse are refused by addNode/configureNode with the '
        + 'reason: fix them; never leave a node the engine cannot run. To add a node BEFORE an existing one, use addNode '
        + 'with insertBefore.',
]

/**
 * Os provedores de decisão que o servidor registra de fato (ArchflowBeanConfiguration.decisionProviders): o agente
 * escrevia o provider de LLM ("openai") no nó decision, que o motor não acha e escala sempre.
 */
const DECISION_PROVIDER_IDS = ['llm', 'rules', 'http-decisions', 'langchain4j-decisions'] as const

const findingKey = (f: DecisionFinding) => `${f.problem}:${f.subject}`

/**
 * Por que a config de decision que o agente quer gravar não roda (RCHFLW-116), ou undefined: a regra do Executar do
 * editor (decisionReadiness), mais o provider que não é de decisão. Confere a config RESULTANTE, porque o defeito
 * nasce da combinação (perguntas sem provider = http-decisions sem model). O que já estava errado num nó do usuário
 * ({@code before}) não é recusado: renomear ou mexer noutro campo não pode travar por um problema que o patch não
 * criou. Num nó novo, ou criado pelo copiloto, {@code before} é undefined e tudo conta.
 */
function decisionRefusal(
    nodeType: string, patch: ValidatedConfig, merged: Record<string, unknown>,
    before: Record<string, unknown> | undefined,
): string | undefined {
    if (nodeType !== 'decision' || (Object.keys(patch.set).length === 0 && patch.unset.length === 0)) return undefined
    const problems: string[] = []
    const provider = patch.set.provider
    if (typeof provider === 'string' && !(DECISION_PROVIDER_IDS as readonly string[]).includes(provider)) {
        problems.push(`provider "${provider}" is not a decision provider (a decision node does not take the LLM provider): use "llm" — the `
            + `platform LLM, model optional — or one of ${DECISION_PROVIDER_IDS.join(', ')}`)
    } else if (decisionModelMissing(merged) && !(before && decisionModelMissing(before))) {
        problems.push(`provider ${decisionProviderOf(merged)}${merged.provider === undefined ? ' (the default)' : ''} `
            + 'refuses to run without "model", and every request would go to ESCALATE: set "provider":"llm" (the '
            + 'platform LLM, no model needed) unless the user gave you a decision model id')
    }
    const preexisting = new Set(before ? decisionConfigFindings(before).map(findingKey) : [])
    for (const f of decisionConfigFindings(merged)) {
        if (!preexisting.has(findingKey(f))) problems.push(describeDecisionFindingForAgent(f))
    }
    return problems.length ? `decision config not applied: ${problems.join('; ')}` : undefined
}

/** A recusa do ramo AUTO de um decision que nunca chega a AUTO (provider llm, teto 0,89 < limiar 0,9). */
function unreachableAutoError(sourceId: string, nodeType: string | undefined, branch: string | undefined,
    config: Record<string, unknown> | undefined): string | undefined {
    if (nodeType !== 'decision' || branch !== 'AUTO' || !decisionAutoUnreachable(config)) return undefined
    return `branch "AUTO" of decision ${sourceId} is never followed: its provider llm caps the confidence at 0.89, `
        + 'below thresholds.auto (0.9). Branch on the answer instead (a condition or switch reading '
        + `\${${sourceId}.answers.<question>…} or \${${sourceId}.decision}), or use REVIEW/ESCALATE as a confidence gate`
}

/** O ramo pedido não é do nó de origem: a recusa diz quais são, para o agente escolher na rodada seguinte. */
function branchError(sourceId: string, branch: string, branches: readonly string[]): string {
    return branches.length
        ? `branch "${branch}" is not an output of node ${sourceId}; its branches are: ${branches.join(', ')}`
        : `node ${sourceId} does not branch: connect it without a branch`
}

/** Sem fluxo conhecido (fila sem destino), só se sabe o formato: os passos do canvas que vai abrir são outros. */
const FORMAT_PROBLEMS: ReadonlySet<ExpressionProblem> = new Set([
    'MALFORMED', 'BARE_OPERAND', 'NO_REFERENCE', 'MALFORMED_TEMPLATE',
])

/**
 * A config dos nós Entrada, para os campos esperados (ioFields) valerem como raiz — como no Executar e no servidor.
 * O CanvasNodeRef não leva config (é o que volta ao agente); ela vem do canvas aberto ou da fila. Sem isso, o
 * copiloto recusaria a {@code ${ticket.urgente}} que o editor aceita, e o agente, que declarou a chave como a recusa
 * pede, ficaria preso.
 */
function inputNodeConfigs(): Map<string, Record<string, unknown>> {
    const configs = new Map<string, Record<string, unknown>>()
    for (const s of useFlowStore.getState().getCanvasSnapshot().steps) {
        if (s.config) configs.set(s.id, s.config)
    }
    for (const op of queuedAdds()) {
        if (op.config) configs.set(op.id!, { ...(configs.get(op.id!) ?? {}), ...op.config })
    }
    return configs
}

/**
 * Por que a expressão de condition/switch que o agente quer gravar não serve (RCHFLW-116), ou undefined. É a regra
 * do Executar do editor e do servidor (controlExpressions / ControlExpressionRules): o agente montava
 * `ticket.urgente == true`, o toast dizia "Configured" e o pedido urgente seguia o ramo errado, em silêncio.
 * Recusar com a forma certa e os ids válidos deixa o agente corrigir na rodada seguinte. Só a chave da expressão
 * presente no patch é conferida — renomear um nó não pode ser recusado por uma expressão que o patch não toca.
 *
 * @param nodes os passos que a expressão pode citar; {@code refsKnown} false = só o formato
 */
function expressionRefusal(
    nodeType: string, set: Record<string, unknown>, nodes: CanvasNodeRef[], refsKnown: boolean,
): string | undefined {
    // A "Mensagem do usuário" do llm-chat (RCHFLW-120) passa pelas mesmas regras de caminho.
    const field: string | undefined = CONTROL_EXPRESSION_FIELD[nodeType] as string | undefined
        ?? (typeof set[USER_MESSAGE_FIELD] === 'string' ? USER_MESSAGE_FIELD : undefined)
    if (!field || typeof set[field] !== 'string') return undefined
    const configs = inputNodeConfigs()
    const steps = nodes.filter(n => !isAnnotationType(n.nodeType))
        .map(n => ({ id: n.id, componentId: n.nodeType, config: n.nodeType === 'input' ? configs.get(n.id) : undefined }))
    const refs = flowRefsOf(steps)
    const findings = (field === USER_MESSAGE_FIELD
        ? userMessageFindings({ id: '', componentId: nodeType, config: { [field]: set[field] } }, refs)
        : controlExpressionFindings(nodeType, { [field]: set[field] }, refs))
        .filter(f => refsKnown || FORMAT_PROBLEMS.has(f.problem))
    if (findings.length === 0) return undefined
    return `${field} not applied: ${findings.map(describeFindingForAgent).join('; ')}`
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
            + 'provider/model are plain ids (e.g. openai / gpt-4o-mini); omit them to inherit the flow default. '
            + 'On a decision node, provider is a DECISION provider (llm, rules, http-decisions), not the LLM provider: '
            + 'see the branching rules',
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
        description: 'How to classify and branch a flow (decision answers, condition/switch expressions, decision routes, '
            + 'the llm-chat userMessage after a branch)',
        value: EXPRESSION_RULES,
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
            + 'Optional `insertBefore` (an existing node id): the new node goes right BEFORE that node — every edge '
            + 'that reached it now reaches the new node, and the new node is connected to it. Use it for "add X '
            + 'before Y" instead of connecting by hand (do not call connectNodes for those edges). '
            + 'Returns {ok:false, error} for an unknown node type, config key or an invalid condition/switch '
            + 'expression: fix it and retry.',
        parameters: z.object({
            nodeType: z.string().describe('the node type to add, e.g. input, llm-chat, condition, output'),
            label: z.string().optional().describe('optional label for the node'),
            config: z.union([z.string(), z.record(z.string(), z.unknown())]).optional()
                .describe('optional JSON object with the node config, e.g. {"systemPrompt":"..."}'),
            insertBefore: z.string().optional()
                .describe('optional id of an existing node: insert the new node between its predecessors and it'),
        }),
        handler: async ({ nodeType, label, config, insertBefore }: AddNodeParams, ctx?: ToolContext)
            : Promise<AddNodeResult> => {
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
                // Nó novo: tudo o que a config cria conta (sem `before`).
                const badDecision = decisionRefusal(nodeType, checked.config, checked.config.set, undefined)
                if (badDecision) return refuse('Add node', badDecision)
            }
            const canvas = useFlowStore.getState().canvasApi
            const known = canvas ? canvas.listNodes() : queuedNodeRefs()
            const badExpression = initial && expressionRefusal(nodeType, initial, known,
                !!canvas || !!useFlowStore.getState().pendingCanvasTarget)
            if (badExpression) return refuse('Add node', badExpression, { nodes: known })
            if (insertBefore !== undefined) {
                // Inserir religa as arestas que já estão no canvas; na fila não há arestas a religar com certeza.
                if (!canvas) return refuse('Add node', 'insertBefore needs the flow open in the editor')
                const target = known.find(n => n.id === insertBefore)
                if (!target || isAnnotationType(target.nodeType)) {
                    return refuse('Add node', `insertBefore: unknown node id ${insertBefore}`, { nodes: known })
                }
            }
            if (canvas) {
                const id = canvas.addNode({ nodeType, label, placement: 'free', ...(initial ? { config: initial } : {}) })
                createdByCopilotRef.current.add(id)
                if (insertBefore !== undefined) {
                    const rewiredEdges = canvas.insertBefore(id, insertBefore) ?? 0
                    notifications.show({ color: 'teal', message: `Added "${nodeType}" node (${id}) before ${insertBefore}` })
                    return {
                        ok: true, id, nodeType, label: label ?? nodeType, queued: false,
                        insertedBefore: insertBefore, rewiredEdges,
                    }
                }
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
            + 'Optional `branch` picks which output of a branching source the edge follows: "true"/"false" for '
            + 'condition, one of the cases or "default" for switch, AUTO/REVIEW/ESCALATE for decision (a decision '
            + 'edge without branch always runs; its branches are the confidence route, not the answer — branch on the '
            + 'answer with a condition/switch after it). '
            + 'Returns {ok: true} or {ok: false, error, nodes}: on failure, retry with an id from `nodes`.',
        parameters: z.object({
            sourceId: z.string().describe('id of the source node, as returned by addNode'),
            targetId: z.string().describe('id of the target node, as returned by addNode'),
            branch: z.string().optional()
                .describe('optional branch of the source node: true/false, a switch case, AUTO/REVIEW/ESCALATE'),
        }),
        handler: async ({ sourceId, targetId, branch: rawBranch }: ConnectNodesParams, ctx?: ToolContext)
            : Promise<ConnectNodesResult> => {
            guardEdit(ctx, 'connectNodes')
            const branch = typeof rawBranch === 'string' && rawBranch.trim() ? rawBranch.trim() : undefined
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
                // O ramo só se confere quando a origem está na fila (é ela que diz o tipo e os casos do nó).
                const queuedSource = queuedAdds().find(op => op.id === sourceId)
                const queuedBranches = queuedSource ? branchOptions(queuedSource.nodeType, queuedSource.config) : undefined
                const refused = unknown.length
                    ? `unknown node id: ${unknown.join(', ')}`
                    : annotationError(sourceId, targetId, queued)
                        ?? (branch !== undefined && queuedBranches && !queuedBranches.includes(branch)
                            ? branchError(sourceId, branch, queuedBranches) : undefined)
                        ?? unreachableAutoError(sourceId, queuedSource?.nodeType, branch, queuedSource?.config)
                if (refused) {
                    notifications.show({ color: 'red', message: `Connect failed — ${refused}` })
                    return { ok: false, error: refused, nodes: queued, ...(queuedBranches ? { branches: queuedBranches } : {}) }
                }
                store.enqueueCanvasOp({ kind: 'connect', sourceId, targetId, ...(branch !== undefined ? { branch } : {}) })
                notifications.show({ color: 'blue', message: 'Queued connection — open a flow to apply it.' })
                return { ok: true, sourceId, targetId, ...(branch !== undefined ? { branch } : {}), queued: true }
            }
            const nodes = canvas.listNodes()
            const known = new Set(nodes.map(n => n.id))
            const unknown = [sourceId, targetId].filter(id => !known.has(id))
            // Devolvido ao agente (e não lançado): um erro encerraria o laço sem followUp, e é justamente o
            // resultado com os ids válidos que deixa o agente corrigir a ligação na rodada seguinte.
            const branches = unknown.length ? undefined : canvas.branchesOf(sourceId) ?? undefined
            const source = nodes.find(n => n.id === sourceId)
            const error = unknown.length
                ? `unknown node id: ${unknown.join(', ')}`
                : annotationError(sourceId, targetId, nodes)
                    ?? (branch !== undefined && !(branches ?? []).includes(branch)
                        ? branchError(sourceId, branch, branches ?? []) : undefined)
                    ?? unreachableAutoError(sourceId, source?.nodeType, branch, canvas.configOf(sourceId) ?? undefined)
            if (error || !canvas.connectNodes(sourceId, targetId, branch)) {
                const message = error ?? 'the canvas refused the connection'
                notifications.show({ color: 'red', message: `Connect failed — ${message}` })
                return { ok: false, error: message, nodes, ...(branches?.length ? { branches } : {}) }
            }
            notifications.show({ color: 'teal', message: `Connected ${sourceId} → ${targetId}${branch ? ` (${branch})` : ''}` })
            return { ok: true, sourceId, targetId, ...(branch !== undefined ? { branch } : {}), queued: false }
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
            const badExpression = expressionRefusal(node.nodeType, patch.set, nodes,
                !!canvas || !!useFlowStore.getState().pendingCanvasTarget)
            if (badExpression) return refuse(tool, badExpression, { nodes })
            const current = canvas
                ? canvas.configOf(nodeId) ?? undefined
                : queuedAdds().find(op => op.id === nodeId)?.config
            const badDecision = decisionRefusal(node.nodeType, patch, applyConfig(current, patch),
                createdByCopilotRef.current.has(nodeId) ? undefined : (current ?? {}))
            if (badDecision) return refuse(tool, badDecision)

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
