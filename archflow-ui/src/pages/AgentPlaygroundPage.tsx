import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Grid, Group, Select, SimpleGrid, Stack, Tabs, Textarea, TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconInfoCircle, IconMessage, IconPlayerPlayFilled, IconRobot, IconSend, IconTerminal2,
} from '@tabler/icons-react'
import { useCatalog } from '../components/PropertyPanel/useCatalog'
import { agentPlaygroundApi } from '../services/agent-playground-api'
import { JsonViewer } from '../components/JsonViewer'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import EmptyState from '../components/EmptyState'

/** Which endpoint produced the last response — shown as a tag on the response panel. */
type Origem = 'invoke' | 'message'

/**
 * Playground for the two external-trigger endpoints that previously
 * had no UI: {@code POST /archflow/agents/{id}/invoke} (demand
 * trigger) and {@code POST /archflow/events/message} (conversation
 * trigger). Useful for smoke testing wire-up and for demos.
 */
export default function AgentPlaygroundPage() {
    const { t } = useTranslation()
    const catalog = useCatalog()
    const [tenantId, setTenant] = useState('tenant_demo')
    const [sessionId, setSess]  = useState('')
    const [agentId, setAgent]   = useState<string | null>(null)
    const [payload, setPayload] = useState('{"topic":"weather"}')
    const [message, setMessage] = useState('Hello, agent!')
    const [response, setResp]   = useState<unknown>(null)
    const [origem, setOrigem]   = useState<Origem | null>(null)
    const [busy, setBusy]       = useState<Origem | null>(null)

    const agentOptions = [
        ...catalog.agents.map(a => ({ value: a.id, label: `${a.displayName} (${t('agentPlayground.agentLabel')})` })),
        ...catalog.assistants.map(a => ({ value: a.id, label: `${a.displayName} (${t('agentPlayground.assistantLabel')})` })),
    ]

    const handleInvoke = async () => {
        if (!agentId) {
            notifications.show({ color: 'yellow', title: t('agentPlayground.agent'), message: t('agentPlayground.selectFirst') })
            return
        }
        setBusy('invoke')
        try {
            const parsed = payload.trim() ? JSON.parse(payload) : {}
            const res = await agentPlaygroundApi.invoke(agentId, tenantId, sessionId || undefined, parsed)
            setResp(res)
            setOrigem('invoke')
            notifications.show({ color: 'green', title: t('agentPlayground.invoked'), message: t('agentPlayground.requestIdMsg', { id: res.requestId }) })
        } catch (e) {
            notifications.show({ color: 'red', title: t('agentPlayground.invokeFailed'),
                message: e instanceof Error ? e.message : String(e) })
        } finally {
            setBusy(null)
        }
    }

    const handleMessage = async () => {
        if (!agentId) {
            notifications.show({ color: 'yellow', title: t('agentPlayground.agent'), message: t('agentPlayground.selectFirst') })
            return
        }
        if (!message.trim()) {
            notifications.show({ color: 'yellow', title: t('agentPlayground.message'), message: t('agentPlayground.emptyMessage') })
            return
        }
        setBusy('message')
        try {
            const res = await agentPlaygroundApi.message(tenantId, agentId, message, sessionId || undefined)
            setResp(res)
            setOrigem('message')
            notifications.show({ color: 'green', title: t('agentPlayground.accepted'), message: t('agentPlayground.requestIdMsg', { id: res.requestId }) })
        } catch (e) {
            notifications.show({ color: 'red', title: t('agentPlayground.sendFailed'),
                message: e instanceof Error ? e.message : String(e) })
        } finally {
            setBusy(null)
        }
    }

    return (
        <Stack gap="lg" p="md" data-testid="agent-playground">
            <PageHeader
                icon={IconPlayerPlayFilled}
                accent="violet"
                title={t('agentPlayground.title')}
                subtitle={t('agentPlayground.subtitle')}
            />

            <Grid gap="lg" align="flex-start">
                <Grid.Col span={{ base: 12, lg: 5 }}>
                    <SectionCard
                        icon={IconRobot}
                        accent="violet"
                        title={t('agentPlayground.requestTitle')}
                        description={t('agentPlayground.requestHint')}
                    >
                        <Stack gap="md">
                            <Alert color="blue" variant="light" icon={<IconInfoCircle size={16} />} p="sm">
                                {t('agentPlayground.info').split(/<code>|<\/code>/).map((seg, i) =>
                                    i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
                            </Alert>

                            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                                <TextInput size="sm" label={t('agentPlayground.tenantId')} value={tenantId}
                                        onChange={e => setTenant(e.currentTarget.value)}
                                        data-testid="pg-tenant" />
                                <TextInput size="sm" label={t('agentPlayground.sessionId')} value={sessionId}
                                        onChange={e => setSess(e.currentTarget.value)}
                                        placeholder={t('agentPlayground.sessionPlaceholder')} />
                            </SimpleGrid>

                            <Select
                                size="sm"
                                label={t('agentPlayground.agent')}
                                value={agentId}
                                onChange={setAgent}
                                data={agentOptions}
                                searchable
                                placeholder={t('agentPlayground.agentPlaceholder')}
                                data-testid="pg-agent"
                            />

                            <Tabs defaultValue="invoke">
                                <Tabs.List grow>
                                    <Tabs.Tab value="invoke" leftSection={<IconTerminal2 size={14} />}>
                                        {t('agentPlayground.tabs.invoke')}
                                    </Tabs.Tab>
                                    <Tabs.Tab value="message" leftSection={<IconMessage size={14} />}>
                                        {t('agentPlayground.tabs.message')}
                                    </Tabs.Tab>
                                </Tabs.List>

                                <Tabs.Panel value="invoke" pt="md">
                                    <Stack gap="sm">
                                        <Textarea
                                            size="sm"
                                            label={t('agentPlayground.jsonPayload')}
                                            value={payload}
                                            onChange={e => setPayload(e.currentTarget.value)}
                                            autosize minRows={5}
                                            data-testid="pg-payload"
                                            styles={{ input: { fontFamily: 'var(--font-mono)' } }}
                                        />
                                        <Group justify="flex-end">
                                            <Button onClick={handleInvoke} loading={busy === 'invoke'}
                                                    leftSection={<IconPlayerPlayFilled size={14} />}
                                                    data-testid="pg-invoke-btn">
                                                {t('agentPlayground.invokeBtn')}
                                            </Button>
                                        </Group>
                                    </Stack>
                                </Tabs.Panel>

                                <Tabs.Panel value="message" pt="md">
                                    <Stack gap="sm">
                                        <Textarea
                                            size="sm"
                                            label={t('agentPlayground.message')}
                                            value={message}
                                            onChange={e => setMessage(e.currentTarget.value)}
                                            autosize minRows={5}
                                            data-testid="pg-message"
                                        />
                                        <Group justify="flex-end">
                                            <Button onClick={handleMessage} loading={busy === 'message'}
                                                    leftSection={<IconSend size={14} />}
                                                    data-testid="pg-message-btn">
                                                {t('agentPlayground.sendBtn')}
                                            </Button>
                                        </Group>
                                    </Stack>
                                </Tabs.Panel>
                            </Tabs>
                        </Stack>
                    </SectionCard>
                </Grid.Col>

                <Grid.Col span={{ base: 12, lg: 7 }}>
                    <SectionCard
                        icon={IconTerminal2}
                        accent="violet"
                        title={t('agentPlayground.response')}
                        description={t('agentPlayground.responseHint')}
                        actions={origem && (
                            <Badge variant="light" color="violet">{t(`agentPlayground.tabs.${origem}`)}</Badge>
                        )}
                    >
                        {response != null ? (
                            <div role="status" aria-live="polite">
                                <JsonViewer value={response} autoExpandDepth={2} testId="pg-response" />
                            </div>
                        ) : (
                            <EmptyState
                                compact
                                accent="violet"
                                icon={<IconTerminal2 />}
                                title={t('agentPlayground.emptyTitle')}
                                description={t('agentPlayground.emptyHint')}
                            />
                        )}
                    </SectionCard>
                </Grid.Col>
            </Grid>
        </Stack>
    )
}
