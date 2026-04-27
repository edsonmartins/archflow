import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
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
    { value: 'overview', key: 'overview', path: '/admin/observability',         icon: IconActivity   },
    { value: 'running',  key: 'running',  path: '/admin/observability/running',  icon: IconPlayerPlay },
    { value: 'traces',   key: 'traces',   path: '/admin/observability/traces',   icon: IconTimeline   },
    { value: 'metrics',  key: 'metrics',  path: '/admin/observability/metrics',  icon: IconChartBar   },
    { value: 'audit',    key: 'audit',    path: '/admin/observability/audit',    icon: IconHistory    },
    { value: 'live',     key: 'live',     path: '/admin/observability/live',     icon: IconRadar2     },
] as const;

/**
 * Sub-layout for the {@code /admin/observability} area. Exposes a
 * horizontal tab strip that maps 1:1 to the child routes registered
 * in App.tsx. The currently active tab is derived from the pathname.
 */
export default function ObservabilityLayout() {
    const navigate = useNavigate();
    const location = useLocation();
    const { t } = useTranslation();

    const active = resolveActiveTab(location.pathname);

    return (
        <Stack p="md" gap="md">
            <Group justify="space-between">
                <Group gap={8}>
                    <IconAlertCircle size={18} />
                    <strong style={{ fontSize: 18 }}>{t('admin.observability.title')}</strong>
                </Group>
            </Group>
            <Tabs value={active} onChange={(v) => v && navigate(TABS.find((tab) => tab.value === v)?.path ?? '/admin/observability')}>
                <Tabs.List>
                    {TABS.map((tab) => (
                        <Tabs.Tab key={tab.value} value={tab.value} leftSection={<tab.icon size={14} />}>
                            {t(`admin.observability.tabs.${tab.key}`)}
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
