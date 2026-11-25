import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, SimpleGrid, Stack, Text } from '@mantine/core';
import {
    IconPlus, IconSparkles, IconTemplate, IconTopologyRing, IconShieldCheck, IconLayoutDashboard,
    IconBolt, IconAlertTriangle, IconPlayerPlay, IconArrowRight,
} from '@tabler/icons-react';
import { useWorkflowStore } from '../stores/workflow-store';
import { useTenantStore } from '../stores/useTenantStore';
import { approvalTenantParam } from '../lib/approvalTenant';
import { approvalApi } from '../services/approval-api';
import { StatCard } from '../components/admin/StatCard';
import { OptionCard } from '../components/OptionCard';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';
import { VxTable, colunaStatus, colunaApagada, type VxColumn } from '../components/VTable';
import { EXECUTION_STATUS_COLOR } from '../lib/executionStatus';
import { formatDuration, formatInstant } from '../lib/format';
import { usePermissions } from '../lib/permissions';

/** Recent-activity window: enough for the table and a meaningful failure count. */
const RECENT_EXECUTIONS_LIMIT = 25;

/**
 * Post-login home: at-a-glance health (workflows, recent runs, pending
 * approvals) plus the three creation paths, so a returning user lands on
 * activity instead of a bare list. The workflow list itself lives at
 * /workflows.
 */
export default function DashboardPage() {
    const navigate = useNavigate();
    // RCHFLW-74: os atalhos de criação só para quem tem workflow:create.
    const canCreate = usePermissions()('workflow:create');
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const { workflows, executions, loading, fetchWorkflows, fetchExecutions } = useWorkflowStore();
    const { impersonating, currentRole } = useTenantStore();
    const [pendingApprovals, setPendingApprovals] = useState<number | null>(null);

    useEffect(() => {
        fetchWorkflows();
        // Bounded fetch: the dashboard needs 5 rows and a recent-failure
        // count, not the tenant's entire execution history.
        fetchExecutions(undefined, RECENT_EXECUTIONS_LIMIT);
    }, [fetchWorkflows, fetchExecutions]);

    useEffect(() => {
        const tenantId = approvalTenantParam(impersonating, currentRole);
        approvalApi.pendingCount(tenantId)
            .then((res) => setPendingApprovals(res.count))
            .catch(() => setPendingApprovals(null));
    }, [impersonating, currentRole]);

    const activeWorkflows = workflows.filter((w) => w.status === 'active').length;
    const failedRuns = executions.filter((e) => e.status === 'FAILED').length;
    const recent = executions.slice(0, 5);

    const colunas = useMemo((): VxColumn[] => [
        { field: 'workflowName', title: t('executions.cols.workflow'), width: 'auto', minWidth: 220,
          style: { fontWeight: 600 } },
        colunaStatus(
            { field: 'status', title: t('executions.cols.status'), width: 160,
              fieldFormat: (r) => t(`executions.statuses.${r.status}`, { defaultValue: String(r.status) }) },
            (r) => EXECUTION_STATUS_COLOR[r.status as keyof typeof EXECUTION_STATUS_COLOR] ?? 'gray',
        ),
        colunaApagada({ field: 'startedAt', title: t('executions.cols.started'), width: 220,
          fieldFormat: (r) => formatInstant(r.startedAt as string, locale) }),
        colunaApagada({ field: 'duration', title: t('executions.cols.duration'), width: 120,
          fieldFormat: (r) => (r.status === 'RUNNING' ? '…' : formatDuration(r.duration as number)) }),
    ], [t, locale]);

    return (
        <Stack gap="lg" p="md" data-testid="dashboard">
            <PageHeader
                icon={IconLayoutDashboard}
                accent="blue"
                title={t('dashboard.title')}
                subtitle={t('dashboard.subtitle')}
                actions={
                    <>
                        {canCreate && (
                            <Button
                                variant="light"
                                color="grape"
                                leftSection={<IconSparkles size={16} />}
                                onClick={() => navigate('/workflows?new=ai')}
                            >
                                {t('workflows.createModal.ai')}
                            </Button>
                        )}
                        <Button
                            variant="default"
                            leftSection={<IconTemplate size={16} />}
                            onClick={() => navigate('/templates')}
                        >
                            {t('workflows.browseTemplates')}
                        </Button>
                        {canCreate && (
                            <Button
                                leftSection={<IconPlus size={16} />}
                                onClick={() => navigate('/workflows?new=1')}
                                data-testid="dashboard-new"
                            >
                                {t('workflows.newWorkflow')}
                            </Button>
                        )}
                    </>
                }
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('dashboard.stats.workflows')}
                    value={workflows.length}
                    icon={IconTopologyRing}
                    accent="blue"
                    hint={t('dashboard.hints.workflows', { count: activeWorkflows })}
                />
                <StatCard
                    label={t('dashboard.stats.active')}
                    value={activeWorkflows}
                    icon={IconBolt}
                    accent="teal"
                    hint={t('dashboard.hints.active')}
                />
                <StatCard
                    label={t('dashboard.stats.failedRuns')}
                    value={failedRuns}
                    color={failedRuns > 0 ? 'red' : undefined}
                    icon={IconAlertTriangle}
                    accent="rose"
                    hint={failedRuns > 0
                        ? t('dashboard.hints.failedRuns', { count: RECENT_EXECUTIONS_LIMIT })
                        : t('dashboard.hints.failedNone')}
                />
                <StatCard
                    label={t('dashboard.stats.pendingApprovals')}
                    value={pendingApprovals ?? '—'}
                    color={pendingApprovals ? 'orange' : undefined}
                    icon={IconShieldCheck}
                    accent="amber"
                    hint={t('dashboard.hints.pendingApprovals')}
                />
            </SimpleGrid>

            <SectionCard
                flush
                icon={IconPlayerPlay}
                accent="violet"
                title={t('dashboard.recentExecutions')}
                description={t('dashboard.recentExecutionsHint')}
                actions={
                    <Button variant="subtle" size="xs" rightSection={<IconArrowRight size={14} />}
                            onClick={() => navigate('/executions')}>
                        {t('dashboard.seeAll')}
                    </Button>
                }
            >
                <VxTable
                    legenda={t('dashboard.recentExecutions')}
                    records={recent as unknown as Record<string, unknown>[]}
                    columns={colunas}
                    loading={loading && executions.length === 0}
                    onRowClick={(r) => navigate(`/executions/${r.id}`)}
                    emptyMessage={t('executions.empty')}
                    emptyIcon={<IconPlayerPlay />}
                    emptyAccent="violet"
                    emptyAction={
                        <Button variant="light" size="xs" onClick={() => navigate('/workflows')}>
                            {t('executions.emptyCta')}
                        </Button>
                    }
                    testid="dashboard-recent"
                    rowTestId={(r) => `exec-row-${r.id}`}
                />
            </SectionCard>

            <Stack gap="xs">
                <Text size="xs" fw={600} c="dimmed" tt="uppercase" style={{ letterSpacing: '0.06em' }}>
                    {t('dashboard.quickActions')}
                </Text>
                <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
                    <OptionCard
                        icon={<IconTopologyRing size={20} />}
                        color="archBlue"
                        title={t('dashboard.shortcuts.workflows')}
                        description={t('dashboard.shortcuts.workflowsHint', { count: workflows.length })}
                        onClick={() => navigate('/workflows')}
                        testId="dashboard-workflows"
                    />
                    <OptionCard
                        icon={<IconShieldCheck size={20} />}
                        color="orange"
                        title={t('dashboard.shortcuts.approvals')}
                        description={t('dashboard.shortcuts.approvalsHint', { count: pendingApprovals ?? 0 })}
                        onClick={() => navigate('/approvals')}
                        testId="dashboard-approvals"
                    />
                </SimpleGrid>
            </Stack>
        </Stack>
    );
}
