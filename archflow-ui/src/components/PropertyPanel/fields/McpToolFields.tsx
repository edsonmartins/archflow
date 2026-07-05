import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Select, TextInput, Textarea, Text } from '@mantine/core'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { getConfig } from './helpers'
import type { FieldProps } from './FieldProps'
import { mcpApi } from '../../../services/mcp-api'

export function McpToolFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string) => t(`editor.properties.fields.${key}`)
  const [servers, setServers] = useState<string[]>([])
  const [tools, setTools]     = useState<{ name: string; description: string }[]>([])
  const [loading, setLoading] = useState(false)

  const selectedServer = getConfig(nodeData, 'mcpServer', '') as string
  const selectedTool   = getConfig(nodeData, 'mcpTool',   '') as string

  useEffect(() => {
    // Pull the list of registered MCP servers; default bean ships with
    // Linktor when enabled.
    mcpApi.listServers().then(setServers).catch(() => setServers([]))
  }, [])

  useEffect(() => {
    if (!selectedServer) { setTools([]); return }
    setLoading(true)
    mcpApi.introspect(selectedServer)
        .then(x => setTools(x.tools.map(t => ({ name: t.name, description: t.description }))))
        .catch(() => setTools([]))
        .finally(() => setLoading(false))
  }, [selectedServer])

  return (
    <>
      <Select
        label={f('mcpServer')}
        value={selectedServer || null}
        onChange={v => { update('mcpServer', v ?? ''); update('mcpTool', '') }}
        size="xs"
        data={servers.map(s => ({ value: s, label: s }))}
        placeholder={servers.length ? f('pickServer') : f('noMcpServers')}
        styles={FIELD_STYLES}
      />
      <Select
        label={f('tool')}
        value={selectedTool || null}
        onChange={v => update('mcpTool', v ?? '')}
        size="xs"
        data={tools.map(tool => ({ value: tool.name, label: tool.name }))}
        searchable
        disabled={!selectedServer || loading}
        placeholder={selectedServer ? (loading ? f('loadingEllipsis') : f('pickTool')) : f('pickServerFirst')}
        styles={FIELD_STYLES}
      />
      {selectedTool && (
        <Text size="xs" c="dimmed" lh={1.4}>
          {tools.find(tool => tool.name === selectedTool)?.description}
        </Text>
      )}
      <Textarea
        label={f('argumentsJson')}
        value={getConfig(nodeData, 'mcpArguments', '{}')}
        onChange={e => update('mcpArguments', e.currentTarget.value)}
        autosize minRows={3}
        size="xs"
        styles={MONO_INPUT}
        description={f('argumentsHint')}
      />
      <TextInput
        label={f('outputVariable')}
        value={getConfig(nodeData, 'mcpOutputVar', 'toolResult')}
        onChange={e => update('mcpOutputVar', e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />
    </>
  )
}

// ── Subflow fields ──────────────────────────────────────────────
