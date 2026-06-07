import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Stack, Text } from '@mantine/core'
import { HttpAgent } from '@ag-ui/client'
import { CopilotKitProvider, CopilotSidebar, useAgentContext } from '@copilotkit/react-core/v2'
import '@copilotkit/react-core/v2/styles.css'
import { PageHeader } from '../components/PageHeader'
import { api } from '../services/api'

interface WorkflowSummary { id: string; name?: string }

/**
 * Exposes the user's screen/app context to the agent via useAgentContext, so the
 * copilot can answer about what's on screen (e.g. the user's workflows). Must run
 * inside the CopilotKitProvider.
 */
function AgentContext() {
    const [workflows, setWorkflows] = useState<{ id: string; name: string }[]>([])

    useEffect(() => {
        api.get<WorkflowSummary[]>('/workflows')
            .then((list) => setWorkflows(list.map((w) => ({ id: w.id, name: w.name ?? w.id }))))
            .catch(() => { /* unauthenticated / empty — context just omits the list */ })
    }, [])

    useAgentContext({
        description: 'Current page',
        value: 'archflow Copilot assistant (route /playground/copilot)',
    })
    useAgentContext({
        description: "The user's workflows (id and name)",
        value: workflows,
    })
    return null
}

/**
 * CopilotKit sidebar wired directly to the archflow AG-UI ConversationalAgent
 * (ADR-0003 / design-0006). v2 direct-agent connection
 * ({@code agents__unsafe_dev_only} + {@code HttpAgent}) — no Node CopilotRuntime;
 * the Java backend is the boundary. useAgentContext makes it screen-aware.
 */
export default function CopilotAssistantPage() {
    const { t } = useTranslation()
    // fetch must be bound to window — @ag-ui/client calls it unbound otherwise
    // ("Illegal invocation").
    const agent = useMemo(() => new HttpAgent({ url: '/ag-ui/agent', fetch: window.fetch.bind(window) }), [])

    return (
        <CopilotKitProvider agents__unsafe_dev_only={{ archflow: agent }}>
            <AgentContext />
            <Stack p="md" gap="md" maw={820}>
                <PageHeader
                    title={t('copilot.title', { defaultValue: 'Copilot (AG-UI)' })}
                    subtitle={t('copilot.subtitle', {
                        defaultValue: 'CopilotKit connected directly to the archflow ConversationalAgent over AG-UI. It is screen-aware via useAgentContext — try asking about your workflows.',
                    })}
                />
                <Text size="sm" c="dimmed">
                    {t('copilot.hint', {
                        defaultValue: 'Direct AG-UI connection (no Node runtime): the Java backend is the security boundary.',
                    })}
                </Text>
            </Stack>
            <CopilotSidebar agentId="archflow" />
        </CopilotKitProvider>
    )
}
