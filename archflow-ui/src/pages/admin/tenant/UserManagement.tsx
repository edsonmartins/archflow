import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, ActionIcon,
  Tooltip, Modal, TextInput, Select, LoadingOverlay, Alert,
} from '@mantine/core'
import { IconPlus, IconPencil, IconTrash, IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { notifications } from '@mantine/notifications'
import { userApi, type TenantUser } from '../../../services/admin-api'

const ROLE_COLORS: Record<string, string> = { admin: 'blue', editor: 'teal', viewer: 'gray' }

export default function UserManagement() {
  const [users, setUsers] = useState<TenantUser[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<string | null>('editor')
  const [editingUser, setEditingUser] = useState<TenantUser | null>(null)
  const [editRole, setEditRole] = useState<string | null>('editor')

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      setUsers(await userApi.list())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load users')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const handleInvite = async () => {
    if (!inviteRole || !inviteEmail) return
    try {
      const created = await userApi.invite({ email: inviteEmail, role: inviteRole })
      setUsers((prev) => [...prev, created])
      notifications.show({ title: 'Invitation sent', message: `Invite sent to ${inviteEmail}`, color: 'teal' })
      setInviteOpen(false)
      setInviteEmail('')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to invite user')
    }
  }

  const handleRoleSave = async () => {
    if (!editingUser || !editRole) return
    try {
      const updated = await userApi.update(editingUser.id, editRole)
      setUsers((prev) => prev.map((user) => user.id === updated.id ? updated : user))
      setEditingUser(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update user')
    }
  }

  const handleRemove = async (user: TenantUser) => {
    try {
      if (user.status === 'invited') {
        await userApi.revoke(user.id)
      } else {
        await userApi.remove(user.id)
      }
      setUsers((prev) => prev.filter((entry) => entry.id !== user.id))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to remove user')
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />

      <Group justify="space-between">
        <Title order={3}>Users</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setInviteOpen(true)}>
          Invite User
        </Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

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
            {users.map(u => (
              <Table.Tr key={u.id}>
                <Table.Td>
                  <Group gap="sm" wrap="nowrap">
                    <div style={{
                      width: 32, height: 32, borderRadius: '50%',
                      background: '#378ADD15', color: '#378ADD',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontWeight: 700, fontSize: 11,
                    }}>
                      {u.name.split(' ').map(n => n[0]).join('').slice(0, 2)}
                    </div>
                    <div>
                      <Text size="sm" fw={500}>{u.name}</Text>
                      <Text size="xs" c="dimmed">{u.email}</Text>
                    </div>
                  </Group>
                </Table.Td>
                <Table.Td><Badge color={ROLE_COLORS[u.role] ?? 'gray'} size="sm">{u.role}</Badge></Table.Td>
                <Table.Td>
                  <Badge color={u.status === 'active' ? 'green' : 'orange'} size="sm" variant="light">
                    {u.status === 'invited' ? 'Invite pending' : 'Active'}
                  </Badge>
                </Table.Td>
                <Table.Td><Text size="xs" c="dimmed">{u.lastAccessAt ?? '—'}</Text></Table.Td>
                <Table.Td><Text size="sm">{u.workflowCount}</Text></Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <Tooltip label="Edit role">
                      <ActionIcon variant="subtle" size="sm" onClick={() => { setEditingUser(u); setEditRole(u.role) }}>
                        <IconPencil size={14} />
                      </ActionIcon>
                    </Tooltip>
                    <Tooltip label={u.status === 'invited' ? 'Revoke invite' : 'Remove'}>
                      <ActionIcon variant="subtle" color="red" size="sm" onClick={() => void handleRemove(u)}>
                        <IconTrash size={14} />
                      </ActionIcon>
                    </Tooltip>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>

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
            <Button onClick={() => void handleInvite()} disabled={!inviteEmail}>Send Invite</Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={editingUser !== null} onClose={() => setEditingUser(null)} title="Edit role" centered>
        <Stack gap="md">
          <Text size="sm">{editingUser?.name}</Text>
          <Select label="Role" data={[
            { value: 'admin', label: 'Admin' },
            { value: 'editor', label: 'Editor' },
            { value: 'viewer', label: 'Viewer' },
          ]} value={editRole} onChange={setEditRole} />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setEditingUser(null)}>Cancel</Button>
            <Button onClick={() => void handleRoleSave()} disabled={!editRole}>Save</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
