import { AppShell, NavLink, Group } from '@mantine/core'
import {
  IconBuilding, IconUsers, IconKey, IconSettings, IconChartBar,
  IconTopologyRing, IconPlayerPlay,
  IconActivity,
  IconBook2, IconPlug, IconClockPlay, IconInbox, IconMessageCog,
  IconLockAccess,
} from '@tabler/icons-react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useTenantStore } from '../../stores/useTenantStore'
import { ImpersonationBanner } from './ImpersonationBanner'

interface AdminNavItem {
  key: string
  icon: typeof IconBuilding
  path: string
  /** Match only the exact path (for parents whose children are separate items). */
  exact?: boolean
}

const SUPERADMIN_NAV: AdminNavItem[] = [
  { key: 'tenants',       icon: IconBuilding, path: '/admin/tenants' },
  { key: 'observability', icon: IconActivity, path: '/admin/observability' },
  { key: 'globalConfig',  icon: IconSettings, path: '/admin/global' },
  { key: 'usageBilling',  icon: IconChartBar, path: '/admin/billing' },
]

const TENANT_NAV: AdminNavItem[] = [
  { key: 'overview',   icon: IconChartBar,  path: '/admin/workspace', exact: true },
  { key: 'users',      icon: IconUsers,     path: '/admin/workspace/users' },
  { key: 'apiKeys',    icon: IconKey,       path: '/admin/workspace/keys' },
  { key: 'scopedKeys', icon: IconLockAccess, path: '/admin/workspace/api-keys' },
]

// Tenant-level integration tools. These routes existed but had no nav
// entry anywhere, making the features undiscoverable without a deep link.
const TENANT_TOOLS_NAV: AdminNavItem[] = [
  { key: 'skills',        icon: IconBook2,      path: '/admin/skills' },
  { key: 'mcp',           icon: IconPlug,       path: '/admin/mcp' },
  { key: 'triggers',      icon: IconClockPlay,  path: '/admin/triggers' },
  { key: 'linktorInbox',  icon: IconInbox,      path: '/admin/linktor/inbox' },
  { key: 'linktorConfig', icon: IconMessageCog, path: '/admin/linktor', exact: true },
  // Brain Sentry removido do menu — decisão 0.2 do plano de homologação: o
  // backend não consome a config (módulo fora do classpath). Religar junto
  // com a rota em App.tsx quando o módulo for integrado.
]

function isActive(item: AdminNavItem, pathname: string): boolean {
  return pathname === item.path
    || (!item.exact && pathname.startsWith(item.path + '/'))
}

export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { t } = useTranslation()
  const { currentRole, impersonating } = useTenantStore()

  const isSuperadminView = currentRole === 'superadmin' && !impersonating
  const navItems = isSuperadminView ? SUPERADMIN_NAV : TENANT_NAV

  return (
    <AppShell
      navbar={{ width: 240, breakpoint: 'sm' }}
      header={{ height: impersonating ? 92 : 52 }}
      padding="md"
    >
      <AppShell.Header>
        <Group h={52} px="md" justify="space-between">
          <Group gap={8}>
            <svg viewBox="0 0 22 22" fill="none" width="22" height="22"><rect width="22" height="22" rx="5" fill="#2563EB" opacity=".12"/><circle cx="5.5" cy="11" r="2.2" fill="#2563EB"/><circle cx="16.5" cy="6" r="2.2" fill="#2563EB"/><circle cx="16.5" cy="16" r="2.2" fill="#2563EB"/><path d="M7.5 10.3L14.4 7" stroke="#2563EB" strokeWidth="1.2" strokeLinecap="round"/><path d="M7.5 11.7L14.4 15" stroke="#2563EB" strokeWidth="1.2" strokeLinecap="round"/></svg>
            <span style={{ fontSize: 15, fontWeight: 600, letterSpacing: '-0.2px' }}>{t('admin.layout.admin')}</span>
            <span style={{
              fontSize: 10, fontWeight: 600, letterSpacing: '0.06em',
              textTransform: 'uppercase', padding: '2px 8px', borderRadius: 4,
              background: isSuperadminView ? 'var(--purple-l)' : 'var(--green-l)',
              color: isSuperadminView ? 'var(--purple)' : 'var(--green)',
            }}>
              {isSuperadminView ? t('admin.layout.superadmin') : t('admin.layout.workspace')}
            </span>
          </Group>
          <span
            onClick={() => navigate('/')}
            style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--text3)', cursor: 'pointer' }}
          >{t('admin.layout.backToApp')}</span>
        </Group>
        <ImpersonationBanner />
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {navItems.map(item => (
          <NavLink
            key={item.path}
            label={t(`admin.layout.${item.key}`)}
            leftSection={<item.icon size={18} />}
            active={isActive(item, location.pathname)}
            onClick={() => navigate(item.path)}
            variant="light"
          />
        ))}

        {!isSuperadminView && (
          <>
            <div style={{ borderTop: '1px solid var(--color-border-tertiary)', margin: '8px 0' }} />
            <span style={{
              fontSize: 10, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase',
              color: 'var(--color-text-tertiary)', padding: '0 10px 4px',
            }}>
              {t('admin.layout.integrations')}
            </span>
            {TENANT_TOOLS_NAV.map(item => (
              <NavLink
                key={item.path}
                label={t(`admin.layout.${item.key}`)}
                leftSection={<item.icon size={18} />}
                active={isActive(item, location.pathname)}
                onClick={() => navigate(item.path)}
                variant="light"
              />
            ))}
          </>
        )}

        {isSuperadminView && (
          <>
            <div style={{ borderTop: '1px solid var(--color-border-tertiary)', margin: '8px 0' }} />
            <NavLink
              label={t('admin.layout.workflows')}
              leftSection={<IconTopologyRing size={18} />}
              onClick={() => navigate('/workflows')}
              variant="subtle"
              c="dimmed"
            />
            <NavLink
              label={t('admin.layout.executions')}
              leftSection={<IconPlayerPlay size={18} />}
              onClick={() => navigate('/executions')}
              variant="subtle"
              c="dimmed"
            />
          </>
        )}
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}
