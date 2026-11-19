import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Stack, Tabs } from '@mantine/core';
import {
    IconActivity,
    IconActivityHeartbeat,
    IconChartBar,
    IconHistory,
    IconPlayerPlay,
    IconRadar2,
    IconTimeline,
} from '@tabler/icons-react';
import { PageHeader } from '../../../components/PageHeader';

const TABS = [
    { value: 'overview', key: 'overview', path: '/admin/observability',         icon: IconActivity   },
    { value: 'running',  key: 'running',  path: '/admin/observability/running',  icon: IconPlayerPlay },
    { value: 'traces',   key: 'traces',   path: '/admin/observability/traces',   icon: IconTimeline   },
    { value: 'metrics',  key: 'metrics',  path: '/admin/observability/metrics',  icon: IconChartBar   },
    { value: 'audit',    key: 'audit',    path: '/admin/observability/audit',    icon: IconHistory    },
    { value: 'live',     key: 'live',     path: '/admin/observability/live',     icon: IconRadar2     },
] as const;

/**
 * Sub-layout for the {@code /admin/observability} area: the area's
 * PageHeader plus a horizontal tab strip that maps 1:1 to the child routes
 * registered in App.tsx. The active tab is derived from the pathname.
 *
 * Deep pages (a single trace) bring their own PageHeader with breadcrumbs,
 * so the area header and tabs step aside there instead of stacking two
 * titles on the same screen.
 */
export default function ObservabilityLayout() {
    const navigate = useNavigate();
    const location = useLocation();
    const { t } = useTranslation();

    const active = resolveActiveTab(location.pathname);
    const deepPage = /^\/admin\/observability\/traces\/[^/]+/.test(location.pathname);

    if (deepPage) {
        return (
            <Stack gap="lg" p="md">
                <Outlet />
            </Stack>
        );
    }

    return (
        <Stack gap="lg" p="md">
            <Stack gap="sm">
                <PageHeader
                    icon={IconActivityHeartbeat}
                    accent="green"
                    title={t('admin.observability.title')}
                    subtitle={t('admin.observability.subtitle')}
                />
                <Tabs
                    data-accent="green"
                    value={active}
                    onChange={(v) => v && navigate(TABS.find((tab) => tab.value === v)?.path ?? '/admin/observability')}
                >
                    <Tabs.List>
                        {TABS.map((tab) => (
                            <Tabs.Tab key={tab.value} value={tab.value} leftSection={<tab.icon size={15} stroke={1.8} />}>
                                {t(`admin.observability.tabs.${tab.key}`)}
                            </Tabs.Tab>
                        ))}
                    </Tabs.List>
                </Tabs>
            </Stack>
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
