import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, ActionIcon,
  Tooltip, Modal, TextInput, Select, LoadingOverlay, Alert,
} from '@mantine/core'
import { IconPlus, IconPencil, IconTrash, IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { userApi, type TenantUser } from '../../../services/admin-api'

const ROLE_COLORS: Record<string, string> = { admin: 'blue', editor: 'teal', viewer: 'gray' }

export default function UserManagement() {
  const { t } = useTranslation()
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
      setError(e instanceof Error ? e.message : t('admin.tenant.users.loadFailed'))
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
      notifications.show({ title: t('admin.tenant.users.inviteSent'), message: t('admin.tenant.users.inviteSentMsg', { email: inviteEmail }), color: 'teal' })
      setInviteOpen(false)
      setInviteEmail('')
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.tenant.users.inviteFailed'))
    }
  }

  const handleRoleSave = async () => {
    if (!editingUser || !editRole) return
    try {
      const updated = await userApi.update(editingUser.id, editRole)
      setUsers((prev) => prev.map((user) => user.id === updated.id ? updated : user))
      setEditingUser(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.tenant.users.updateFailed'))
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
      setError(e instanceof Error ? e.message : t('admin.tenant.users.removeFailed'))
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />

      <Group justify="space-between">
        <Title order={3}>{t('admin.tenant.users.title')}</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setInviteOpen(true)}>
          {t('admin.tenant.users.invite')}
        </Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <Paper withBorder p="md" radius="lg">
        <Text fw={600} size="sm" mb="sm">{t('admin.tenant.users.permissionsTitle')}</Text>
        <Table withColumnBorders>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.tenant.users.permissions.header')}</Table.Th>
              <Table.Th ta="center">{t('admin.tenant.users.permissions.admin')}</Table.Th>
              <Table.Th ta="center">{t('admin.tenant.users.permissions.editor')}</Table.Th>
              <Table.Th ta="center">{t('admin.tenant.users.permissions.viewer')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {[
              ['viewWorkflows',     true, true,  true ],
              ['editWorkflows',     true, true,  false],
              ['executeWorkflows',  true, true,  false],
              ['manageUsers',       true, false, false],
              ['manageApiKeys',     true, false, false],
            ].map(([permKey, admin, editor, viewer]) => (
              <Table.Tr key={permKey as string}>
                <Table.Td><Text size="sm">{t(`admin.tenant.users.permissions.${permKey as string}`)}</Text></Table.Td>
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
              <Table.Th>{t('admin.tenant.users.columns.user')}</Table.Th>
              <Table.Th>{t('admin.tenant.users.columns.role')}</Table.Th>
              <Table.Th>{t('admin.tenant.users.columns.status')}</Table.Th>
              <Table.Th>{t('admin.tenant.users.columns.lastAccess')}</Table.Th>
              <Table.Th>{t('admin.tenant.users.columns.workflows')}</Table.Th>
              <Table.Th w={80}>{t('admin.tenant.users.columns.actions')}</Table.Th>
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
                <Table.Td><Badge color={ROLE_COLORS[u.role] ?? 'gray'} size="sm">{t(`admin.tenant.users.roles.${u.role}`, { defaultValue: u.role })}</Badge></Table.Td>
                <Table.Td>
                  <Badge color={u.status === 'active' ? 'green' : 'orange'} size="sm" variant="light">
                    {u.status === 'invited' ? t('admin.tenant.users.status.invited') : t('admin.tenant.users.status.active')}
                  </Badge>
                </Table.Td>
                <Table.Td><Text size="xs" c="dimmed">{u.lastAccessAt ?? '—'}</Text></Table.Td>
                <Table.Td><Text size="sm">{u.workflowCount}</Text></Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <Tooltip label={t('admin.tenant.users.editTooltip')}>
                      <ActionIcon variant="subtle" size="sm" onClick={() => { setEditingUser(u); setEditRole(u.role) }}>
                        <IconPencil size={14} />
                      </ActionIcon>
                    </Tooltip>
                    <Tooltip label={u.status === 'invited' ? t('admin.tenant.users.revokeInvite') : t('admin.tenant.users.remove')}>
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

      <Modal opened={inviteOpen} onClose={() => setInviteOpen(false)} title={t('admin.tenant.users.inviteTitle')} centered>
        <Stack gap="md">
          <TextInput label={t('admin.tenant.users.form.email')} required placeholder={t('admin.tenant.users.form.emailPlaceholder')} value={inviteEmail}
            onChange={e => setInviteEmail(e.currentTarget.value)} />
          <Select label={t('admin.tenant.users.form.role')} required data={[
            { value: 'admin', label: t('admin.tenant.users.roles.admin') },
            { value: 'editor', label: t('admin.tenant.users.roles.editor') },
            { value: 'viewer', label: t('admin.tenant.users.roles.viewer') },
          ]} value={inviteRole} onChange={setInviteRole} />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setInviteOpen(false)}>{t('common.cancel')}</Button>
            <Button onClick={() => void handleInvite()} disabled={!inviteEmail}>{t('admin.tenant.users.sendInvite')}</Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={editingUser !== null} onClose={() => setEditingUser(null)} title={t('admin.tenant.users.editRole')} centered>
        <Stack gap="md">
          <Text size="sm">{editingUser?.name}</Text>
          <Select label={t('admin.tenant.users.form.role')} data={[
            { value: 'admin', label: t('admin.tenant.users.roles.admin') },
            { value: 'editor', label: t('admin.tenant.users.roles.editor') },
            { value: 'viewer', label: t('admin.tenant.users.roles.viewer') },
          ]} value={editRole} onChange={setEditRole} />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setEditingUser(null)}>{t('common.cancel')}</Button>
            <Button onClick={() => void handleRoleSave()} disabled={!editRole}>{t('admin.tenant.users.save')}</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
