import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { notifications } from '@mantine/notifications'
import { z } from 'zod'
import { useAgentContext, useFrontendTool } from '@copilotkit/react-core/v2'
import { api } from '../../services/api'

/** page key -> route. Exposed to the agent so it can navigate by name. */
const ROUTES: Record<string, string> = {
    workflows: '/',
    editor: '/editor',
    executions: '/executions',
    conversations: '/conversations',
    approvals: '/approvals',
    templates: '/templates',
    marketplace: '/marketplace',
    'dynamic-workflow': '/playground/orchestration',
    'ag-ui': '/playground/ag-ui',
    copilot: '/playground/copilot',
}

interface WorkflowSummary { id: string; name?: string }

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
    const [workflows, setWorkflows] = useState<{ id: string; name: string }[]>([])

    useEffect(() => {
        api.get<WorkflowSummary[]>('/workflows')
            .then((list) => setWorkflows(list.map((w) => ({ id: w.id, name: w.name ?? w.id }))))
            .catch(() => { /* unauthenticated / empty */ })
    }, [location.pathname])

    useAgentContext({ description: 'Current route (where the user is)', value: location.pathname })
    useAgentContext({
        description: 'Pages you can open with navigateTo (page key)',
        value: Object.keys(ROUTES),
    })
    useAgentContext({ description: "The user's workflows (id and name)", value: workflows })

    useFrontendTool({
        name: 'navigateTo',
        description: 'Open a page in the app. The `page` must be one of: ' + Object.keys(ROUTES).join(', ') + '.',
        parameters: z.object({ page: z.string().describe('the page key to open') }),
        followUp: false,
        handler: async ({ page }) => {
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
        handler: async ({ workflowId }) => {
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
        handler: async ({ message }) => {
            notifications.show({ message: String(message) })
        },
    })

    return null
}
