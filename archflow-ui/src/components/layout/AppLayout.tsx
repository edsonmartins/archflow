import { AppShell, NavLink, Group, Text, Badge, ActionIcon, Tooltip } from '@mantine/core';
import {
    IconLayoutDashboard,
    IconTopologyRing,
    IconPlayerPlay,
    IconLogout,
    IconSun,
    IconMoon,
} from '@tabler/icons-react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../stores/auth-store';
import { useState } from 'react';

const NAV_ITEMS = [
    { label: 'Workflows', icon: IconTopologyRing, path: '/' },
    { label: 'Editor', icon: IconLayoutDashboard, path: '/editor' },
    { label: 'Executions', icon: IconPlayerPlay, path: '/executions' },
];

export default function AppLayout() {
    const navigate = useNavigate();
    const location = useLocation();
    const { user, logout } = useAuthStore();
    const [dark, setDark] = useState(false);

    const handleLogout = async () => {
        await logout();
        navigate('/login');
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
                                onClick={() => setDark(!dark)}
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
                {NAV_ITEMS.map((item) => (
                    <NavLink
                        key={item.path}
                        label={item.label}
                        leftSection={<item.icon size={18} />}
                        active={location.pathname === item.path}
                        onClick={() => navigate(item.path)}
                        variant="light"
                    />
                ))}
            </AppShell.Navbar>

            <AppShell.Main>
                <Outlet />
            </AppShell.Main>
        </AppShell>
    );
}
