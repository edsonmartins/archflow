import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Group, Stack, Tabs } from '@mantine/core';
import {
    IconActivity,
    IconAlertCircle,
    IconChartBar,
    IconHistory,
    IconPlayerPlay,
    IconRadar2,
    IconTimeline,
} from '@tabler/icons-react';

const TABS = [
    { value: 'overview', label: 'Overview', path: '/admin/observability', icon: IconActivity },
    { value: 'running', label: 'Running flows', path: '/admin/observability/running', icon: IconPlayerPlay },
    { value: 'traces', label: 'Traces', path: '/admin/observability/traces', icon: IconTimeline },
    { value: 'metrics', label: 'Metrics', path: '/admin/observability/metrics', icon: IconChartBar },
    { value: 'audit', label: 'Audit log', path: '/admin/observability/audit', icon: IconHistory },
    { value: 'live', label: 'Live events', path: '/admin/observability/live', icon: IconRadar2 },
] as const;

/**
 * Sub-layout for the {@code /admin/observability} area. Exposes a
 * horizontal tab strip that maps 1:1 to the child routes registered
 * in App.tsx. The currently active tab is derived from the pathname.
 */
export default function ObservabilityLayout() {
    const navigate = useNavigate();
    const location = useLocation();

    const active = resolveActiveTab(location.pathname);

    return (
        <Stack p="md" gap="md">
            <Group justify="space-between">
                <Group gap={8}>
                    <IconAlertCircle size={18} />
                    <strong style={{ fontSize: 18 }}>Observability</strong>
                </Group>
            </Group>
            <Tabs value={active} onChange={(v) => v && navigate(TABS.find((t) => t.value === v)?.path ?? '/admin/observability')}>
                <Tabs.List>
                    {TABS.map((tab) => (
                        <Tabs.Tab key={tab.value} value={tab.value} leftSection={<tab.icon size={14} />}>
                            {tab.label}
                        </Tabs.Tab>
                    ))}
                </Tabs.List>
            </Tabs>
            <Outlet />
        </Stack>
    );
}

function resolveActiveTab(pathname: string): string {
    // Order matters — check more specific paths first
    if (pathname.startsWith('/admin/observability/traces')) return 'traces';
    if (pathname.startsWith('/admin/observability/metrics')) return 'metrics';
    if (pathname.startsWith('/admin/observability/audit')) return 'audit';
    if (pathname.startsWith('/admin/observability/running')) return 'running';
    if (pathname.startsWith('/admin/observability/live')) return 'live';
    return 'overview';
}
