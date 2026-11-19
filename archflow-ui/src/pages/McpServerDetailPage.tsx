import { useEffect, useState, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Accordion, Alert, Badge, Button, Code, Group, Paper, SimpleGrid, Skeleton, Stack, Text, ThemeIcon,
} from '@mantine/core'
import {
    IconAlertCircle, IconAlertTriangle, IconDatabase, IconMessage2, IconPlug, IconPlugConnected,
    IconPlugConnectedX, IconRefresh, IconTool,
} from '@tabler/icons-react'
import { mcpApi, type McpIntrospection } from '../services/mcp-api'
import { JsonViewer } from '../components/JsonViewer'
import { ApiError } from '../services/api'
import { PageHeader } from '../components/PageHeader'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'

/** Accordion section heading: icon + label (the label keeps the "Name (n)" text the button is found by). */
function SectionControl({ icon, color, label }: { icon: ReactNode; color: string; label: string }) {
    return (
        <Group gap="sm" wrap="nowrap">
            <ThemeIcon size={30} radius="md" variant="light" color={color}>{icon}</ThemeIcon>
            <Text fw={600} size="sm">{label}</Text>
        </Group>
    )
}

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

    const dash = loading && !data ? '—' : null

    return (
        <Stack gap="lg" p="md" data-testid="mcp-detail">
            <PageHeader
                icon={IconPlug}
                accent="teal"
                title={
                    <Group gap="sm" component="span">
                        <span>{name}</span>
                        {data && (data.connected
                            ? <Badge color="teal" variant="light">{t('mcp.detail.connected')}</Badge>
                            : <Badge color="red" variant="light">{t('mcp.detail.offline')}</Badge>)}
                    </Group>
                }
                subtitle={t('mcp.detail.subtitle')}
                breadcrumbs={[
                    { label: t('mcp.servers.title'), to: '/admin/mcp' },
                    { label: name },
                ]}
                backTo="/admin/mcp"
                backLabel={t('mcp.detail.back')}
                actions={
                    <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={load} loading={loading}>
                        {t('mcp.detail.reconnect')}
                    </Button>
                }
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('mcp.detail.stats.status')}
                    value={dash ?? (data?.connected ? t('mcp.detail.connected') : t('mcp.detail.offline'))}
                    color={data && !data.connected ? 'red' : undefined}
                    icon={data && !data.connected ? IconPlugConnectedX : IconPlugConnected}
                    accent={data && !data.connected ? 'rose' : 'teal'}
                    hint={loading ? t('mcp.detail.inspecting') : t('mcp.detail.stats.statusHint')}
                />
                <StatCard label={t('mcp.detail.stats.tools')} value={dash ?? data?.tools.length ?? 0}
                          icon={IconTool} accent="blue" hint={t('mcp.detail.stats.toolsHint')} />
                <StatCard label={t('mcp.detail.stats.prompts')} value={dash ?? data?.prompts.length ?? 0}
                          icon={IconMessage2} accent="violet" hint={t('mcp.detail.stats.promptsHint')} />
                <StatCard label={t('mcp.detail.stats.resources')} value={dash ?? data?.resources.length ?? 0}
                          icon={IconDatabase} accent="amber" hint={t('mcp.detail.stats.resourcesHint')} />
            </SimpleGrid>

            {err && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />} title={t('mcp.detail.inspectFailed')}>
                    {err}
                </Alert>
            )}
            {data?.error && (
                <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />} title={t('mcp.detail.serverError')}>
                    {data.error}
                </Alert>
            )}

            {loading && !data && (
                <Stack gap="sm" aria-hidden>
                    {[0, 1, 2].map(i => <Skeleton key={i} height={60} radius="md" />)}
                </Stack>
            )}

            {data && (
                <Accordion
                    multiple
                    defaultValue={['tools']}
                    variant="separated"
                    radius="lg"
                    styles={{
                        item: {
                            background: 'var(--surface-bg)',
                            border: '1px solid var(--surface-border)',
                            boxShadow: 'var(--surface-shadow)',
                        },
                    }}
                >
                    <Accordion.Item value="tools">
                        <Accordion.Control>
                            <SectionControl icon={<IconTool size={16} />} color="blue"
                                            label={t('mcp.detail.tools', { count: data.tools.length })} />
                        </Accordion.Control>
                        <Accordion.Panel>
                            {data.tools.length === 0 ? (
                                <EmptyState compact accent="blue" icon={<IconTool />} title={t('mcp.detail.noTools')} />
                            ) : (
                                <Stack gap="sm">
                                    {data.tools.map(tool => (
                                        <Paper key={tool.name} withBorder p="md" data-testid={`mcp-tool-${tool.name}`}>
                                            <Stack gap="xs">
                                                <Group gap="xs">
                                                    <Code fw={600}>{tool.name}</Code>
                                                </Group>
                                                {tool.description && <Text size="sm" c="dimmed">{tool.description}</Text>}
                                                <Text size="xs" fw={600} c="dimmed" tt="uppercase" style={{ letterSpacing: '0.06em' }}>
                                                    {t('mcp.detail.inputSchema')}
                                                </Text>
                                                <JsonViewer value={tool.inputSchema} />
                                            </Stack>
                                        </Paper>
                                    ))}
                                </Stack>
                            )}
                        </Accordion.Panel>
                    </Accordion.Item>

                    <Accordion.Item value="prompts">
                        <Accordion.Control>
                            <SectionControl icon={<IconMessage2 size={16} />} color="violet"
                                            label={t('mcp.detail.prompts', { count: data.prompts.length })} />
                        </Accordion.Control>
                        <Accordion.Panel>
                            {data.prompts.length === 0 ? (
                                <EmptyState compact accent="violet" icon={<IconMessage2 />} title={t('mcp.detail.noPrompts')} />
                            ) : (
                                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="sm">
                                    {data.prompts.map(p => (
                                        <Paper key={p.name} withBorder p="md">
                                            <Stack gap={6}>
                                                <Code fw={600} w="fit-content">{p.name}</Code>
                                                {p.description && <Text size="sm" c="dimmed">{p.description}</Text>}
                                                {p.arguments.length > 0 && (
                                                    <Group gap={4}>
                                                        {p.arguments.map(a => (
                                                            <Badge key={a.name} size="sm" color="violet"
                                                                   variant={a.required ? 'filled' : 'outline'}
                                                                   title={a.description}>
                                                                {a.name}
                                                            </Badge>
                                                        ))}
                                                    </Group>
                                                )}
                                            </Stack>
                                        </Paper>
                                    ))}
                                </SimpleGrid>
                            )}
                        </Accordion.Panel>
                    </Accordion.Item>

                    <Accordion.Item value="resources">
                        <Accordion.Control>
                            <SectionControl icon={<IconDatabase size={16} />} color="orange"
                                            label={t('mcp.detail.resources', { count: data.resources.length })} />
                        </Accordion.Control>
                        <Accordion.Panel>
                            {data.resources.length === 0 ? (
                                <EmptyState compact accent="amber" icon={<IconDatabase />} title={t('mcp.detail.noResources')} />
                            ) : (
                                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="sm">
                                    {data.resources.map(r => (
                                        <Paper key={r.name} withBorder p="md">
                                            <Stack gap={6}>
                                                <Group justify="space-between" wrap="nowrap" gap="xs">
                                                    <Text fw={600} truncate>{r.name}</Text>
                                                    {r.mimeType && <Badge size="xs" variant="outline" color="gray">{r.mimeType}</Badge>}
                                                </Group>
                                                {r.uri && <Code w="fit-content">{r.uri}</Code>}
                                                {r.description && <Text size="sm" c="dimmed">{r.description}</Text>}
                                            </Stack>
                                        </Paper>
                                    ))}
                                </SimpleGrid>
                            )}
                        </Accordion.Panel>
                    </Accordion.Item>
                </Accordion>
            )}
        </Stack>
    )
}
