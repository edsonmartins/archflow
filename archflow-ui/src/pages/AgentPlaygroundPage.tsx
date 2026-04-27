import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Card, Code, Group, Select, Stack, Tabs, Text, Textarea, TextInput, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useCatalog } from '../components/PropertyPanel/useCatalog'
import { agentPlaygroundApi } from '../services/agent-playground-api'

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

    const agentOptions = [
        ...catalog.agents.map(a => ({ value: a.id, label: `${a.displayName} (${t('agentPlayground.agentLabel')})` })),
        ...catalog.assistants.map(a => ({ value: a.id, label: `${a.displayName} (${t('agentPlayground.assistantLabel')})` })),
    ]

    const handleInvoke = async () => {
        if (!agentId) {
            notifications.show({ color: 'yellow', title: t('agentPlayground.agent'), message: t('agentPlayground.selectFirst') })
            return
        }
        try {
            const parsed = payload.trim() ? JSON.parse(payload) : {}
            const res = await agentPlaygroundApi.invoke(agentId, tenantId, sessionId || undefined, parsed)
            setResp(res)
            notifications.show({ color: 'green', title: t('agentPlayground.invoked'), message: t('agentPlayground.requestIdMsg', { id: res.requestId }) })
        } catch (e) {
            notifications.show({ color: 'red', title: t('agentPlayground.invokeFailed'),
                message: e instanceof Error ? e.message : String(e) })
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
        try {
            const res = await agentPlaygroundApi.message(tenantId, agentId, message, sessionId || undefined)
            setResp(res)
            notifications.show({ color: 'green', title: t('agentPlayground.accepted'), message: t('agentPlayground.requestIdMsg', { id: res.requestId }) })
        } catch (e) {
            notifications.show({ color: 'red', title: t('agentPlayground.sendFailed'),
                message: e instanceof Error ? e.message : String(e) })
        }
    }

    return (
        <Stack gap="md" style={{ padding: 24, maxWidth: 900 }} data-testid="agent-playground">
            <Title order={2}>{t('agentPlayground.title')}</Title>
            <Alert color="blue" variant="light">
                {t('agentPlayground.info').split(/<code>|<\/code>/).map((seg, i) =>
                    i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
            </Alert>

            <Group grow>
                <TextInput label={t('agentPlayground.tenantId')} value={tenantId}
                        onChange={e => setTenant(e.currentTarget.value)}
                        data-testid="pg-tenant" />
                <TextInput label={t('agentPlayground.sessionId')} value={sessionId}
                        onChange={e => setSess(e.currentTarget.value)}
                        placeholder={t('agentPlayground.sessionPlaceholder')} />
            </Group>

            <Select
                label={t('agentPlayground.agent')}
                value={agentId}
                onChange={setAgent}
                data={agentOptions}
                searchable
                placeholder={t('agentPlayground.agentPlaceholder')}
                data-testid="pg-agent"
            />

            <Tabs defaultValue="invoke">
                <Tabs.List>
                    <Tabs.Tab value="invoke">{t('agentPlayground.tabs.invoke')}</Tabs.Tab>
                    <Tabs.Tab value="message">{t('agentPlayground.tabs.message')}</Tabs.Tab>
                </Tabs.List>

                <Tabs.Panel value="invoke" pt="sm">
                    <Stack>
                        <Textarea
                            label={t('agentPlayground.jsonPayload')}
                            value={payload}
                            onChange={e => setPayload(e.currentTarget.value)}
                            autosize minRows={4}
                            data-testid="pg-payload"
                            styles={{ input: { fontFamily: 'var(--font-mono)' } }}
                        />
                        <Group justify="flex-end">
                            <Button onClick={handleInvoke} data-testid="pg-invoke-btn">{t('agentPlayground.invokeBtn')}</Button>
                        </Group>
                    </Stack>
                </Tabs.Panel>

                <Tabs.Panel value="message" pt="sm">
                    <Stack>
                        <Textarea
                            label={t('agentPlayground.message')}
                            value={message}
                            onChange={e => setMessage(e.currentTarget.value)}
                            autosize minRows={3}
                            data-testid="pg-message"
                        />
                        <Group justify="flex-end">
                            <Button onClick={handleMessage} data-testid="pg-message-btn">{t('agentPlayground.sendBtn')}</Button>
                        </Group>
                    </Stack>
                </Tabs.Panel>
            </Tabs>

            {response != null && (
                <Card withBorder>
                    <Text size="sm" c="dimmed" mb={4}>{t('agentPlayground.response')}</Text>
                    <Code block>{JSON.stringify(response, null, 2)}</Code>
                </Card>
            )}
        </Stack>
    )
}
