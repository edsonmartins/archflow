import type { ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { Group, Paper, SimpleGrid, Stack, Text, ThemeIcon } from '@mantine/core'
import {
    IconBell, IconCompass, IconEye, IconMessageChatbot, IconPlayerPlay, IconShieldLock, IconSparkles,
} from '@tabler/icons-react'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'

/** The four copilot abilities, each tied to the hook/tool that implements it. */
const EXAMPLES: { key: string; icon: ComponentType<{ size?: number; stroke?: number }>; color: string }[] = [
    { key: 'context',  icon: IconEye,        color: 'violet' },
    { key: 'navigate', icon: IconCompass,    color: 'blue' },
    { key: 'run',      icon: IconPlayerPlay, color: 'teal' },
    { key: 'status',   icon: IconBell,       color: 'orange' },
]

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
        <Stack p="md" gap="lg">
            <PageHeader
                icon={IconSparkles}
                accent="violet"
                title={t('copilot.title')}
                subtitle={t('copilot.subtitle')}
            />

            <SectionCard
                icon={IconMessageChatbot}
                accent="violet"
                title={t('copilot.tryTitle')}
                description={t('copilot.tryHint')}
            >
                <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
                    {EXAMPLES.map(({ key, icon: Icon, color }) => (
                        <Paper key={key} withBorder p="md">
                            <Group gap="sm" wrap="nowrap" align="flex-start">
                                <ThemeIcon size={36} radius="md" variant="light" color={color}>
                                    <Icon size={18} stroke={1.8} />
                                </ThemeIcon>
                                <Stack gap={2} style={{ minWidth: 0 }}>
                                    <Text fw={600} size="sm">{t(`copilot.exampleTitles.${key}`)}</Text>
                                    <Text size="sm" c="dimmed" lh={1.5}>{t(`copilot.examples.${key}`)}</Text>
                                </Stack>
                            </Group>
                        </Paper>
                    ))}
                </SimpleGrid>
            </SectionCard>

            <SectionCard
                icon={IconShieldLock}
                accent="slate"
                title={t('copilot.securityTitle')}
                description={t('copilot.hint')}
            >
                <Text size="sm" c="dimmed">{t('copilot.whereHint')}</Text>
            </SectionCard>
        </Stack>
    )
}
