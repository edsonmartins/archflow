import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, ActionIcon,
  Tooltip, Modal, TextInput, Select, CopyButton, LoadingOverlay, Alert,
} from '@mantine/core'
import { IconPlus, IconCopy, IconTrash, IconCheck, IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { notifications } from '@mantine/notifications'
import { apiKeyApi, type ApiKey } from '../../../services/admin-api'

const TYPE_CONFIG: Record<string, { color: string; label: string }> = {
  production:    { color: 'green',  label: 'Production' },
  staging:       { color: 'yellow', label: 'Staging' },
  web_component: { color: 'blue',   label: 'Web Component' },
}

export default function ApiKeys() {
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [keyName, setKeyName] = useState('')
  const [keyType, setKeyType] = useState<string | null>('production')
  const [newKey, setNewKey] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      setKeys(await apiKeyApi.list())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load API keys')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const handleCreate = async () => {
    if (!keyType || !keyName) return
    try {
      const created = await apiKeyApi.create({ name: keyName, type: keyType as any })
      setKeys((prev) => [...prev, created])
      setNewKey(created.fullKey)
      notifications.show({ title: 'API Key created', message: 'Copy the key now — it won\'t be shown again', color: 'teal' })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create key')
    }
  }

  const handleRevoke = async (key: ApiKey) => {
    try {
      await apiKeyApi.revoke(key.id)
      setKeys((prev) => prev.filter((entry) => entry.id !== key.id))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to revoke key')
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />

      <Group justify="space-between">
        <Title order={3}>API Keys</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => { setCreateOpen(true); setNewKey(null); setKeyName('') }}>
          Create Key
        </Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <Paper withBorder radius="lg" style={{ overflow: 'hidden' }}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>Type</Table.Th>
              <Table.Th>Key</Table.Th>
              <Table.Th>Created</Table.Th>
              <Table.Th>Last used</Table.Th>
              <Table.Th w={80}>Actions</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {keys.map(k => {
              const cfg = TYPE_CONFIG[k.type] ?? { color: 'gray', label: k.type }
              return (
                <Table.Tr key={k.id}>
                  <Table.Td><Text size="sm" fw={500}>{k.name}</Text></Table.Td>
                  <Table.Td><Badge color={cfg.color} size="sm" variant="light">{cfg.label}</Badge></Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      <Text size="xs" ff="monospace" c="dimmed">{k.maskedKey}</Text>
                      <CopyButton value={k.maskedKey}>
                        {({ copied, copy }) => (
                          <Tooltip label={copied ? 'Copied' : 'Copy'}>
                            <ActionIcon variant="subtle" size="xs" onClick={copy} color={copied ? 'teal' : 'gray'}>
                              {copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
                            </ActionIcon>
                          </Tooltip>
                        )}
                      </CopyButton>
                    </Group>
                  </Table.Td>
                  <Table.Td><Text size="xs" c="dimmed">{k.createdAt}</Text></Table.Td>
                  <Table.Td><Text size="xs" c="dimmed">{k.lastUsedAt ?? '—'}</Text></Table.Td>
                  <Table.Td>
                    <Tooltip label="Revoke">
                      <ActionIcon variant="subtle" color="red" size="sm" onClick={() => void handleRevoke(k)}>
                        <IconTrash size={14} />
                      </ActionIcon>
                    </Tooltip>
                  </Table.Td>
                </Table.Tr>
              )
            })}
          </Table.Tbody>
        </Table>
      </Paper>

      <Modal opened={createOpen} onClose={() => setCreateOpen(false)} title="Create API Key" centered>
        <Stack gap="md">
          {newKey ? (
            <>
              <Text size="sm" fw={500}>Your new API key:</Text>
              <Paper withBorder p="sm" radius="md" bg="var(--color-background-tertiary)">
                <Group gap="xs">
                  <Text size="sm" ff="monospace" style={{ wordBreak: 'break-all' }}>{newKey}</Text>
                  <CopyButton value={newKey}>
                    {({ copied, copy }) => (
                      <Button size="xs" variant="light" onClick={copy} leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}>
                        {copied ? 'Copied!' : 'Copy'}
                      </Button>
                    )}
                  </CopyButton>
                </Group>
              </Paper>
              <Text size="xs" c="red">This key will not be shown again. Store it securely.</Text>
              <Button onClick={() => setCreateOpen(false)}>Done</Button>
            </>
          ) : (
            <>
              <TextInput label="Key name" required placeholder="My integration" value={keyName}
                onChange={e => setKeyName(e.currentTarget.value)} />
              <Select label="Type" required data={[
                { value: 'production',    label: 'Production' },
                { value: 'staging',       label: 'Staging' },
                { value: 'web_component', label: 'Web Component' },
              ]} value={keyType} onChange={setKeyType} />
              <Group justify="flex-end">
                <Button variant="light" onClick={() => setCreateOpen(false)}>Cancel</Button>
                <Button onClick={() => void handleCreate()} disabled={!keyName}>Create</Button>
              </Group>
            </>
          )}
        </Stack>
      </Modal>
    </Stack>
  )
}
