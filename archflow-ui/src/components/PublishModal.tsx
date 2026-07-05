import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Anchor, Code, Group, Modal, Stack, Tabs, Text,
} from '@mantine/core'
import { IconApi, IconBolt, IconCode, IconPlug } from '@tabler/icons-react'
import { CopyIconButton } from './CopyIconButton'

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
