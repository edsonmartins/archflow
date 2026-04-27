import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Accordion, Alert, Badge, Button, Card, Code, Group, Stack, Text, Title,
} from '@mantine/core'
import { mcpApi, type McpIntrospection } from '../services/mcp-api'
import { ApiError } from '../services/api'

/**
 * Shows the tools, prompts and resources exposed by a single MCP
 * server. The backend opens the connection lazily on first request,
 * so this page may take a moment on cold start.
 */
export default function McpServerDetailPage() {
    const { t } = useTranslation()
    const { name = '' } = useParams()
    const [data, setData]   = useState<McpIntrospection | null>(null)
    const [err, setErr]     = useState<string | null>(null)
    const [loading, setLoading] = useState(true)

    const load = () => {
        setLoading(true); setErr(null)
        mcpApi.introspect(name)
            .then(setData)
            .catch(e => setErr(e instanceof ApiError ? e.message : String(e)))
            .finally(() => setLoading(false))
    }
    useEffect(load, [name])

    return (
        <Stack gap="md" style={{ padding: 24 }} data-testid="mcp-detail">
            <Group justify="space-between">
                <Group>
                    <Title order={2}>{name}</Title>
                    {data && (data.connected
                        ? <Badge color="green">{t('mcp.detail.connected')}</Badge>
                        : <Badge color="red">{t('mcp.detail.offline')}</Badge>)}
                </Group>
                <Group>
                    <Button variant="default" onClick={load}>{t('mcp.detail.reconnect')}</Button>
                    <Link to="/admin/mcp"><Button variant="default">{t('mcp.detail.back')}</Button></Link>
                </Group>
            </Group>

            {loading && <Text c="dimmed">{t('mcp.detail.inspecting')}</Text>}
            {err && <Alert color="red" title={t('mcp.detail.inspectFailed')}>{err}</Alert>}
            {data?.error && <Alert color="yellow" title={t('mcp.detail.serverError')}>{data.error}</Alert>}

            {data && (
                <Accordion multiple defaultValue={['tools']}>
                    <Accordion.Item value="tools">
                        <Accordion.Control>{t('mcp.detail.tools', { count: data.tools.length })}</Accordion.Control>
                        <Accordion.Panel>
                            <Stack gap="xs">
                                {data.tools.length === 0 && <Text c="dimmed">{t('mcp.detail.noTools')}</Text>}
                                {data.tools.map(t => (
                                    <Card key={t.name} withBorder data-testid={`mcp-tool-${t.name}`}>
                                        <Stack gap={4}>
                                            <Group gap="xs">
                                                <Text fw={600}>{t.name}</Text>
                                            </Group>
                                            <Text size="sm" c="dimmed">{t.description}</Text>
                                            <Code block>{JSON.stringify(t.inputSchema, null, 2)}</Code>
                                        </Stack>
                                    </Card>
                                ))}
                            </Stack>
                        </Accordion.Panel>
                    </Accordion.Item>

                    <Accordion.Item value="prompts">
                        <Accordion.Control>{t('mcp.detail.prompts', { count: data.prompts.length })}</Accordion.Control>
                        <Accordion.Panel>
                            <Stack gap="xs">
                                {data.prompts.length === 0 && <Text c="dimmed">{t('mcp.detail.noPrompts')}</Text>}
                                {data.prompts.map(p => (
                                    <Card key={p.name} withBorder>
                                        <Stack gap={4}>
                                            <Text fw={600}>{p.name}</Text>
                                            <Text size="sm" c="dimmed">{p.description}</Text>
                                            {p.arguments.length > 0 && (
                                                <Group gap={4}>
                                                    {p.arguments.map(a => (
                                                        <Badge key={a.name} size="sm"
                                                               variant={a.required ? 'filled' : 'outline'}>
                                                            {a.name}
                                                        </Badge>
                                                    ))}
                                                </Group>
                                            )}
                                        </Stack>
                                    </Card>
                                ))}
                            </Stack>
                        </Accordion.Panel>
                    </Accordion.Item>

                    <Accordion.Item value="resources">
                        <Accordion.Control>{t('mcp.detail.resources', { count: data.resources.length })}</Accordion.Control>
                        <Accordion.Panel>
                            <Stack gap="xs">
                                {data.resources.length === 0 && <Text c="dimmed">{t('mcp.detail.noResources')}</Text>}
                                {data.resources.map(r => (
                                    <Card key={r.name} withBorder>
                                        <Stack gap={4}>
                                            <Text fw={600}>{r.name}</Text>
                                            {r.uri && <Text size="xs" c="dimmed"><Code>{r.uri}</Code></Text>}
                                            {r.description && <Text size="sm" c="dimmed">{r.description}</Text>}
                                            {r.mimeType && <Badge size="xs" variant="outline">{r.mimeType}</Badge>}
                                        </Stack>
                                    </Card>
                                ))}
                            </Stack>
                        </Accordion.Panel>
                    </Accordion.Item>
                </Accordion>
            )}
        </Stack>
    )
}
