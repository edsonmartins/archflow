import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Stack, Text } from '@mantine/core'
import { z } from 'zod'
import { HttpAgent } from '@ag-ui/client'
import { CopilotKitProvider, CopilotSidebar, useAgentContext, useFrontendTool } from '@copilotkit/react-core/v2'
import '@copilotkit/react-core/v2/styles.css'
import { PageHeader } from '../components/PageHeader'
import { api } from '../services/api'

interface WorkflowSummary { id: string; name?: string }

/**
 * Wires the copilot into the page (must run inside CopilotKitProvider):
 *  - useAgentContext: makes it screen-aware (current page + the user's workflows);
 *  - useFrontendTool: lets the agent ACT on screen — it can show a status banner.
 * Renders the banner so a tool call visibly changes the main area.
 */
function CopilotTools() {
    const [workflows, setWorkflows] = useState<{ id: string; name: string }[]>([])
    const [status, setStatus] = useState<string | null>(null)

    useEffect(() => {
        api.get<WorkflowSummary[]>('/workflows')
            .then((list) => setWorkflows(list.map((w) => ({ id: w.id, name: w.name ?? w.id }))))
            .catch(() => { /* unauthenticated / empty */ })
    }, [])

    useAgentContext({
        description: 'Current page',
        value: 'archflow Copilot assistant (route /playground/copilot)',
    })
    useAgentContext({
        description: "The user's workflows (id and name)",
        value: workflows,
    })

    // Frontend action: the agent can call this to put a banner on the page.
    useFrontendTool({
        name: 'showStatus',
        description: 'Display a short status/notification banner to the user on the page. '
            + 'Use it when the user asks to show, highlight, or announce something on screen.',
        parameters: z.object({
            message: z.string().describe('the text to show in the banner'),
        }),
        followUp: false,
        handler: async ({ message }) => {
            setStatus(String(message))
        },
    })

    if (!status) return null
    return (
        <Alert color="blue" withCloseButton title="Copilot" onClose={() => setStatus(null)} data-testid="copilot-status">
            {status}
        </Alert>
    )
}

/**
 * CopilotKit sidebar wired directly to the archflow AG-UI ConversationalAgent
 * (ADR-0003 / design-0006). v2 direct-agent connection (agents__unsafe_dev_only +
 * HttpAgent) — no Node CopilotRuntime; the Java backend is the boundary.
 * Screen-aware (useAgentContext) and can act on screen (useFrontendTool).
 */
export default function CopilotAssistantPage() {
    const { t } = useTranslation()
    // fetch must be bound to window — @ag-ui/client calls it unbound otherwise.
    const agent = useMemo(() => new HttpAgent({ url: '/ag-ui/agent', fetch: window.fetch.bind(window) }), [])

    return (
        <CopilotKitProvider agents__unsafe_dev_only={{ archflow: agent }}>
            <Stack p="md" gap="md" maw={820}>
                <PageHeader
                    title={t('copilot.title', { defaultValue: 'Copilot (AG-UI)' })}
                    subtitle={t('copilot.subtitle', {
                        defaultValue: 'Connected to the archflow ConversationalAgent over AG-UI. It is screen-aware (ask about your workflows) and can act on screen (ask it to show a status message).',
                    })}
                />
                <Text size="sm" c="dimmed">
                    {t('copilot.hint', {
                        defaultValue: 'Direct AG-UI connection (no Node runtime): the Java backend is the security boundary.',
                    })}
                </Text>
                <CopilotTools />
            </Stack>
            <CopilotSidebar agentId="archflow" />
        </CopilotKitProvider>
    )
}
