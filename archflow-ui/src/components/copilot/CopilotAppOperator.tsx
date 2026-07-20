import { useEffect, useMemo } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { notifications } from '@mantine/notifications'
import { z } from 'zod'
import { useAgentContext, useFrontendTool } from '@copilotkit/react-core/v2'
import { api, workflowApi } from '../../services/api'
import { useFlowStore } from '../FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'
import { PALETTE_NODES } from '../FlowCanvas/constants'

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
type AddNodeParams = { nodeType: string; label?: string }
type ConnectNodesParams = { sourceId: string; targetId: string }

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
    // Shared workflow list (deduped fetch in the store) instead of a private
    // GET /workflows on every route change.
    const storeWorkflows = useWorkflowStore((s) => s.workflows)
    const fetchWorkflows = useWorkflowStore((s) => s.fetchWorkflows)
    const workflows = useMemo(
        () => storeWorkflows.map((w) => ({ id: w.id, name: w.name ?? w.id })),
        [storeWorkflows])
    // Live mirror of the editor canvas (store is kept in sync from React Flow).
    const canvasNodes = useFlowStore((s) => s.nodes)

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
        value: PALETTE_NODES.map((n) => n.componentId),
    })
    useAgentContext({
        description: 'Nodes currently on the editor canvas (id, label) — use these ids with connectNodes',
        value: canvasNodes.map((n) => ({ id: n.id, label: n.data?.label, nodeType: n.data?.nodeType })),
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
    useFrontendTool({
        name: 'createFlow',
        description: 'Create a new, empty workflow and open it in the editor (mounts the canvas). '
            + 'Call this before addNode when no flow is open.',
        parameters: z.object({ name: z.string().optional().describe('optional name for the flow') }),
        followUp: false,
        handler: async ({ name }: CreateFlowParams) => {
            try {
                const wf = await workflowApi.create({
                    metadata: { name: name || 'New flow', description: '', version: '1.0.0', category: '', tags: [] },
                    steps: [],
                    configuration: {},
                })
                notifications.show({ color: 'teal', message: `Created flow "${wf.metadata?.name ?? wf.id}"` })
                navigate(`/editor/${wf.id}`)
            } catch (e) {
                notifications.show({ color: 'red', message: `Create failed: ${String(e)}` })
            }
        },
    })

    useFrontendTool({
        name: 'addNode',
        description: 'Add a node to the workflow editor canvas. nodeType is one of the available node types. '
            + 'If no flow is open the node is queued and applied as soon as a flow is opened/created '
            + '(so you can call createFlow then addNode).',
        parameters: z.object({
            nodeType: z.string().describe('the node type to add, e.g. agent, llm-chat, tool, condition'),
            label: z.string().optional().describe('optional label for the node'),
        }),
        followUp: false,
        handler: async ({ nodeType, label }: AddNodeParams) => {
            const canvas = useFlowStore.getState().canvasApi
            if (canvas) {
                const id = canvas.addNode({ nodeType, label })
                notifications.show({ color: 'teal', message: `Added "${nodeType}" node (${id})` })
                return
            }
            useFlowStore.getState().enqueueCanvasOp({ kind: 'add', nodeType, label })
            notifications.show({
                color: 'blue',
                message: `Queued "${nodeType}" — create or open a flow in the editor to apply it.`,
            })
        },
    })

    useFrontendTool({
        name: 'connectNodes',
        description: 'Connect two nodes on the editor canvas with an edge (source -> target). '
            + 'Use the node ids from the canvas context.',
        parameters: z.object({
            sourceId: z.string().describe('id of the source node'),
            targetId: z.string().describe('id of the target node'),
        }),
        followUp: false,
        handler: async ({ sourceId, targetId }: ConnectNodesParams) => {
            const canvas = useFlowStore.getState().canvasApi
            if (!canvas) {
                useFlowStore.getState().enqueueCanvasOp({ kind: 'connect', sourceId, targetId })
                notifications.show({ color: 'blue', message: 'Queued connection — open a flow to apply it.' })
                return
            }
            const ok = canvas.connectNodes(sourceId, targetId)
            notifications.show(ok
                ? { color: 'teal', message: `Connected ${sourceId} → ${targetId}` }
                : { color: 'red', message: 'Connect failed — unknown node id' })
        },
    })

    return null
}
