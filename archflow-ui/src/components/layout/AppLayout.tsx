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
} from '@tabler/icons-react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../stores/auth-store';
import { useTenantStore } from '../../stores/useTenantStore';
import { useColorScheme } from '../../App';
import { approvalApi } from '../../services/approval-api';

const NAV_ITEMS = [
    { label: 'Workflows', icon: IconTopologyRing, path: '/' },
    { label: 'Editor', icon: IconLayoutDashboard, path: '/editor' },
    { label: 'Executions', icon: IconPlayerPlay, path: '/executions' },
    { label: 'Conversations', icon: IconMessageCircle, path: '/conversations' },
    { label: 'Approvals', icon: IconShieldCheck, path: '/approvals', withBadge: true },
    { label: 'Templates', icon: IconTemplate, path: '/templates' },
    { label: 'Voice', icon: IconMicrophone, path: '/playground/voice' },
];

export default function AppLayout() {
    const navigate = useNavigate();
    const location = useLocation();
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

    return (
        <AppShell
            navbar={{ width: 220, breakpoint: 'sm' }}
            header={{ height: 52 }}
            padding="md"
        >
            <AppShell.Header>
                <Group h="100%" px="md" justify="space-between">
                    <Group gap="xs">
                        <Text fw={700} size="lg">Archflow</Text>
                        <Badge variant="light" size="sm">v1.0</Badge>
                    </Group>
                    <Group gap="xs">
                        <Tooltip label={dark ? 'Light mode' : 'Dark mode'}>
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
                                <Tooltip label="Logout">
                                    <ActionIcon variant="subtle" color="red" onClick={handleLogout} aria-label="Logout">
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
                    return (
                        <NavLink
                            key={item.path}
                            label={item.label}
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
