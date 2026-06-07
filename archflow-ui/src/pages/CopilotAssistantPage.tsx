import { useTranslation } from 'react-i18next'
import { Card, List, Stack, Text } from '@mantine/core'
import { PageHeader } from '../components/PageHeader'

/**
 * Info page for the global copilot (ADR-0003 / design-0006). The copilot itself
 * is the app-wide CopilotKit sidebar mounted in AppLayout (available on every
 * page), connected to the archflow ConversationalAgent over AG-UI. It is
 * screen-aware (useAgentContext) and can operate the app (useFrontendTool:
 * navigateTo / runWorkflow / showStatus).
 */
export default function CopilotAssistantPage() {
    const { t } = useTranslation()

    return (
        <Stack p="md" gap="md" maw={820}>
            <PageHeader
                title={t('copilot.title', { defaultValue: 'Copilot (AG-UI)' })}
                subtitle={t('copilot.subtitle', {
                    defaultValue: 'The copilot is the sidebar available on every page (bottom-right). It is connected to the archflow agent over AG-UI, is screen-aware, and can operate the app.',
                })}
            />
            <Card withBorder radius="lg" p="lg">
                <Text fw={600} size="sm" mb="xs">{t('copilot.tryTitle', { defaultValue: 'Try asking the copilot:' })}</Text>
                <List size="sm" spacing="xs">
                    <List.Item>"Quais workflows eu tenho?" — it reads the screen (useAgentContext).</List.Item>
                    <List.Item>"Abra as execuções" / "Vá para o marketplace" — it navigates (navigateTo).</List.Item>
                    <List.Item>"Rode o workflow wf-demo-001" — it executes it and opens Executions (runWorkflow).</List.Item>
                    <List.Item>"Mostre um status dizendo: tudo certo" — it shows a notification (showStatus).</List.Item>
                </List>
            </Card>
            <Text size="sm" c="dimmed">
                {t('copilot.hint', {
                    defaultValue: 'Direct AG-UI connection (no Node runtime): the Java backend is the security boundary.',
                })}
            </Text>
        </Stack>
    )
}
