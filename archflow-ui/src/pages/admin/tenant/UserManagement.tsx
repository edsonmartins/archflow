import {
  Text, Stack, Group, Button, Modal, TextInput, Select, LoadingOverlay, Alert, SimpleGrid, Paper,
  ThemeIcon, Avatar,
} from '@mantine/core'
import {
  IconPlus, IconAlertCircle, IconUsers, IconShieldLock, IconCheck, IconMinus, IconUserPlus,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { userApi, type TenantUser } from '../../../services/admin-api'
import { confirmAction } from '../../../lib/confirm'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import {
  VxTable, colunaAcoes, colunaApagada, colunaDupla, colunaStatus, type VxColumn,
} from '../../../components/VTable'

const ROLE_COLORS: Record<string, string> = { admin: 'blue', editor: 'teal', viewer: 'gray' }

const ROLES = ['admin', 'editor', 'viewer'] as const

/** O que cada papel pode fazer — a mesma matriz de antes, agora lida por papel. */
const PERMISSIONS: [string, boolean, boolean, boolean][] = [
  ['viewWorkflows',     true, true,  true ],
  ['editWorkflows',     true, true,  false],
  ['executeWorkflows',  true, true,  false],
  ['manageUsers',       true, false, false],
  ['manageApiKeys',     true, false, false],
]

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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  const handleRemove = (user: TenantUser) => {
    const invited = user.status === 'invited'
    const name = user.name || user.email
    confirmAction({
      title: t(invited ? 'confirmations.revokeInviteTitle' : 'confirmations.removeUserTitle'),
      message: t(invited ? 'confirmations.revokeInviteMessage' : 'confirmations.removeUserMessage', { name }),
      confirmLabel: t(invited ? 'confirmations.revoke' : 'confirmations.remove'),
      onConfirm: async () => {
        try {
          if (invited) {
            await userApi.revoke(user.id)
          } else {
            await userApi.remove(user.id)
          }
          setUsers((prev) => prev.filter((entry) => entry.id !== user.id))
        } catch (e) {
          setError(e instanceof Error ? e.message : t('admin.tenant.users.removeFailed'))
        }
      },
    })
  }

  const colunas = useMemo((): VxColumn[] => [
    {
      ...colunaDupla(
        { field: 'name', title: t('admin.tenant.users.columns.user'), width: 'auto', minWidth: 260 },
        (r) => String(r.name || r.email),
        (r) => (r.name ? String(r.email) : undefined),
      ),
      espelho: (r) => <><span>{String(r.name)}</span> <span>{String(r.email)}</span></>,
    },
    colunaStatus(
      { field: 'role', title: t('admin.tenant.users.columns.role'), width: 130,
        fieldFormat: (r) => t(`admin.tenant.users.roles.${r.role}`, { defaultValue: String(r.role) }) },
      (r) => ROLE_COLORS[r.role as string] ?? 'gray',
    ),
    colunaStatus(
      { field: 'status', title: t('admin.tenant.users.columns.status'), width: 170,
        fieldFormat: (r) => (r.status === 'invited' ? t('admin.tenant.users.status.invited') : t('admin.tenant.users.status.active')) },
      (r) => (r.status === 'active' ? 'green' : 'orange'),
    ),
    colunaApagada({ field: 'lastAccessAt', title: t('admin.tenant.users.columns.lastAccess'), width: 170,
      fieldFormat: (r) => (r.lastAccessAt as string | null) ?? '—' }),
    { field: 'workflowCount', title: t('admin.tenant.users.columns.workflows'), width: 120 },
    colunaAcoes([
      { nome: 'edit', icone: 'pencil', rotulo: t('admin.tenant.users.editTooltip') },
      { nome: 'revoke', icone: 'x', rotulo: t('admin.tenant.users.revokeInvite'), cor: 'red',
        mostrar: (r) => r.status === 'invited' },
      { nome: 'remove', icone: 'trash', rotulo: t('admin.tenant.users.remove'), cor: 'red',
        mostrar: (r) => r.status !== 'invited' },
    ]),
  ], [t])

  const invitedCount = users.filter((u) => u.status === 'invited').length

  return (
    <Stack gap="lg" p="md" pos="relative">
      <LoadingOverlay visible={loading && users.length > 0} />

      <PageHeader
        icon={IconUsers}
        accent="blue"
        title={t('admin.tenant.users.title')}
        subtitle={t('admin.tenant.users.subtitle')}
        actions={
          <Button leftSection={<IconPlus size={16} />} onClick={() => setInviteOpen(true)}>
            {t('admin.tenant.users.invite')}
          </Button>
        }
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SectionCard
        flush
        icon={IconUsers}
        accent="blue"
        title={t('admin.tenant.users.listTitle')}
        description={t('admin.tenant.users.listHint', { count: users.length, invited: invitedCount })}
      >
        <VxTable
          legenda={t('admin.tenant.users.listTitle')}
          records={users as unknown as Record<string, unknown>[]}
          columns={colunas}
          rowHeight={58}
          loading={loading && users.length === 0}
          onIconClick={(nome, r) => {
            const user = r as unknown as TenantUser
            if (nome === 'edit') {
              setEditingUser(user)
              setEditRole(user.role)
            } else {
              handleRemove(user)
            }
          }}
          emptyMessage={t('admin.tenant.users.emptyTitle')}
          emptyDescription={t('admin.tenant.users.emptyHint')}
          emptyIcon={<IconUsers />}
          emptyAccent="blue"
          emptyAction={
            <Button variant="light" size="xs" leftSection={<IconUserPlus size={14} />} onClick={() => setInviteOpen(true)}>
              {t('admin.tenant.users.invite')}
            </Button>
          }
          testid="user-list"
          rowTestId={(r) => `user-row-${r.id}`}
        />
      </SectionCard>

      <SectionCard
        icon={IconShieldLock}
        accent="slate"
        title={t('admin.tenant.users.permissionsTitle')}
        description={t('admin.tenant.users.permissionsHint')}
      >
        <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
          {ROLES.map((role, roleIndex) => (
            <Paper key={role} withBorder p="md" radius="md" data-testid={`role-card-${role}`}>
              <Group gap="sm" mb="sm" wrap="nowrap">
                <Avatar size={30} radius="md" color={ROLE_COLORS[role]} variant="light">
                  {t(`admin.tenant.users.roles.${role}`).slice(0, 1)}
                </Avatar>
                <Stack gap={0} style={{ minWidth: 0 }}>
                  <Text size="sm" fw={600}>{t(`admin.tenant.users.permissions.${role}`)}</Text>
                  <Text size="xs" c="dimmed">
                    {t('admin.tenant.users.membersWithRole', { count: users.filter((u) => u.role === role).length })}
                  </Text>
                </Stack>
              </Group>
              <Stack gap={6}>
                {PERMISSIONS.map(([permKey, ...allowed]) => {
                  const ok = allowed[roleIndex]
                  return (
                    <Group key={permKey} gap={8} wrap="nowrap">
                      <ThemeIcon size={18} radius="xl" variant="light" color={ok ? 'teal' : 'gray'}
                                 aria-label={ok ? t('admin.tenant.users.allowed') : t('admin.tenant.users.notAllowed')}>
                        {ok ? <IconCheck size={12} stroke={2.4} /> : <IconMinus size={12} stroke={2.4} />}
                      </ThemeIcon>
                      <Text size="xs" c={ok ? undefined : 'dimmed'}>
                        {t(`admin.tenant.users.permissions.${permKey}`)}
                      </Text>
                    </Group>
                  )
                })}
              </Stack>
            </Paper>
          ))}
        </SimpleGrid>
      </SectionCard>

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
