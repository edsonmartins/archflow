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
                title={t('copilot.title')}
                subtitle={t('copilot.subtitle')}
            />
            <Card withBorder radius="lg" p="lg">
                <Text fw={600} size="sm" mb="xs">{t('copilot.tryTitle')}</Text>
                <List size="sm" spacing="xs">
                    <List.Item>{t('copilot.examples.context')}</List.Item>
                    <List.Item>{t('copilot.examples.navigate')}</List.Item>
                    <List.Item>{t('copilot.examples.run')}</List.Item>
                    <List.Item>{t('copilot.examples.status')}</List.Item>
                </List>
            </Card>
            <Text size="sm" c="dimmed">
                {t('copilot.hint')}
            </Text>
        </Stack>
    )
}
