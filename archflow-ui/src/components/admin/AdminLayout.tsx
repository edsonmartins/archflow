import { AppShell, NavLink, Group, Text, Badge } from '@mantine/core'
import {
  IconBuilding, IconUsers, IconKey, IconSettings, IconChartBar,
  IconTopologyRing, IconPlayerPlay, IconShieldCheck, IconArrowLeft,
  IconActivity,
} from '@tabler/icons-react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useTenantStore } from '../../stores/useTenantStore'
import { ImpersonationBanner } from './ImpersonationBanner'

const SUPERADMIN_NAV = [
  { label: 'Tenants',         icon: IconBuilding,  path: '/admin/tenants' },
  { label: 'Observability',   icon: IconActivity,  path: '/admin/observability' },
  { label: 'Global Config',   icon: IconSettings,  path: '/admin/global' },
  { label: 'Usage & Billing', icon: IconChartBar,  path: '/admin/billing' },
]

const TENANT_NAV = [
  { label: 'Overview',    icon: IconChartBar,      path: '/admin/workspace' },
  { label: 'Users',       icon: IconUsers,         path: '/admin/workspace/users' },
  { label: 'API Keys',    icon: IconKey,           path: '/admin/workspace/keys' },
]

export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()
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
            <span style={{ fontSize: 15, fontWeight: 600, letterSpacing: '-0.2px' }}>Admin</span>
            <span style={{
              fontSize: 10, fontWeight: 600, letterSpacing: '0.06em',
              textTransform: 'uppercase', padding: '2px 8px', borderRadius: 4,
              background: isSuperadminView ? 'var(--purple-l)' : 'var(--green-l)',
              color: isSuperadminView ? 'var(--purple)' : 'var(--green)',
            }}>
              {isSuperadminView ? 'SUPERADMIN' : 'WORKSPACE'}
            </span>
          </Group>
          <span
            onClick={() => navigate('/')}
            style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--text3)', cursor: 'pointer' }}
          >← Back to app</span>
        </Group>
        <ImpersonationBanner />
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {navItems.map(item => (
          <NavLink
            key={item.path}
            label={item.label}
            leftSection={<item.icon size={18} />}
            active={location.pathname === item.path || location.pathname.startsWith(item.path + '/')}
            onClick={() => navigate(item.path)}
            variant="light"
          />
        ))}

        {isSuperadminView && (
          <>
            <div style={{ borderTop: '1px solid var(--color-border-tertiary)', margin: '8px 0' }} />
            <NavLink
              label="Workflows"
              leftSection={<IconTopologyRing size={18} />}
              onClick={() => navigate('/')}
              variant="subtle"
              c="dimmed"
            />
            <NavLink
              label="Executions"
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
