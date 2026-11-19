import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Alert, Badge, Button, Group, Paper, SimpleGrid, Skeleton, Stack, Text, ThemeIcon } from '@mantine/core'
import { IconAlertCircle, IconArrowRight, IconPlug, IconRefresh, IconServer } from '@tabler/icons-react'
import { mcpApi } from '../services/mcp-api'
import { ApiError } from '../services/api'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import EmptyState from '../components/EmptyState'

/**
 * Lists every MCP server name registered on the backend. Clicking one
 * opens the detail page, which performs the connect/initialize dance
 * lazily and shows tools, prompts and resources.
 *
 * <p>The backend only returns names, so the servers render as a grid of
 * tiles (one action each) rather than a single-column table.</p>
 */
export default function McpServersPage() {
    const { t } = useTranslation()
    const [names, setNames]     = useState<string[]>([])
    const [loading, setLoading] = useState(true)
    const [error, setError]     = useState<string | null>(null)

    const load = () => {
        setLoading(true)
        setError(null)
        mcpApi.listServers()
            .then(setNames)
            .catch(e => setError(e instanceof ApiError ? e.message : String(e)))
            .finally(() => setLoading(false))
    }

    useEffect(load, [])

    const renderRich = (key: string) =>
        t(key).split(/<code>|<\/code>/).map((seg, i) =>
            i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)

    return (
        <Stack gap="lg" p="md" data-testid="mcp-servers-page">
            <PageHeader
                icon={IconPlug}
                accent="teal"
                title={t('mcp.servers.title')}
                subtitle={t('mcp.servers.subtitle')}
                actions={
                    <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={load}>
                        {t('common.refresh')}
                    </Button>
                }
            />

            <SectionCard
                icon={IconServer}
                accent="teal"
                title={t('mcp.servers.listTitle')}
                description={t('mcp.servers.listHint')}
                actions={!loading && !error && (
                    <Badge variant="light" color="teal">{t('mcp.servers.count', { count: names.length })}</Badge>
                )}
            >
                {error && (
                    <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                        <Group justify="space-between" wrap="nowrap" gap="sm">
                            <Text size="sm">{error}</Text>
                            <Button size="xs" variant="white" color="red" onClick={load}>{t('common.refresh')}</Button>
                        </Group>
                    </Alert>
                )}

                {loading && (
                    <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md" aria-hidden>
                        {[0, 1, 2].map(i => <Skeleton key={i} height={84} radius="md" />)}
                    </SimpleGrid>
                )}

                {!loading && !error && names.length === 0 && (
                    <EmptyState
                        compact
                        accent="teal"
                        icon={<IconPlug />}
                        title={t('mcp.servers.emptyTitle')}
                        description={renderRich('mcp.servers.emptyHint')}
                    />
                )}

                {!loading && names.length > 0 && (
                    <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                        {names.map(name => (
                            <Paper key={name} withBorder p="md" data-testid={`mcp-${name}`}>
                                <Group justify="space-between" wrap="nowrap" gap="sm">
                                    <Group gap="sm" wrap="nowrap" style={{ minWidth: 0 }}>
                                        <ThemeIcon size={38} radius="md" variant="light" color="teal">
                                            <IconServer size={18} stroke={1.8} />
                                        </ThemeIcon>
                                        <Stack gap={0} style={{ minWidth: 0 }}>
                                            <Text fw={600} truncate>{name}</Text>
                                            <Text size="xs" c="dimmed">{t('mcp.servers.tileHint')}</Text>
                                        </Stack>
                                    </Group>
                                    <Button
                                        component={Link}
                                        to={`/admin/mcp/${encodeURIComponent(name)}`}
                                        variant="light"
                                        color="teal"
                                        size="xs"
                                        rightSection={<IconArrowRight size={14} />}
                                        style={{ flexShrink: 0 }}
                                    >
                                        {t('mcp.servers.inspect')}
                                    </Button>
                                </Group>
                            </Paper>
                        ))}
                    </SimpleGrid>
                )}
            </SectionCard>
        </Stack>
    )
}
