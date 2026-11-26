import { AppShell, Group } from '@mantine/core'
import {
  IconBuilding, IconUsers, IconKey, IconSettings, IconChartBar,
  IconTopologyRing, IconPlayerPlay,
  IconActivity,
  IconBook2, IconPlug, IconClockPlay, IconInbox, IconMessageCog,
  IconArrowLeft,
} from '@tabler/icons-react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useTenantStore } from '../../stores/useTenantStore'
import { ImpersonationBanner } from './ImpersonationBanner'
import { SidebarBrand, SidebarGroup, SidebarItem, SidebarSeparator, SidebarUser } from '../layout/Sidebar'
import { useAuthStore } from '../../stores/auth-store'
import { useLogout } from '../layout/useLogout'

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
  // Uma tela só de chaves: a do ApiKeyService. A antiga (/keys) guardava chaves em memória (RCHFLW-85).
  { key: 'apiKeys',    icon: IconKey,       path: '/admin/workspace/api-keys' },
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
  const user = useAuthStore((s) => s.user)
  // O mesmo Sair do app: navega, e o /login encerra a sessão (RCHFLW-142).
  const handleLogout = useLogout()

  const isSuperadminView = currentRole === 'superadmin' && !impersonating
  const navItems = isSuperadminView ? SUPERADMIN_NAV : TENANT_NAV

  const roleLabel = isSuperadminView ? t('admin.layout.superadmin') : t('admin.layout.workspace')
  // O texto traduzido traz a seta ("← Back to app"); no item a seta já é o ícone.
  const backLabel = t('admin.layout.backToApp').replace(/^\s*←\s*/, '')
  const item = (it: AdminNavItem) => (
    <SidebarItem
      key={it.path}
      icon={it.icon}
      label={t(`admin.layout.${it.key}`)}
      active={isActive(it, location.pathname)}
      onClick={() => navigate(it.path)}
    />
  )

  return (
    // Mesma sidebar do app (peças em layout/Sidebar): painel marinho em altura
    // inteira, logo oficial no topo — a Administração não deve parecer outro produto.
    <AppShell
      layout="alt"
      navbar={{ width: 232, breakpoint: 'sm' }}
      header={{ height: impersonating ? 92 : 52 }}
      padding="md"
    >
      <AppShell.Header>
        <Group h={52} px="md" justify="space-between">
          <span style={{ fontSize: 15, fontWeight: 600, letterSpacing: '-0.2px' }}>{t('admin.layout.admin')}</span>
          <span
            onClick={() => navigate('/')}
            style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--text3)', cursor: 'pointer' }}
          >{t('admin.layout.backToApp')}</span>
        </Group>
        <ImpersonationBanner />
      </AppShell.Header>

      <AppShell.Navbar p={10} className="af-sidebar">
        <SidebarBrand
          alt={t('common.appName')}
          tag={
            <span className="af-sidebar-role" data-variant={isSuperadminView ? 'superadmin' : 'workspace'}>
              {roleLabel}
            </span>
          }
        />
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', overflowX: 'hidden', margin: '0 -10px', padding: '0 10px' }}>
          {/* Sem título no primeiro grupo: a etiqueta ao lado do logo já diz qual é. */}
          <div style={{ height: 8 }} />
          {navItems.map(item)}

          {/* O superadmin também administra o próprio workspace: o ADMIN do backend vira superadmin na UI
              (mapRolesToUserRole) e o /admin abre na visão geral dele, mas o menu só tinha as páginas da
              plataforma — Usuários, Chaves de API e as integrações só abriam digitando a URL (RCHFLW-145). */}
          {isSuperadminView && (
            <>
              <SidebarGroup label={t('admin.layout.ownWorkspace')} />
              {TENANT_NAV.map(item)}
            </>
          )}

          <SidebarGroup label={t('admin.layout.integrations')} />
          {TENANT_TOOLS_NAV.map(item)}

          {isSuperadminView && (
            <>
              <SidebarSeparator />
              <SidebarItem
                icon={IconTopologyRing}
                label={t('admin.layout.workflows')}
                onClick={() => navigate('/workflows')}
                dimmed
              />
              <SidebarItem
                icon={IconPlayerPlay}
                label={t('admin.layout.executions')}
                onClick={() => navigate('/executions')}
                dimmed
              />
            </>
          )}
        </div>

        <div style={{ paddingTop: 8, marginTop: 8, borderTop: '1px solid var(--sb-divider)' }}>
          <SidebarItem icon={IconArrowLeft} label={backLabel} onClick={() => navigate('/')} dimmed />
        </div>
        {user && (
          <SidebarUser
            name={user.name || user.username}
            detail={user.email || undefined}
            logoutLabel={t('header.logout')}
            onLogout={handleLogout}
          />
        )}
      </AppShell.Navbar>

      <AppShell.Main className="af-main">
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}
