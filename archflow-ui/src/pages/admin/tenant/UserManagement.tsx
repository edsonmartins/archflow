import {
  Text, Stack, Group, Button, Modal, TextInput, Select, LoadingOverlay, Alert, SimpleGrid, Paper,
  ThemeIcon, Avatar, CopyButton, Code,
} from '@mantine/core'
import {
  IconPlus, IconAlertCircle, IconUsers, IconShieldLock, IconCheck, IconUserPlus, IconCopy, IconLink,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  userApi, invitationLink, type TenantUser, type WorkspaceRole, type InviteUserResponse,
} from '../../../services/admin-api'
import { confirmAction } from '../../../lib/confirm'
import { mensagemDeUsuarioDoWorkspace } from '../../../lib/workspaceUserError'
import { formatInstant } from '../../../lib/format'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import {
  VxTable, colunaAcoes, colunaApagada, colunaDupla, colunaStatus, type VxColumn,
} from '../../../components/VTable'

/** Cor por papel do backend; um papel novo sem cor fica cinza, mas aparece. */
const ROLE_COLORS: Record<string, string> = { ADMIN: 'blue', DESIGNER: 'teal', EXECUTOR: 'violet', VIEWER: 'gray' }
const STATUS_COLORS: Record<string, string> = { active: 'green', invited: 'orange', disabled: 'gray' }

/** `workflow:read` → chave i18n `workflow_read` (`:` é o separador de namespace do i18next). */
const permissionKey = (permission: string) => permission === '*:*' ? 'all' : permission.replace(/[:*]/g, '_')

/**
 * Admin › Usuários (RCHFLW-85): os usuários do login de verdade. Convidar cria um acesso pendente e
 * mostra, uma vez, o link de aceite (o produto não envia e-mail); trocar o papel ou remover muda o
 * acesso real. Os papéis e o que cada um pode são os do backend (`GET /admin/workspace/roles`) — a tela
 * não tem uma matriz própria que pudesse divergir.
 */
export default function UserManagement() {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
  const [users, setUsers] = useState<TenantUser[]>([])
  const [roles, setRoles] = useState<WorkspaceRole[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [inviteOpen, setInviteOpen] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<string | null>(null)
  const [inviteError, setInviteError] = useState<string | null>(null)
  const [invitation, setInvitation] = useState<InviteUserResponse | null>(null)
  const [editingUser, setEditingUser] = useState<TenantUser | null>(null)
  const [editRole, setEditRole] = useState<string | null>(null)
  const [editError, setEditError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      const [list, available] = await Promise.all([userApi.list(), userApi.roles()])
      setUsers(list)
      setRoles(available)
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

  const roleLabel = (name: string | null | undefined) =>
    name ? t(`admin.tenant.users.roles.${name}`, { defaultValue: name }) : '—'
  const roleOptions = roles.map((r) => ({ value: r.name, label: roleLabel(r.name) }))

  const openInvite = () => {
    setInviteEmail('')
    setInviteRole(null)
    setInviteError(null)
    setInvitation(null)
    setInviteOpen(true)
  }

  const handleInvite = async () => {
    if (!inviteRole || !inviteEmail) return
    setInviteError(null)
    try {
      const created = await userApi.invite({ email: inviteEmail, role: inviteRole })
      setUsers((prev) => [...prev, created.user])
      setInvitation(created)
    } catch (e) {
      // No próprio diálogo: o administrador corrige o e-mail ou o papel sem perder o que digitou.
      setInviteError(mensagemDeUsuarioDoWorkspace(e, t, t('admin.tenant.users.inviteFailed')))
    }
  }

  const handleRoleSave = async () => {
    if (!editingUser || !editRole) return
    setEditError(null)
    try {
      const updated = await userApi.update(editingUser.id, editRole)
      setUsers((prev) => prev.map((user) => user.id === updated.id ? updated : user))
      setEditingUser(null)
    } catch (e) {
      setEditError(mensagemDeUsuarioDoWorkspace(e, t, t('admin.tenant.users.updateFailed')))
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
          setError(mensagemDeUsuarioDoWorkspace(e, t, t('admin.tenant.users.removeFailed')))
        }
      },
    })
  }

  const colunas = useMemo((): VxColumn[] => [
    {
      ...colunaDupla(
        { field: 'name', title: t('admin.tenant.users.columns.user'), width: 'auto', minWidth: 260 },
        (r) => String(r.name || r.email),
        (r) => (r.name && r.name !== r.email ? String(r.email) : undefined),
      ),
      espelho: (r) => <><span>{String(r.name)}</span> <span>{String(r.email)}</span></>,
    },
    colunaStatus(
      { field: 'role', title: t('admin.tenant.users.columns.role'), width: 150,
        fieldFormat: (r) => roleLabel(r.role as string | null) },
      (r) => ROLE_COLORS[r.role as string] ?? 'gray',
    ),
    colunaStatus(
      { field: 'status', title: t('admin.tenant.users.columns.status'), width: 170,
        fieldFormat: (r) => t(`admin.tenant.users.status.${r.status}`, { defaultValue: String(r.status) }) },
      (r) => STATUS_COLORS[r.status as string] ?? 'gray',
    ),
    colunaApagada({ field: 'lastAccessAt', title: t('admin.tenant.users.columns.lastAccess'), width: 190,
      fieldFormat: (r) => r.status === 'invited'
        ? t('admin.tenant.users.inviteExpires', { when: formatInstant(r.invitationExpiresAt as string | null, locale) })
        : (r.lastAccessAt ? formatInstant(r.lastAccessAt as string, locale) : '—') }),
    colunaAcoes([
      { nome: 'edit', icone: 'pencil', rotulo: t('admin.tenant.users.editTooltip') },
      { nome: 'revoke', icone: 'x', rotulo: t('admin.tenant.users.revokeInvite'), cor: 'red',
        mostrar: (r) => r.status === 'invited' },
      { nome: 'remove', icone: 'trash', rotulo: t('admin.tenant.users.remove'), cor: 'red',
        mostrar: (r) => r.status !== 'invited' },
    ]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [t, locale])

  const invitedCount = users.filter((u) => u.status === 'invited').length
  const link = invitation ? invitationLink(invitation.invitationToken) : ''

  return (
    <Stack gap="lg" p="md" pos="relative">
      <LoadingOverlay visible={loading && users.length > 0} />

      <PageHeader
        icon={IconUsers}
        accent="blue"
        title={t('admin.tenant.users.title')}
        subtitle={t('admin.tenant.users.subtitle')}
        actions={
          <Button leftSection={<IconPlus size={16} />} onClick={openInvite} disabled={roles.length === 0}
                  data-testid="user-invite">
            {t('admin.tenant.users.invite')}
          </Button>
        }
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />} data-testid="users-error">
          {error}
        </Alert>
      )}

      <SectionCard
        flush
        icon={IconUsers}
        accent="blue"
        title={t('admin.tenant.users.listTitle')}
        // Cada número com o próprio plural: "{{count}} pessoas · {{invited}} convites pendentes" pluralizava só o
        // primeiro e mostrava "1 convites pendentes" (RCHFLW-195 item 17).
        description={t('admin.tenant.users.listHint', {
          people: t('admin.tenant.users.peopleCount', { count: users.length }),
          invited: t('admin.tenant.users.invitedCount', { count: invitedCount }),
        })}
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
              setEditError(null)
            } else {
              handleRemove(user)
            }
          }}
          emptyMessage={t('admin.tenant.users.emptyTitle')}
          emptyDescription={t('admin.tenant.users.emptyHint')}
          emptyIcon={<IconUsers />}
          emptyAccent="blue"
          emptyAction={
            <Button variant="light" size="xs" leftSection={<IconUserPlus size={14} />} onClick={openInvite}
                    disabled={roles.length === 0}>
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
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="md">
          {roles.map((role) => (
            <Paper key={role.name} withBorder p="md" radius="md" data-testid={`role-card-${role.name}`}>
              <Group gap="sm" mb="sm" wrap="nowrap">
                <Avatar size={30} radius="md" color={ROLE_COLORS[role.name] ?? 'gray'} variant="light">
                  {roleLabel(role.name).slice(0, 1)}
                </Avatar>
                <Stack gap={0} style={{ minWidth: 0 }}>
                  <Text size="sm" fw={600}>{roleLabel(role.name)}</Text>
                  <Text size="xs" c="dimmed">
                    {t('admin.tenant.users.membersWithRole', { count: users.filter((u) => u.role === role.name).length })}
                  </Text>
                </Stack>
              </Group>
              <Stack gap={6}>
                {role.permissions.map((permission) => (
                  <Group key={permission} gap={8} wrap="nowrap">
                    <ThemeIcon size={18} radius="xl" variant="light" color="teal"
                               aria-label={t('admin.tenant.users.allowed')}>
                      <IconCheck size={12} stroke={2.4} />
                    </ThemeIcon>
                    <Text size="xs">
                      {t(`admin.tenant.users.permissionNames.${permissionKey(permission)}`, { defaultValue: permission })}
                    </Text>
                  </Group>
                ))}
              </Stack>
            </Paper>
          ))}
        </SimpleGrid>
      </SectionCard>

      <Modal opened={inviteOpen} onClose={() => setInviteOpen(false)} title={t('admin.tenant.users.inviteTitle')}
             centered size="lg">
        {invitation ? (
          <Stack gap="md" data-testid="invite-link">
            <Alert color="teal" variant="light" icon={<IconLink size={16} />}
                   title={t('admin.tenant.users.inviteCreated', { email: invitation.user.email })}>
              {t('admin.tenant.users.inviteLinkHint', {
                when: formatInstant(invitation.expiresAt, locale) })}
            </Alert>
            <Code block style={{ wordBreak: 'break-all' }} data-testid="invite-link-url">{link}</Code>
            <Group justify="flex-end">
              <CopyButton value={link}>
                {({ copied, copy }) => (
                  <Button variant="light" leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                          onClick={copy}>
                    {copied ? t('admin.tenant.users.linkCopied') : t('admin.tenant.users.copyLink')}
                  </Button>
                )}
              </CopyButton>
              <Button onClick={() => setInviteOpen(false)}>{t('admin.tenant.users.done')}</Button>
            </Group>
          </Stack>
        ) : (
          <Stack gap="md" data-testid="invite-form">
            {inviteError && (
              <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />} data-testid="invite-error">
                {inviteError}
              </Alert>
            )}
            <TextInput label={t('admin.tenant.users.form.email')} required data-testid="invite-email"
                       placeholder={t('admin.tenant.users.form.emailPlaceholder')} value={inviteEmail}
                       onChange={(e) => setInviteEmail(e.currentTarget.value)} />
            <Select label={t('admin.tenant.users.form.role')} required data={roleOptions} value={inviteRole}
                    onChange={setInviteRole} data-testid="invite-role" />
            <Text size="xs" c="dimmed">{t('admin.tenant.users.inviteNoEmail')}</Text>
            <Group justify="flex-end">
              <Button variant="light" onClick={() => setInviteOpen(false)}>{t('common.cancel')}</Button>
              <Button onClick={() => void handleInvite()} disabled={!inviteEmail || !inviteRole}
                      data-testid="invite-send">
                {t('admin.tenant.users.sendInvite')}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>

      <Modal opened={editingUser !== null} onClose={() => setEditingUser(null)} title={t('admin.tenant.users.editRole')} centered>
        <Stack gap="md">
          {editError && (
            <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />} data-testid="edit-error">
              {editError}
            </Alert>
          )}
          <Text size="sm">{editingUser?.name}</Text>
          <Select label={t('admin.tenant.users.form.role')} data={roleOptions} value={editRole} onChange={setEditRole} />
          <Text size="xs" c="dimmed" data-testid="role-change-hint">{t('admin.tenant.users.roleChangeHint')}</Text>
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setEditingUser(null)}>{t('common.cancel')}</Button>
            <Button onClick={() => void handleRoleSave()} disabled={!editRole}>{t('admin.tenant.users.save')}</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
