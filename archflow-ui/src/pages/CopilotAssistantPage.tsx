import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Stack, Text } from '@mantine/core'
import { HttpAgent } from '@ag-ui/client'
import { CopilotKitProvider, CopilotSidebar } from '@copilotkit/react-core/v2'
import '@copilotkit/react-core/v2/styles.css'
import { PageHeader } from '../components/PageHeader'

/**
 * CopilotKit sidebar wired directly to the archflow AG-UI endpoint
 * (ADR-0003 / design-0006). Uses the v2 direct-agent connection
 * ({@code agents__unsafe_dev_only} + {@code HttpAgent}) — no Node CopilotRuntime:
 * archflow's Java backend is already the auth/tenant/governance boundary, so it
 * plays the role the runtime would. The "agent" runs a workflow over AG-UI.
 */
export default function CopilotAssistantPage() {
    const { t } = useTranslation()
    // fetch must be bound to window — @ag-ui/client calls it unbound otherwise
    // ("Illegal invocation").
    const agent = useMemo(() => new HttpAgent({ url: '/ag-ui/agent', fetch: window.fetch.bind(window) }), [])

    return (
        <CopilotKitProvider agents__unsafe_dev_only={{ archflow: agent }}>
            <Stack p="md" gap="md" maw={820}>
                <PageHeader
                    title={t('copilot.title', { defaultValue: 'Copilot (AG-UI)' })}
                    subtitle={t('copilot.subtitle', {
                        defaultValue: 'CopilotKit connected directly to the archflow ConversationalAgent over AG-UI. Open the sidebar and chat — replies and tool use stream back as AG-UI events.',
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
