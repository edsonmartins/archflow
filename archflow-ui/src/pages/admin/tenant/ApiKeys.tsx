import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, ActionIcon,
  Tooltip, Modal, TextInput, Select, CopyButton,
} from '@mantine/core'
import { IconPlus, IconCopy, IconTrash, IconCheck } from '@tabler/icons-react'
import { useState } from 'react'
import { notifications } from '@mantine/notifications'

const TYPE_CONFIG: Record<string, { color: string; prefix: string; label: string }> = {
  production:    { color: 'green',  prefix: 'af_live_', label: 'Production' },
  staging:       { color: 'yellow', prefix: 'af_test_', label: 'Staging' },
  web_component: { color: 'blue',   prefix: 'af_pub_',  label: 'Web Component' },
}

const MOCK_KEYS = [
  { id: 'k1', name: 'VendaX Backend',     type: 'production',    maskedKey: 'af_live_rq_••••••••3f2a', createdAt: '2026-01-15', lastUsedAt: '2026-04-09' },
  { id: 'k2', name: 'Staging Tests',      type: 'staging',       maskedKey: 'af_test_rq_••••••••b8e1', createdAt: '2026-03-01', lastUsedAt: '2026-04-08' },
  { id: 'k3', name: 'Embeddable Designer', type: 'web_component', maskedKey: 'af_pub_rq_••••••••9d44', createdAt: '2026-02-20', lastUsedAt: null },
]

export default function ApiKeys() {
  const [createOpen, setCreateOpen] = useState(false)
  const [keyName, setKeyName] = useState('')
  const [keyType, setKeyType] = useState<string | null>('production')
  const [newKey, setNewKey] = useState<string | null>(null)

  const handleCreate = () => {
    const generated = `${TYPE_CONFIG[keyType!]?.prefix}rq_${Math.random().toString(36).slice(2, 18)}`
    setNewKey(generated)
    notifications.show({ title: 'API Key created', message: 'Copy the key now — it won\'t be shown again', color: 'teal' })
  }

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={3}>API Keys</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => { setCreateOpen(true); setNewKey(null); setKeyName(''); }}>
          Create Key
        </Button>
      </Group>

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
            {MOCK_KEYS.map(k => {
              const cfg = TYPE_CONFIG[k.type]
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
                      <ActionIcon variant="subtle" color="red" size="sm">
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
                { value: 'production',    label: 'Production (af_live_)' },
                { value: 'staging',       label: 'Staging (af_test_)' },
                { value: 'web_component', label: 'Web Component (af_pub_)' },
              ]} value={keyType} onChange={setKeyType} />
              <Group justify="flex-end">
                <Button variant="light" onClick={() => setCreateOpen(false)}>Cancel</Button>
                <Button onClick={handleCreate} disabled={!keyName}>Create</Button>
              </Group>
            </>
          )}
        </Stack>
      </Modal>
    </Stack>
  )
}
