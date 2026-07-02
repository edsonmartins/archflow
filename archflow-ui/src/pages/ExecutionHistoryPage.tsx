import { Stack, Group, Title, Select, Text, Table, Button } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useWorkflowStore } from '../stores/workflow-store';
import { DataTable, clickableRow, tabularNums } from '../components/DataTable';
import { StatusBadge } from '../components/StatusBadge';
import { EXECUTION_STATUS_COLOR } from '../lib/executionStatus';
import { formatDuration } from '../lib/format';

export default function ExecutionHistoryPage() {
    const { t, i18n } = useTranslation();
    const navigate = useNavigate();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const { executions, workflows, loading, error, fetchExecutions, fetchWorkflows } = useWorkflowStore();
    const [filterWorkflow, setFilterWorkflow] = useState('');

    useEffect(() => { fetchWorkflows(); }, [fetchWorkflows]);
    // Single source of executions fetching — runs on mount and whenever the
    // workflow filter changes (avoids the duplicate mount fetch the two
    // separate effects used to fire).
    useEffect(() => { fetchExecutions(filterWorkflow || undefined); }, [filterWorkflow, fetchExecutions]);

    return (
        <Stack gap="md" p="md">
            <Group justify="space-between" wrap="wrap">
                <Title order={2}>{t('executions.title')}</Title>
                <Select
                    aria-label={t('executions.allWorkflows')}
                    placeholder={t('executions.allWorkflows')}
                    value={filterWorkflow || null}
                    onChange={(v) => setFilterWorkflow(v ?? '')}
                    data={workflows.map(w => ({ value: w.id, label: w.name }))}
                    clearable
                    w={240}
                    size="sm"
                />
            </Group>

            <DataTable
                columns={6}
                minWidth={820}
                striped
                highlightOnHover
                loading={loading}
                error={error}
                onRetry={() => fetchExecutions(filterWorkflow || undefined)}
                isEmpty={executions.length === 0}
                emptyMessage={t('executions.empty')}
                emptyAction={
                    <Button variant="light" size="xs" onClick={() => navigate('/workflows')}>
                        {t('executions.emptyCta')}
                    </Button>
                }
                head={
                    <Table.Tr>
                        <Table.Th>{t('executions.cols.id')}</Table.Th>
                        <Table.Th>{t('executions.cols.workflow')}</Table.Th>
                        <Table.Th>{t('executions.cols.status')}</Table.Th>
                        <Table.Th>{t('executions.cols.started')}</Table.Th>
                        <Table.Th>{t('executions.cols.duration')}</Table.Th>
                        <Table.Th>{t('executions.cols.error')}</Table.Th>
                    </Table.Tr>
                }
            >
                {executions.map((exec) => {
                    const isRunning = exec.status === 'RUNNING';
                    return (
                        <Table.Tr key={exec.id} {...clickableRow(() => navigate(`/executions/${exec.id}`))}>
                            <Table.Td>
                                <Text size="xs" ff="DM Mono, monospace" c="dimmed">
                                    {exec.id.slice(0, 16)}…
                                </Text>
                            </Table.Td>
                            <Table.Td><Text size="sm" fw={500}>{exec.workflowName}</Text></Table.Td>
                            <Table.Td>
                                <StatusBadge
                                    status={exec.status}
                                    color={EXECUTION_STATUS_COLOR[exec.status] ?? 'gray'}
                                    label={t(`executions.statuses.${exec.status}`, { defaultValue: exec.status })}
                                />
                            </Table.Td>
                            <Table.Td>
                                <Text size="xs" c="dimmed">
                                    {new Date(exec.startedAt).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })}
                                </Text>
                            </Table.Td>
                            <Table.Td>
                                <Text size="xs" ff="DM Mono, monospace" style={tabularNums}>
                                    {isRunning ? '…' : formatDuration(exec.duration)}
                                </Text>
                            </Table.Td>
                            <Table.Td>
                                <Text size="xs" c="red" lineClamp={1} title={exec.error ?? undefined}>
                                    {exec.error ?? ''}
                                </Text>
                            </Table.Td>
                        </Table.Tr>
                    );
                })}
            </DataTable>
        </Stack>
    );
}
