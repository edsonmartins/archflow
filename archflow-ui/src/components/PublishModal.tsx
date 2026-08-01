import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Alert, Anchor, Badge, Button, Code, Group, Modal, Paper, ScrollArea,
    Select, Stack, Tabs, Text, TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconApi, IconBolt, IconCode, IconHistory, IconPlug } from '@tabler/icons-react'
import { CopyIconButton } from './CopyIconButton'
import {
    ApiError, workflowApi, type WorkflowDeployment, type WorkflowVersion,
} from '../services/api'

/**
 * "Publish" surface for a workflow: how to call it from outside the app.
 * Mirrors the consumption trio the market converged on (REST endpoint,
 * streaming endpoint, embeddable component) using the endpoints that
 * already exist — POST /api/workflows/{id}/execute, the AG-UI SSE run
 * endpoint, and the <archflow-designer> web component build.
 */
export function PublishModal({
    workflowId,
    opened,
    onClose,
}: {
    workflowId: string
    opened: boolean
    onClose: () => void
}) {
    const { t } = useTranslation()
    const origin = window.location.origin
    const [comment, setComment] = useState('')
    const [publishing, setPublishing] = useState(false)
    const [published, setPublished] = useState<WorkflowVersion | null>(null)
    const [versions, setVersions] = useState<WorkflowVersion[]>([])
    const [deployment, setDeployment] = useState<WorkflowDeployment | null>(null)
    const [loadingHistory, setLoadingHistory] = useState(false)
    const [rollingBack, setRollingBack] = useState<string | null>(null)
    const [leftVersionId, setLeftVersionId] = useState<string | null>(null)
    const [rightVersionId, setRightVersionId] = useState<string | null>(null)

    const loadHistory = async () => {
        setLoadingHistory(true)
        try {
            const loadedVersions = await workflowApi.versions(workflowId)
            setVersions(loadedVersions)
            setRightVersionId((current) => current ?? loadedVersions[0]?.id ?? null)
            setLeftVersionId((current) => current ?? loadedVersions[1]?.id ?? null)
            try {
                setDeployment(await workflowApi.deployment(workflowId))
            } catch (error) {
                if (!(error instanceof ApiError && error.status === 404)) throw error
                setDeployment(null)
            }
        } catch (error) {
            notifications.show({
                color: 'red',
                message: t('editor.publish.historyError', { error: String(error) }),
            })
        } finally {
            setLoadingHistory(false)
        }
    }

    useEffect(() => {
        if (opened) void loadHistory()
    // Loading is deliberately keyed by modal/workflow, not by callback identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [opened, workflowId])

    const publish = async () => {
        setPublishing(true)
        try {
            const result = await workflowApi.publish(workflowId, comment || undefined)
            setPublished(result.version)
            setComment('')
            await loadHistory()
            notifications.show({
                color: 'teal',
                message: t('editor.publish.success', { number: result.version.number }),
            })
        } catch (error) {
            notifications.show({
                color: 'red',
                message: t('editor.publish.error', { error: String(error) }),
            })
        } finally {
            setPublishing(false)
        }
    }

    const rollback = async (version: WorkflowVersion) => {
        setRollingBack(version.id)
        try {
            const next = await workflowApi.deploy(workflowId, 'PRODUCTION', version.id)
            setDeployment(next)
            notifications.show({
                color: 'teal',
                message: t('editor.publish.rollbackSuccess', { number: version.number }),
            })
        } catch (error) {
            notifications.show({
                color: 'red',
                message: t('editor.publish.rollbackError', { error: String(error) }),
            })
        } finally {
            setRollingBack(null)
        }
    }

    const leftVersion = versions.find((version) => version.id === leftVersionId)
    const rightVersion = versions.find((version) => version.id === rightVersionId)
    const versionOptions = versions.map((version) => ({
        value: version.id,
        label: `v${version.number} · ${formatDate(version.createdAt)}`,
    }))
    const comparison = useMemo(
        () => compareDocuments(leftVersion?.document, rightVersion?.document),
        [leftVersion, rightVersion],
    )

    const restSnippets = [
        {
            label: 'curl',
            code: `curl -X POST '${origin}/api/workflows/${workflowId}/execute' \\
  -H 'Authorization: Bearer <API_KEY>' \\
  -H 'Content-Type: application/json' \\
  -d '{"input": "hello"}'`,
        },
        {
            label: 'JavaScript',
            code: `const res = await fetch('${origin}/api/workflows/${workflowId}/execute', {
  method: 'POST',
  headers: {
    Authorization: 'Bearer <API_KEY>',
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({ input: 'hello' }),
});
const { executionId } = await res.json();`,
        },
        {
            label: 'Python',
            code: `import requests

res = requests.post(
    '${origin}/api/workflows/${workflowId}/execute',
    headers={'Authorization': 'Bearer <API_KEY>'},
    json={'input': 'hello'},
)
execution_id = res.json()['executionId']`,
        },
    ]

    const streamSnippet = `curl -N -X POST '${origin}/ag-ui/workflows/${workflowId}' \\
  -H 'Authorization: Bearer <API_KEY>' \\
  -H 'Content-Type: application/json' \\
  -d '{"messages": [{"role": "user", "content": "hello"}]}'`

    const mcpSnippet = `{
  "mcpServers": {
    "archflow": {
      "type": "http",
      "url": "${origin}/mcp",
      "headers": { "Authorization": "Bearer <API_KEY>" }
    }
  }
}`

    const embedSnippet = `<script type="module" src="${origin}/archflow-designer.js"></script>

<archflow-designer
  workflow-id="${workflowId}"
  api-base="${origin}/api"
  height="600px">
</archflow-designer>`

    return (
        <Modal opened={opened} onClose={onClose} title={t('editor.publish.title')} size="lg" centered>
            <Stack gap="sm">
                <Text size="sm" c="dimmed">{t('editor.publish.subtitle')}</Text>
                <Group align="end">
                    <TextInput
                        style={{ flex: 1 }}
                        label={t('editor.publish.comment')}
                        placeholder={t('editor.publish.commentPlaceholder')}
                        value={comment}
                        onChange={(event) => setComment(event.currentTarget.value)}
                    />
                    <Button loading={publishing} onClick={publish}>
                        {t('editor.publish.createVersion')}
                    </Button>
                </Group>
                {published && (
                    <Alert color="teal">
                        {t('editor.publish.deployed', {
                            number: published.number,
                            id: published.id,
                        })}
                    </Alert>
                )}

                <Tabs defaultValue="rest">
                    <Tabs.List>
                        <Tabs.Tab value="rest" leftSection={<IconApi size={14} />}>
                            {t('editor.publish.tabs.rest')}
                        </Tabs.Tab>
                        <Tabs.Tab value="stream" leftSection={<IconBolt size={14} />}>
                            {t('editor.publish.tabs.stream')}
                        </Tabs.Tab>
                        <Tabs.Tab value="embed" leftSection={<IconCode size={14} />}>
                            {t('editor.publish.tabs.embed')}
                        </Tabs.Tab>
                        <Tabs.Tab value="mcp" leftSection={<IconPlug size={14} />}>
                            {t('editor.publish.tabs.mcp')}
                        </Tabs.Tab>
                        <Tabs.Tab value="history" leftSection={<IconHistory size={14} />}>
                            {t('editor.publish.tabs.history')}
                        </Tabs.Tab>
                    </Tabs.List>

                    <Tabs.Panel value="rest" pt="sm">
                        <Stack gap="sm">
                            <Text size="xs" c="dimmed">{t('editor.publish.restHint')}</Text>
                            {restSnippets.map((s) => (
                                <Snippet key={s.label} label={s.label} code={s.code} />
                            ))}
                        </Stack>
                    </Tabs.Panel>

                    <Tabs.Panel value="stream" pt="sm">
                        <Stack gap="sm">
                            <Text size="xs" c="dimmed">{t('editor.publish.streamHint')}</Text>
                            <Snippet label="curl" code={streamSnippet} />
                        </Stack>
                    </Tabs.Panel>

                    <Tabs.Panel value="embed" pt="sm">
                        <Stack gap="sm">
                            <Text size="xs" c="dimmed">{t('editor.publish.embedHint')}</Text>
                            <Snippet label="HTML" code={embedSnippet} />
                        </Stack>
                    </Tabs.Panel>

                    <Tabs.Panel value="mcp" pt="sm">
                        <Stack gap="sm">
                            <Text size="xs" c="dimmed">{t('editor.publish.mcpHint', { tool: `workflow_${workflowId}` })}</Text>
                            <Snippet label="mcp.json" code={mcpSnippet} />
                        </Stack>
                    </Tabs.Panel>

                    <Tabs.Panel value="history" pt="sm">
                        <Stack gap="sm">
                            <Group justify="space-between">
                                <Text size="xs" c="dimmed">
                                    {t('editor.publish.historyHint')}
                                </Text>
                                <Button variant="subtle" size="compact-xs"
                                    loading={loadingHistory} onClick={loadHistory}>
                                    {t('editor.publish.refresh')}
                                </Button>
                            </Group>
                            {versions.length === 0 && !loadingHistory ? (
                                <Alert color="gray">{t('editor.publish.noVersions')}</Alert>
                            ) : versions.map((version) => {
                                const active = deployment?.versionId === version.id
                                return (
                                    <Paper key={version.id} withBorder p="sm">
                                        <Group justify="space-between" align="center">
                                            <div>
                                                <Group gap="xs">
                                                    <Text fw={600}>v{version.number}</Text>
                                                    {active && (
                                                        <Badge color="teal" size="sm">
                                                            PRODUCTION
                                                        </Badge>
                                                    )}
                                                </Group>
                                                <Text size="xs" c="dimmed">
                                                    {formatDate(version.createdAt)}
                                                    {version.comment ? ` · ${version.comment}` : ''}
                                                </Text>
                                            </div>
                                            <Button size="compact-xs" variant="light"
                                                disabled={active}
                                                loading={rollingBack === version.id}
                                                onClick={() => rollback(version)}>
                                                {active
                                                    ? t('editor.publish.active')
                                                    : t('editor.publish.rollback')}
                                            </Button>
                                        </Group>
                                    </Paper>
                                )
                            })}

                            {versions.length >= 2 && (
                                <>
                                    <Text fw={600} size="sm">
                                        {t('editor.publish.compare')}
                                    </Text>
                                    <Group grow>
                                        <Select data={versionOptions} value={leftVersionId}
                                            onChange={setLeftVersionId}
                                            label={t('editor.publish.baseVersion')} />
                                        <Select data={versionOptions} value={rightVersionId}
                                            onChange={setRightVersionId}
                                            label={t('editor.publish.targetVersion')} />
                                    </Group>
                                    <ScrollArea h={260}>
                                        <Code block style={{
                                            fontSize: 11,
                                            whiteSpace: 'pre',
                                            color: comparison.changed ? undefined : 'var(--mantine-color-dimmed)',
                                        }}>
                                            {comparison.text}
                                        </Code>
                                    </ScrollArea>
                                </>
                            )}
                        </Stack>
                    </Tabs.Panel>
                </Tabs>

                <Text size="xs" c="dimmed">
                    {t('editor.publish.apiKeyHint')}{' '}
                    <Anchor component={Link} to="/admin/workspace/api-keys" size="xs">
                        {t('editor.publish.apiKeyLink')}
                    </Anchor>
                </Text>
            </Stack>
        </Modal>
    )
}

function formatDate(value: string) {
    return new Intl.DateTimeFormat(undefined, {
        dateStyle: 'short',
        timeStyle: 'short',
    }).format(new Date(value))
}

function compareDocuments(left?: unknown, right?: unknown) {
    if (!left || !right) return { changed: false, text: '' }
    const leftLines = JSON.stringify(left, null, 2).split('\n')
    const rightLines = JSON.stringify(right, null, 2).split('\n')
    const lines: string[] = []
    const length = Math.max(leftLines.length, rightLines.length)
    let changed = false
    for (let index = 0; index < length; index += 1) {
        const before = leftLines[index]
        const after = rightLines[index]
        if (before === after) {
            lines.push(`  ${before ?? ''}`)
        } else {
            changed = true
            if (before !== undefined) lines.push(`- ${before}`)
            if (after !== undefined) lines.push(`+ ${after}`)
        }
    }
    return { changed, text: lines.join('\n') }
}

function Snippet({ label, code }: { label: string; code: string }) {
    return (
        <div style={{ position: 'relative' }}>
            <Group justify="space-between" mb={4}>
                <Text size="xs" fw={600} c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                    {label}
                </Text>
                <CopyIconButton value={code} />
            </Group>
            <Code block style={{ fontSize: 11.5, whiteSpace: 'pre' }}>{code}</Code>
        </div>
    )
}
