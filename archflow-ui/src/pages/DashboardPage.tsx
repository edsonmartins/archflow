import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    Button, Card, Group, SimpleGrid, Stack, Table, Text, Title,
} from '@mantine/core';
import {
    IconPlus, IconSparkles, IconTemplate, IconTopologyRing, IconShieldCheck,
} from '@tabler/icons-react';
import { useWorkflowStore } from '../stores/workflow-store';
import { useTenantStore } from '../stores/useTenantStore';
import { approvalApi } from '../services/approval-api';
import { StatCard } from '../components/admin/StatCard';
import { StatusBadge } from '../components/StatusBadge';
import { DataTable, clickableRow, tabularNums } from '../components/DataTable';

const STATUS_COLOR: Record<string, string> = {
    RUNNING:   'blue',
    COMPLETED: 'teal',
    FAILED:    'red',
    PAUSED:    'yellow',
    CANCELLED: 'gray',
};

/**
 * Post-login home: at-a-glance health (workflows, recent runs, pending
 * approvals) plus the three creation paths, so a returning user lands on
 * activity instead of a bare list. The workflow list itself lives at
 * /workflows.
 */
export default function DashboardPage() {
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const { workflows, executions, loading, fetchWorkflows, fetchExecutions } = useWorkflowStore();
    const { impersonating, currentRole } = useTenantStore();
    const [pendingApprovals, setPendingApprovals] = useState<number | null>(null);

    useEffect(() => {
        fetchWorkflows();
        fetchExecutions();
    }, [fetchWorkflows, fetchExecutions]);

    useEffect(() => {
        const tenantId = impersonating?.id ?? (currentRole === 'superadmin' ? 'all' : 'default');
        approvalApi.pendingCount(tenantId)
            .then((res) => setPendingApprovals(res.count))
            .catch(() => setPendingApprovals(null));
    }, [impersonating, currentRole]);

    const activeWorkflows = workflows.filter((w) => w.status === 'active').length;
    const failedRuns = executions.filter((e) => e.status === 'FAILED').length;
    const recent = executions.slice(0, 5);

    return (
        <Stack gap="md" p="md" data-testid="dashboard">
            <Group justify="space-between" wrap="wrap">
                <Title order={2}>{t('dashboard.title')}</Title>
                <Group gap="sm">
                    <Button
                        leftSection={<IconPlus size={16} />}
                        onClick={() => navigate('/workflows?new=1')}
                        data-testid="dashboard-new"
                    >
                        {t('workflows.newWorkflow')}
                    </Button>
                    <Button
                        variant="light"
                        color="grape"
                        leftSection={<IconSparkles size={16} />}
                        onClick={() => navigate('/workflows?new=ai')}
                    >
                        {t('workflows.createModal.ai')}
                    </Button>
                    <Button
                        variant="light"
                        leftSection={<IconTemplate size={16} />}
                        onClick={() => navigate('/templates')}
                    >
                        {t('workflows.browseTemplates')}
                    </Button>
                </Group>
            </Group>

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('dashboard.stats.workflows')} value={workflows.length} />
                <StatCard label={t('dashboard.stats.active')} value={activeWorkflows} color="teal" />
                <StatCard
                    label={t('dashboard.stats.failedRuns')}
                    value={failedRuns}
                    color={failedRuns > 0 ? 'red' : undefined}
                />
                <StatCard
                    label={t('dashboard.stats.pendingApprovals')}
                    value={pendingApprovals ?? '—'}
                    color={pendingApprovals ? 'orange' : undefined}
                />
            </SimpleGrid>

            <Card withBorder radius="md" padding="md">
                <Group justify="space-between" mb="sm">
                    <Text fw={600}>{t('dashboard.recentExecutions')}</Text>
                    <Button variant="subtle" size="xs" onClick={() => navigate('/executions')}>
                        {t('dashboard.seeAll')}
                    </Button>
                </Group>
                <DataTable
                    columns={4}
                    minWidth={560}
                    highlightOnHover
                    loading={loading && executions.length === 0}
                    isEmpty={recent.length === 0}
                    emptyMessage={t('executions.empty')}
                    emptyAction={
                        <Button variant="light" size="xs" onClick={() => navigate('/workflows')}>
                            {t('executions.emptyCta')}
                        </Button>
                    }
                    head={
                        <Table.Tr>
                            <Table.Th>{t('executions.cols.workflow')}</Table.Th>
                            <Table.Th>{t('executions.cols.status')}</Table.Th>
                            <Table.Th>{t('executions.cols.started')}</Table.Th>
                            <Table.Th>{t('executions.cols.duration')}</Table.Th>
                        </Table.Tr>
                    }
                >
                    {recent.map((exec) => (
                        <Table.Tr key={exec.id} {...clickableRow(() => navigate(`/executions/${exec.id}`))}>
                            <Table.Td><Text size="sm" fw={500}>{exec.workflowName}</Text></Table.Td>
                            <Table.Td>
                                <StatusBadge
                                    status={exec.status}
                                    color={STATUS_COLOR[exec.status] ?? 'gray'}
                                    label={t(`executions.statuses.${exec.status}`, { defaultValue: exec.status })}
                                />
                            </Table.Td>
                            <Table.Td>
                                <Text size="xs" c="dimmed">
                                    {new Date(exec.startedAt).toLocaleString(locale)}
                                </Text>
                            </Table.Td>
                            <Table.Td>
                                <Text size="xs" ff="DM Mono, monospace" style={tabularNums}>
                                    {exec.duration != null ? `${(exec.duration / 1000).toFixed(1)}s` : '…'}
                                </Text>
                            </Table.Td>
                        </Table.Tr>
                    ))}
                </DataTable>
            </Card>

            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
                <ShortcutCard
                    icon={<IconTopologyRing size={20} />}
                    title={t('dashboard.shortcuts.workflows')}
                    description={t('dashboard.shortcuts.workflowsHint', { count: workflows.length })}
                    onClick={() => navigate('/workflows')}
                    testId="dashboard-workflows"
                />
                <ShortcutCard
                    icon={<IconShieldCheck size={20} />}
                    title={t('dashboard.shortcuts.approvals')}
                    description={t('dashboard.shortcuts.approvalsHint', { count: pendingApprovals ?? 0 })}
                    onClick={() => navigate('/approvals')}
                    testId="dashboard-approvals"
                />
            </SimpleGrid>
        </Stack>
    );
}

function ShortcutCard({
    icon, title, description, onClick, testId,
}: {
    icon: React.ReactNode;
    title: string;
    description: string;
    onClick: () => void;
    testId: string;
}) {
    return (
        <Card
            withBorder
            radius="md"
            padding="md"
            component="button"
            type="button"
            onClick={onClick}
            data-testid={testId}
            style={{ cursor: 'pointer', textAlign: 'left' }}
        >
            <Group gap="sm" wrap="nowrap">
                {icon}
                <Stack gap={0}>
                    <Text fw={600} size="sm">{title}</Text>
                    <Text size="xs" c="dimmed">{description}</Text>
                </Stack>
            </Group>
        </Card>
    );
}
