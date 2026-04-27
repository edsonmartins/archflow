import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button, Card, Group, Stack, Text, Title } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { mcpApi } from '../services/mcp-api'
import { ApiError } from '../services/api'

/**
 * Lists every MCP server name registered on the backend. Clicking one
 * opens the detail page, which performs the connect/initialize dance
 * lazily and shows tools, prompts and resources.
 */
export default function McpServersPage() {
    const { t } = useTranslation()
    const [names, setNames]     = useState<string[]>([])
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        mcpApi.listServers()
            .then(setNames)
            .catch(e => notifications.show({ color: 'red', title: 'MCP',
                message: e instanceof ApiError ? e.message : String(e) }))
            .finally(() => setLoading(false))
    }, [])

    const renderRich = (key: string) =>
        t(key).split(/<code>|<\/code>/).map((seg, i) =>
            i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)

    return (
        <Stack gap="md" style={{ padding: 24 }} data-testid="mcp-servers-page">
            <Title order={2}>{t('mcp.servers.title')}</Title>
            {loading && <Text c="dimmed">{t('triggers.loading')}</Text>}
            {!loading && names.length === 0 && (
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
                            <Link to={`/admin/mcp/${encodeURIComponent(name)}`}>
                                <Button variant="light">{t('mcp.servers.inspect')}</Button>
                            </Link>
                        </Group>
                    </Card>
                ))}
            </Stack>
        </Stack>
    )
}
