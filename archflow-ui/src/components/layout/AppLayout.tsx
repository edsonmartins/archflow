import { useEffect, useState } from 'react';
import { AppShell, NavLink, Group, Text, Badge, ActionIcon, Tooltip } from '@mantine/core';
import {
    IconLayoutDashboard,
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
} from '@tabler/icons-react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../../stores/auth-store';
import { useTenantStore } from '../../stores/useTenantStore';
import { useColorScheme } from '../../App';
import { approvalApi } from '../../services/approval-api';
import LanguageSwitcher from './LanguageSwitcher';

// Labels come from t() at render time so switching languages updates
// the sidebar without remounting. The `key` is the stable i18n lookup
// path; `path` is the route.
const NAV_ITEMS = [
    { key: 'nav.workflows',     icon: IconTopologyRing,     path: '/' },
    { key: 'nav.editor',        icon: IconLayoutDashboard,  path: '/editor' },
    { key: 'nav.executions',    icon: IconPlayerPlay,       path: '/executions' },
    { key: 'nav.conversations', icon: IconMessageCircle,    path: '/conversations' },
    { key: 'nav.approvals',     icon: IconShieldCheck,      path: '/approvals', withBadge: true },
    { key: 'nav.templates',     icon: IconTemplate,         path: '/templates' },
    { key: 'nav.marketplace',   icon: IconBox,              path: '/marketplace' },
    { key: 'nav.agentLab',      icon: IconPlayerPlayFilled, path: '/playground/agent' },
    { key: 'nav.voice',         icon: IconMicrophone,       path: '/playground/voice' },
];

export default function AppLayout() {
    const navigate = useNavigate();
    const location = useLocation();
    const { t } = useTranslation();
    const { user, logout } = useAuthStore();
    const { impersonating, currentRole } = useTenantStore();
    const [colorScheme, setColorScheme] = useColorScheme();
    const dark = colorScheme === 'dark';
    const [pendingApprovals, setPendingApprovals] = useState(0);

    // Poll the approval count every 30s so the badge stays in sync without
    // needing a dedicated SSE subscription. Errors are swallowed on purpose
    // — the badge is opportunistic, not load-bearing.
    useEffect(() => {
        const tenantId = impersonating ?? (currentRole === 'superadmin' ? 'all' : 'default');
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
                {NAV_ITEMS.map((item) => {
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
            </AppShell.Navbar>

            <AppShell.Main>
                <Outlet />
            </AppShell.Main>
        </AppShell>
    );
}
