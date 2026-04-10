import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, ActionIcon,
  Tooltip, Modal, TextInput, Select,
} from '@mantine/core'
import { IconPlus, IconPencil, IconTrash } from '@tabler/icons-react'
import { useState } from 'react'
import { notifications } from '@mantine/notifications'

const MOCK_USERS = [
  { id: 'u1', name: 'João Silva',    email: 'joao@rioquality.com.br',   role: 'admin',  status: 'active' as const, lastAccess: '2026-04-09', workflows: 8 },
  { id: 'u2', name: 'Maria Santos',  email: 'maria@rioquality.com.br',  role: 'editor', status: 'active' as const, lastAccess: '2026-04-08', workflows: 4 },
  { id: 'u3', name: 'Pedro Costa',   email: 'pedro@rioquality.com.br',  role: 'viewer', status: 'active' as const, lastAccess: '2026-04-05', workflows: 0 },
  { id: 'u4', name: 'Ana Oliveira',  email: 'ana@rioquality.com.br',    role: 'editor', status: 'invited' as const, lastAccess: null,         workflows: 0 },
]

const ROLE_COLORS: Record<string, string> = { admin: 'blue', editor: 'teal', viewer: 'gray' }

export default function UserManagement() {
  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<string | null>('editor')

  const handleInvite = () => {
    notifications.show({ title: 'Invitation sent', message: `Invite sent to ${inviteEmail}`, color: 'teal' })
    setInviteOpen(false)
    setInviteEmail('')
  }

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={3}>Users</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setInviteOpen(true)}>
          Invite User
        </Button>
      </Group>

      {/* Permission matrix */}
      <Paper withBorder p="md" radius="lg">
        <Text fw={600} size="sm" mb="sm">Permission Matrix</Text>
        <Table withColumnBorders>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Permission</Table.Th>
              <Table.Th ta="center">Admin</Table.Th>
              <Table.Th ta="center">Editor</Table.Th>
              <Table.Th ta="center">Viewer</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {[
              ['View workflows', true, true, true],
              ['Create/edit workflows', true, true, false],
              ['Execute workflows', true, true, false],
              ['Manage users', true, false, false],
              ['Manage API keys', true, false, false],
            ].map(([perm, admin, editor, viewer]) => (
              <Table.Tr key={perm as string}>
                <Table.Td><Text size="sm">{perm as string}</Text></Table.Td>
                <Table.Td ta="center">{admin  ? '✓' : '—'}</Table.Td>
                <Table.Td ta="center">{editor ? '✓' : '—'}</Table.Td>
                <Table.Td ta="center">{viewer ? '✓' : '—'}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>

      {/* User table */}
      <Paper withBorder radius="lg" style={{ overflow: 'hidden' }}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>User</Table.Th>
              <Table.Th>Role</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Last access</Table.Th>
              <Table.Th>Workflows</Table.Th>
              <Table.Th w={80}>Actions</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {MOCK_USERS.map(u => (
              <Table.Tr key={u.id}>
                <Table.Td>
                  <Group gap="sm" wrap="nowrap">
                    <div style={{
                      width: 32, height: 32, borderRadius: '50%',
                      background: '#378ADD15', color: '#378ADD',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontWeight: 700, fontSize: 11,
                    }}>
                      {u.name.split(' ').map(n => n[0]).join('')}
                    </div>
                    <div>
                      <Text size="sm" fw={500}>{u.name}</Text>
                      <Text size="xs" c="dimmed">{u.email}</Text>
                    </div>
                  </Group>
                </Table.Td>
                <Table.Td><Badge color={ROLE_COLORS[u.role]} size="sm">{u.role}</Badge></Table.Td>
                <Table.Td>
                  <Badge color={u.status === 'active' ? 'green' : 'orange'} size="sm" variant="light">
                    {u.status === 'invited' ? 'Invite pending' : 'Active'}
                  </Badge>
                </Table.Td>
                <Table.Td><Text size="xs" c="dimmed">{u.lastAccess ?? '—'}</Text></Table.Td>
                <Table.Td><Text size="sm">{u.workflows}</Text></Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <Tooltip label="Edit role">
                      <ActionIcon variant="subtle" size="sm"><IconPencil size={14} /></ActionIcon>
                    </Tooltip>
                    <Tooltip label="Remove">
                      <ActionIcon variant="subtle" color="red" size="sm"><IconTrash size={14} /></ActionIcon>
                    </Tooltip>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>

      {/* Invite modal */}
      <Modal opened={inviteOpen} onClose={() => setInviteOpen(false)} title="Invite User" centered>
        <Stack gap="md">
          <TextInput label="Email" required placeholder="user@company.com" value={inviteEmail}
            onChange={e => setInviteEmail(e.currentTarget.value)} />
          <Select label="Role" required data={[
            { value: 'admin', label: 'Admin' },
            { value: 'editor', label: 'Editor' },
            { value: 'viewer', label: 'Viewer' },
          ]} value={inviteRole} onChange={setInviteRole} />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setInviteOpen(false)}>Cancel</Button>
            <Button onClick={handleInvite} disabled={!inviteEmail}>Send Invite</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
