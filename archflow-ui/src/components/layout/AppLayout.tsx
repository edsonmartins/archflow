import { useEffect, useMemo, useState } from 'react';
import { AppShell, Group, Badge, ActionIcon, Tooltip, Kbd } from '@mantine/core';
import { spotlight, type SpotlightActionGroupData } from '@mantine/spotlight';
import { HttpAgent } from '@ag-ui/client';
import { CopilotKitProvider } from '@copilotkit/react-core/v2';
import '@copilotkit/react-core/v2/styles.css';
import CopilotAppOperator from '../copilot/CopilotAppOperator';
import CopilotGenerateBridge from '../copilot/CopilotGenerateBridge';
import AppCopilotSidebar from '../copilot/AppCopilotSidebar';
import {
    IconTopologyRing,
    IconPlayerPlay,
    IconMessageCircle,
    IconTemplate,
    IconMicrophone,
    IconShieldCheck,
    IconSun,
    IconMoon,
    IconLayoutSidebarLeftCollapse,
    IconLayoutSidebarLeftExpand,
    IconPlayerPlayFilled,
    IconSitemap,
    IconPlugConnected,
    IconSparkles,
    IconSchema,
    IconSettings,
    IconSearch,
    IconLayoutDashboard,
    IconBook2,
} from '@tabler/icons-react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../../stores/auth-store';
import { useTenantStore } from '../../stores/useTenantStore';
import { approvalTenantParam } from '../../lib/approvalTenant';
import { useColorScheme } from '../../App';
import { approvalApi } from '../../services/approval-api';
import { authFetch } from '../../services/api';
import LanguageSwitcher from './LanguageSwitcher';
import GlobalSearch from './GlobalSearch';
import { SidebarBrand, SidebarGroup, SidebarItem, SidebarUser } from './Sidebar';

// Labels come from t() at render time so switching languages updates
// the sidebar without remounting. The `key` is the stable i18n lookup
// path; `path` is the route. Items are grouped into labeled sections
// (build / run / extend / labs) so the sidebar scans as a product
// hierarchy instead of a flat 12-item list.
const NAV_GROUPS = [
    {
        key: 'nav.groups.build',
        items: [
            { key: 'nav.dashboard',     icon: IconLayoutDashboard,  path: '/' },
            { key: 'nav.workflows',     icon: IconTopologyRing,     path: '/workflows' },
            { key: 'nav.editor',        icon: IconSchema,           path: '/editor' },
            { key: 'nav.templates',     icon: IconTemplate,         path: '/templates' },
            { key: 'nav.knowledge',     icon: IconBook2,            path: '/knowledge' },
        ],
    },
    {
        key: 'nav.groups.run',
        items: [
            { key: 'nav.executions',    icon: IconPlayerPlay,       path: '/executions' },
            { key: 'nav.conversations', icon: IconMessageCircle,    path: '/conversations' },
            { key: 'nav.approvals',     icon: IconShieldCheck,      path: '/approvals', withBadge: true },
        ],
    },
    // Grupo "extend" (Marketplace) removido do menu — decisão 0.2 do plano de
    // homologação: o marketplace não instala plugins de verdade ainda. Religar
    // junto com as rotas em App.tsx quando o backend integrar a instalação.
    {
        key: 'nav.groups.labs',
        items: [
            { key: 'nav.agentLab',      icon: IconPlayerPlayFilled, path: '/playground/agent' },
            { key: 'nav.dynamicFlow',   icon: IconSitemap,          path: '/playground/orchestration' },
            { key: 'nav.aguiRunner',    icon: IconPlugConnected,    path: '/playground/ag-ui' },
            { key: 'nav.copilot',       icon: IconSparkles,         path: '/playground/copilot' },
            { key: 'nav.voice',         icon: IconMicrophone,       path: '/playground/voice' },
        ],
    },
];

export default function AppLayout() {
    const navigate = useNavigate();
    // App-wide copilot agent (AG-UI). fetch must be bound to window.
    // O @copilotkit (pré-release) embute sua própria declaração de
    // AbstractAgent, nominalmente incompatível com a de @ag-ui/client —
    // em runtime é o mesmo protocolo AG-UI, então o cast é seguro.
    const copilotAgent = useMemo(
        () => {
            // authFetch (services/api) injeta o token ATUAL a cada chamada COM
            // refresh single-flight — sem o refresh o /ag-ui/agent voltava 401
            // após a expiração do access token em sessões longas.
            const copilotFetch: typeof window.fetch = (input, init) =>
                authFetch(typeof input === 'string' ? input : input.toString(), init ?? undefined);
            return new HttpAgent({ url: '/ag-ui/agent', fetch: copilotFetch }) as unknown as
                NonNullable<Parameters<typeof CopilotKitProvider>[0]['agents__unsafe_dev_only']>[string];
        },
        []);
    const location = useLocation();
    const { t } = useTranslation();
    const { user, logout } = useAuthStore();
    const { impersonating, currentRole } = useTenantStore();
    const isAdmin = currentRole === 'superadmin' || currentRole === 'tenant_admin' || impersonating !== null;
    const [colorScheme, setColorScheme] = useColorScheme();
    const dark = colorScheme === 'dark';
    const [pendingApprovals, setPendingApprovals] = useState(0);

    // Poll the approval count every 30s so the badge stays in sync without
    // needing a dedicated SSE subscription. Errors are swallowed on purpose
    // — the badge is opportunistic, not load-bearing.
    useEffect(() => {
        // impersonating é um TenantInfo — a API espera o ID (passar o objeto virava "[object Object]")
        const tenantId = approvalTenantParam(impersonating, currentRole);
        let cancelled = false;
        const refresh = () => {
            approvalApi
                .pendingCount(tenantId)
                .then((res) => {
                    if (!cancelled) setPendingApprovals(res.count);
                })
                .catch(() => { /* ignore — badge is opportunistic */ });
        };
        refresh();
        const timer = setInterval(refresh, 30_000);
        return () => {
            cancelled = true;
            clearInterval(timer);
        };
    }, [impersonating, currentRole]);

    const handleLogout = async () => {
        await logout();
        navigate('/login');
    };

    // ── Global search (⌘K) — as páginas do menu; os dados (fluxos, execuções…) vêm do GlobalSearch ──
    const spotlightActions = useMemo<SpotlightActionGroupData[]>(() => {
        const nav: SpotlightActionGroupData[] = NAV_GROUPS.map((group) => ({
            group: t(group.key),
            actions: group.items.map((item) => ({
                id: `nav-${item.path}`,
                label: t(item.key),
                onClick: () => navigate(item.path),
                leftSection: <item.icon size={18} />,
            })),
        }));
        if (isAdmin) {
            nav.push({
                group: t('nav.admin'),
                actions: [{
                    id: 'nav-admin',
                    label: t('nav.admin'),
                    onClick: () => navigate('/admin'),
                    leftSection: <IconSettings size={18} />,
                }],
            });
        }
        return nav;
    }, [t, navigate, isAdmin]);

    const toggleTheme = () => {
        setColorScheme(dark ? 'light' : 'dark');
    };

    // Plain useState (reading once from localStorage) is more reliable
    // here than Mantine's useLocalStorage: the latter can hydrate
    // asynchronously and its partially-undefined first render was
    // causing the AppShell navbar width prop to ignore the update.
    const [navCollapsed, setNavCollapsedState] = useState<boolean>(() => {
        try { return localStorage.getItem('archflow-nav-collapsed') === 'true'; }
        catch { return false; }
    });
    const setNavCollapsed = (updater: boolean | ((v: boolean) => boolean)) => {
        setNavCollapsedState(prev => {
            const next = typeof updater === 'function' ? updater(prev) : updater;
            try { localStorage.setItem('archflow-nav-collapsed', String(next)); } catch { /* ignore */ }
            return next;
        });
    };
    const navWidth = navCollapsed ? 84 : 232;

    return (
        // key forces AppShell to re-evaluate navbar.width. Some Mantine
        // v7 builds don't re-apply the CSS variable when only width
        // changes; remounting on toggle is the safe escape hatch.
        <AppShell
            key={navCollapsed ? 'collapsed' : 'expanded'}
            // "alt": a sidebar ocupa a altura toda e a marca mora no topo dela,
            // em vez de o header branco atravessar por cima do painel colorido.
            layout="alt"
            navbar={{ width: navWidth, breakpoint: 'sm' }}
            header={{ height: 52 }}
            padding="md"
        >
            <AppShell.Header>
                {/* Em dev, o inspetor do CopilotKit flutua no canto superior direito: o header
                    reserva o espaço dele para nenhum botão ficar embaixo. */}
                <Group h="100%" pl="md" pr={import.meta.env.DEV ? 84 : 'md'} justify="space-between">
                    <Group gap="xs">
                        <Tooltip label={navCollapsed ? t('nav.expandSidebar') : t('nav.collapseSidebar')}>
                            <ActionIcon
                                variant="subtle"
                                onClick={() => setNavCollapsed(v => !v)}
                                aria-label={t('nav.toggleSidebar')}
                                data-testid="nav-toggle"
                            >
                                {navCollapsed
                                    ? <IconLayoutSidebarLeftExpand size={18} />
                                    : <IconLayoutSidebarLeftCollapse size={18} />}
                            </ActionIcon>
                        </Tooltip>
                    </Group>
                    <Group gap="xs">
                        <Tooltip label={<Group gap={6}>{t('nav.searchTooltip')}<Kbd size="xs">⌘K</Kbd></Group>}>
                            <ActionIcon
                                variant="subtle"
                                onClick={spotlight.open}
                                aria-label={t('nav.searchTooltip')}
                                data-testid="global-search"
                            >
                                <IconSearch size={18} />
                            </ActionIcon>
                        </Tooltip>
                        <LanguageSwitcher />
                        <Tooltip label={dark ? t('header.lightMode') : t('header.darkMode')}>
                            {/* aria-label kept as a stable English action so
                                automated tests can target the toggle without
                                tracking dynamic state. */}
                            <ActionIcon
                                variant="subtle"
                                onClick={toggleTheme}
                                aria-label="Toggle theme"
                            >
                                {dark ? <IconSun size={18} /> : <IconMoon size={18} />}
                            </ActionIcon>
                        </Tooltip>
                    </Group>
                </Group>
            </AppShell.Header>

            <AppShell.Navbar p={10} className="af-sidebar" data-collapsed={navCollapsed || undefined}>
                <SidebarBrand
                    collapsed={navCollapsed}
                    alt={t('common.appName')}
                    tag={<span className="af-sidebar-version">v1.0</span>}
                />
                <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', overflowX: 'hidden', margin: '0 -10px', padding: '0 10px' }}>
                    {NAV_GROUPS.map((group, groupIndex) => (
                        <div key={group.key}>
                            <SidebarGroup label={t(group.key)} collapsed={navCollapsed} first={groupIndex === 0} />
                            {group.items.map((item) => {
                                const active =
                                    item.path === '/'
                                        ? location.pathname === '/'
                                        : location.pathname === item.path ||
                                          location.pathname.startsWith(`${item.path}/`);
                                const showBadge = item.withBadge && pendingApprovals > 0;
                                return (
                                    <SidebarItem
                                        key={item.path}
                                        icon={item.icon}
                                        label={t(item.key)}
                                        shortLabel={t(item.key.replace('nav.', 'nav.short.'), { defaultValue: t(item.key) })}
                                        active={active}
                                        collapsed={navCollapsed}
                                        onClick={() => navigate(item.path)}
                                        badge={showBadge ? (
                                            <Badge
                                                size="xs" color="orange" variant="filled"
                                                className={navCollapsed ? 'af-nav-badge-dot' : undefined}
                                                data-testid={`nav-badge-${item.path.replace('/', '')}`}
                                            >
                                                {pendingApprovals}
                                            </Badge>
                                        ) : undefined}
                                    />
                                );
                            })}
                        </div>
                    ))}
                </div>

                {isAdmin && (
                    <div style={{ paddingTop: 8, marginTop: 8, borderTop: '1px solid var(--sb-divider)' }}>
                        <SidebarItem
                            icon={IconSettings}
                            label={t('nav.admin')}
                            shortLabel={t('nav.short.admin')}
                            active={location.pathname.startsWith('/admin')}
                            collapsed={navCollapsed}
                            onClick={() => navigate('/admin')}
                        />
                    </div>
                )}

                {/* Quem está logado + sair. Morava no header, embaixo do botão flutuante do CopilotKit. */}
                {user && (
                    <SidebarUser
                        name={user.name || user.username}
                        detail={user.email || undefined}
                        logoutLabel={t('header.logout')}
                        onLogout={handleLogout}
                        collapsed={navCollapsed}
                    />
                )}
            </AppShell.Navbar>

            <GlobalSearch pageActions={spotlightActions} />

            <AppShell.Main className="af-main">
                <CopilotKitProvider agents__unsafe_dev_only={{ archflow: copilotAgent }}>
                    <CopilotAppOperator />
                    <CopilotGenerateBridge />
                    <Outlet />
                    <AppCopilotSidebar />
                </CopilotKitProvider>
            </AppShell.Main>
        </AppShell>
    );
}
