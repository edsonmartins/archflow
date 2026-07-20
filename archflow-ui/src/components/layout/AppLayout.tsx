import { useEffect, useMemo, useState } from 'react';
import { AppShell, NavLink, Group, Text, Badge, ActionIcon, Tooltip, Kbd } from '@mantine/core';
import { Spotlight, spotlight, type SpotlightActionData, type SpotlightActionGroupData } from '@mantine/spotlight';
import { HttpAgent } from '@ag-ui/client';
import { CopilotKitProvider, CopilotSidebar } from '@copilotkit/react-core/v2';
import '@copilotkit/react-core/v2/styles.css';
import CopilotAppOperator from '../copilot/CopilotAppOperator';
import CopilotGenerateBridge from '../copilot/CopilotGenerateBridge';
import {
    IconTopologyRing,
    IconPlayerPlay,
    IconMessageCircle,
    IconTemplate,
    IconMicrophone,
    IconShieldCheck,
    IconLogout,
    IconSun,
    IconMoon,
    IconLayoutSidebarLeftCollapse,
    IconLayoutSidebarLeftExpand,
    IconBox,
    IconPlayerPlayFilled,
    IconSitemap,
    IconPlugConnected,
    IconSparkles,
    IconSchema,
    IconSettings,
    IconSearch,
    IconLayoutDashboard,
} from '@tabler/icons-react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../../stores/auth-store';
import { useTenantStore } from '../../stores/useTenantStore';
import { useWorkflowStore } from '../../stores/workflow-store';
import { useColorScheme } from '../../App';
import { approvalApi } from '../../services/approval-api';
import LanguageSwitcher from './LanguageSwitcher';

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
    {
        key: 'nav.groups.extend',
        items: [
            { key: 'nav.marketplace',   icon: IconBox,              path: '/marketplace' },
        ],
    },
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
            // Injeta o token ATUAL a cada chamada (o token pode ser renovado
            // durante a sessão) — sem isso o /ag-ui/agent volta 401 com auth
            // habilitada e o Copilot morre silenciosamente.
            const authFetch: typeof window.fetch = (input, init) => {
                const token = sessionStorage.getItem('archflow_token');
                const headers = new Headers(init?.headers);
                if (token && !headers.has('Authorization')) {
                    headers.set('Authorization', `Bearer ${token}`);
                }
                return window.fetch(input, { ...init, headers });
            };
            return new HttpAgent({ url: '/ag-ui/agent', fetch: authFetch }) as unknown as
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
        const tenantId = impersonating?.id ?? (currentRole === 'superadmin' ? 'all' : 'default');
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

    // ── Global search (⌘K) — navigation + workflows ─────────────────
    const { workflows, fetchWorkflows } = useWorkflowStore();
    useEffect(() => { fetchWorkflows().catch(() => { /* opportunistic */ }); }, [fetchWorkflows]);

    const spotlightActions = useMemo<(SpotlightActionGroupData | SpotlightActionData)[]>(() => {
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
        if (workflows.length > 0) {
            nav.push({
                group: t('nav.workflows'),
                actions: workflows.slice(0, 30).map((w) => ({
                    id: `wf-${w.id}`,
                    label: w.name,
                    description: w.description || undefined,
                    onClick: () => navigate(`/editor/${w.id}`),
                    leftSection: <IconTopologyRing size={18} />,
                })),
            });
        }
        return nav;
    }, [t, navigate, isAdmin, workflows]);

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
    const navWidth = navCollapsed ? 56 : 220;

    return (
        // key forces AppShell to re-evaluate navbar.width. Some Mantine
        // v7 builds don't re-apply the CSS variable when only width
        // changes; remounting on toggle is the safe escape hatch.
        <AppShell
            key={navCollapsed ? 'collapsed' : 'expanded'}
            navbar={{ width: navWidth, breakpoint: 'sm' }}
            header={{ height: 52 }}
            padding="md"
        >
            <AppShell.Header>
                <Group h="100%" px="md" justify="space-between">
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
                        <Text fw={700} size="lg">{t('common.appName')}</Text>
                        {!navCollapsed && <Badge variant="light" size="sm">v1.0</Badge>}
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
                        {user && (
                            <Group gap={4}>
                                <Text size="sm" c="dimmed">{user.name || user.username}</Text>
                                <Tooltip label={t('header.logout')}>
                                    <ActionIcon variant="subtle" color="red" onClick={handleLogout} aria-label={t('header.logout')}>
                                        <IconLogout size={18} />
                                    </ActionIcon>
                                </Tooltip>
                            </Group>
                        )}
                    </Group>
                </Group>
            </AppShell.Header>

            <AppShell.Navbar p="xs">
                {NAV_GROUPS.map((group, groupIndex) => (
                    <div key={group.key}>
                        {navCollapsed ? (
                            groupIndex > 0 && (
                                <div
                                    style={{
                                        borderTop: '1px solid var(--color-border-tertiary)',
                                        margin: '6px 4px',
                                    }}
                                    aria-hidden
                                />
                            )
                        ) : (
                            <Text
                                size="xs"
                                c="dimmed"
                                fw={600}
                                tt="uppercase"
                                px="xs"
                                pt={groupIndex > 0 ? 'sm' : 4}
                                pb={4}
                                style={{ letterSpacing: 0.5 }}
                            >
                                {t(group.key)}
                            </Text>
                        )}
                        {group.items.map((item) => {
                            const active =
                                item.path === '/'
                                    ? location.pathname === '/'
                                    : location.pathname === item.path ||
                                      location.pathname.startsWith(`${item.path}/`);
                            const showBadge = item.withBadge && pendingApprovals > 0;
                            // Collapsed: render as a narrow icon-only button with
                            // a tooltip so the user still knows what each icon
                            // means. Mantine's NavLink doesn't natively support
                            // this so we render a compact ActionIcon instead.
                            const label = t(item.key);
                            if (navCollapsed) {
                                return (
                                    <Tooltip key={item.path} label={label} position="right" withArrow>
                                        <ActionIcon
                                            variant={active ? 'light' : 'subtle'}
                                            size="lg"
                                            onClick={() => navigate(item.path)}
                                            aria-label={label}
                                            mb={4}
                                            style={{ position: 'relative', width: '100%' }}
                                        >
                                            <item.icon size={18} />
                                            {showBadge && (
                                                <Badge
                                                    size="xs" color="orange" variant="filled"
                                                    style={{
                                                        position: 'absolute', top: 2, right: 2,
                                                        padding: '0 4px', height: 14, minWidth: 14,
                                                    }}
                                                    data-testid={`nav-badge-${item.path.replace('/', '')}`}
                                                >
                                                    {pendingApprovals}
                                                </Badge>
                                            )}
                                        </ActionIcon>
                                    </Tooltip>
                                );
                            }
                            return (
                                <NavLink
                                    key={item.path}
                                    label={label}
                                    leftSection={<item.icon size={18} />}
                                    rightSection={
                                        showBadge ? (
                                            <Badge
                                                size="xs"
                                                color="orange"
                                                variant="filled"
                                                data-testid={`nav-badge-${item.path.replace('/', '')}`}
                                            >
                                                {pendingApprovals}
                                            </Badge>
                                        ) : undefined
                                    }
                                    active={active}
                                    onClick={() => navigate(item.path)}
                                    variant="light"
                                />
                            );
                        })}
                    </div>
                ))}

                {isAdmin && (
                    <div style={{ marginTop: 'auto', paddingTop: 8, borderTop: '1px solid var(--color-border-tertiary)' }}>
                        {navCollapsed ? (
                            <Tooltip label={t('nav.admin')} position="right" withArrow>
                                <ActionIcon
                                    variant="subtle"
                                    size="lg"
                                    onClick={() => navigate('/admin')}
                                    aria-label={t('nav.admin')}
                                    style={{ width: '100%' }}
                                >
                                    <IconSettings size={18} />
                                </ActionIcon>
                            </Tooltip>
                        ) : (
                            <NavLink
                                label={t('nav.admin')}
                                leftSection={<IconSettings size={18} />}
                                onClick={() => navigate('/admin')}
                                variant="subtle"
                            />
                        )}
                    </div>
                )}
            </AppShell.Navbar>

            <Spotlight
                actions={spotlightActions}
                shortcut="mod + k"
                highlightQuery
                searchProps={{
                    leftSection: <IconSearch size={18} />,
                    placeholder: t('nav.searchPlaceholder'),
                }}
                nothingFound={t('nav.searchNothing')}
            />

            <AppShell.Main>
                <CopilotKitProvider agents__unsafe_dev_only={{ archflow: copilotAgent }}>
                    <CopilotAppOperator />
                    <CopilotGenerateBridge />
                    <Outlet />
                    <CopilotSidebar agentId="archflow" defaultOpen={false} />
                </CopilotKitProvider>
            </AppShell.Main>
        </AppShell>
    );
}
