import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Alert, Button, Card, Group, Skeleton, Stack, Text } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { mcpApi } from '../services/mcp-api'
import { ApiError } from '../services/api'
import { PageHeader } from '../components/PageHeader'

/**
 * Lists every MCP server name registered on the backend. Clicking one
 * opens the detail page, which performs the connect/initialize dance
 * lazily and shows tools, prompts and resources.
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
        <Stack gap="md" data-testid="mcp-servers-page">
            <PageHeader
                title={t('mcp.servers.title')}
                actions={<Button variant="default" onClick={load}>{t('common.refresh')}</Button>}
            />

            {error && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>{error}</Alert>
            )}

            {loading && (
                <Stack gap="sm" aria-hidden>
                    {[0, 1, 2].map(i => <Skeleton key={i} height={72} radius="md" />)}
                </Stack>
            )}

            {!loading && !error && names.length === 0 && (
                <Card withBorder><Stack>
                    <Text c="dimmed">{t('mcp.servers.emptyTitle')}</Text>
                    <Text size="sm" c="dimmed">{renderRich('mcp.servers.emptyHint')}</Text>
                </Stack></Card>
            )}

            <Stack gap="sm">
                {names.map(name => (
                    <Card key={name} withBorder data-testid={`mcp-${name}`}>
                        <Group justify="space-between">
                            <Text fw={600}>{name}</Text>
                            <Button component={Link} to={`/admin/mcp/${encodeURIComponent(name)}`} variant="light">
                                {t('mcp.servers.inspect')}
                            </Button>
                        </Group>
                    </Card>
                ))}
            </Stack>
        </Stack>
    )
}
