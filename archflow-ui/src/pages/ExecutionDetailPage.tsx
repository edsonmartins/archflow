import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Badge, Button, Card, Code, Group, Stack, Text, Title } from '@mantine/core'
import { executionApi } from '../services/api'
import { ApiError } from '../services/api'

/**
 * Details a single execution. Fetches {@code /api/executions/{id}} for
 * base info and, when a {@code traceId} is present in the response,
 * links to the full trace for span-level detail.
 */
export default function ExecutionDetailPage() {
    const { t } = useTranslation()
    const { id = '' } = useParams()
    const [data, setData] = useState<Record<string, unknown> | null>(null)
    const [err, setErr]   = useState<string | null>(null)

    useEffect(() => {
        (async () => {
            try {
                const res = await executionApi.get(id)
                setData(res as unknown as Record<string, unknown>)
            } catch (e) {
                setErr(e instanceof ApiError ? e.message : String(e))
            }
        })()
    }, [id])

    if (err) return (
        <Stack gap="md" style={{ padding: 24 }} data-testid="execution-detail-error">
            <Text c="red">{err}</Text>
            <Link to="/executions"><Button variant="default">{t('executionDetail.back')}</Button></Link>
        </Stack>
    )
    if (!data) return <Text style={{ padding: 24 }}>{t('triggers.loading')}</Text>

    const status = String(data.status ?? '')
    const traceId = data.traceId as string | undefined

    return (
        <Stack gap="md" style={{ padding: 24 }} data-testid="execution-detail">
            <Group justify="space-between">
                <Group>
                    <Title order={2}>{t('executionDetail.title', { id: id.slice(0, 12) })}</Title>
                    {status && <Badge color={status === 'COMPLETED' ? 'green' : status === 'FAILED' ? 'red' : 'yellow'}>{t(`executions.statuses.${status}`, { defaultValue: status })}</Badge>}
                </Group>
                <Group>
                    {traceId && (
                        <Link to={`/admin/observability/traces/${traceId}`}>
                            <Button variant="light">{t('executionDetail.viewTrace')}</Button>
                        </Link>
                    )}
                    <Link to="/executions"><Button variant="default">{t('executionDetail.back')}</Button></Link>
                </Group>
            </Group>
            <Card withBorder>
                <Code block>{JSON.stringify(data, null, 2)}</Code>
            </Card>
        </Stack>
    )
}
