import { Stack, Select, Button, SimpleGrid } from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    IconAlertTriangle, IconCircleCheck, IconFilter, IconHistory, IconLoader2, IconPlayerPlay,
} from '@tabler/icons-react';
import { useWorkflowStore } from '../stores/workflow-store';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';
import { StatCard } from '../components/admin/StatCard';
import { VxTable, colunaApagada, colunaStatus, type Registro, type VxColumn } from '../components/VTable';
import { selo } from '../components/VTable/tokens';
import { EXECUTION_STATUS_COLOR } from '../lib/executionStatus';
import { formatDuration, formatInstant } from '../lib/format';

export default function ExecutionHistoryPage() {
    const { t, i18n } = useTranslation();
    const navigate = useNavigate();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const { executions, workflows, loading, error, fetchExecutions, fetchWorkflows } = useWorkflowStore();
    // O filtro de fluxo mora na URL (?workflow=<id>): no useState ele se perdia ao abrir uma execução e voltar
    // (Voltar do navegador ou breadcrumb), e a lista reaparecia com todos os fluxos (RCHFLW-188). Trocar o
    // filtro substitui a entrada do histórico em vez de empilhar uma por escolha.
    const [searchParams, setSearchParams] = useSearchParams();
    const filtroDaUrl = searchParams.get('workflow') ?? '';
    // A tela lê um valor local, atualizado no mesmo clique: o setSearchParams passa por uma navegação do router
    // e só chega um instante depois, e nesse meio o Select controlado ficava com o valor antigo e a lista aberta.
    // A URL continua sendo a fonte quando muda por fora (Voltar, link direto, breadcrumb).
    const [filterWorkflow, setFiltroLocal] = useState(filtroDaUrl);
    useEffect(() => { setFiltroLocal(filtroDaUrl); }, [filtroDaUrl]);
    const setFilterWorkflow = (value: string) => {
        setFiltroLocal(value);
        setSearchParams((prev) => {
            const next = new URLSearchParams(prev);
            if (value) next.set('workflow', value);
            else next.delete('workflow');
            return next;
        }, { replace: true });
    };

    useEffect(() => { fetchWorkflows(); }, [fetchWorkflows]);
    // Single source of executions fetching — runs on mount and whenever the
    // workflow filter changes (avoids the duplicate mount fetch the two
    // separate effects used to fire).
    useEffect(() => { fetchExecutions(filterWorkflow || undefined); }, [filterWorkflow, fetchExecutions]);

    const counts = useMemo(() => ({
        completed: executions.filter((e) => e.status === 'COMPLETED').length,
        failed: executions.filter((e) => e.status === 'FAILED').length,
        running: executions.filter((e) => e.status === 'RUNNING').length,
    }), [executions]);

    const colunas = useMemo((): VxColumn[] => [
        colunaApagada({ field: 'id', title: t('executions.cols.id'), width: 200,
            fieldFormat: (r: Registro) => `${String(r.id).slice(0, 16)}…` }),
        { field: 'workflowName', title: t('executions.cols.workflow'), width: 'auto', minWidth: 220,
            style: { fontWeight: 600 } },
        colunaStatus(
            { field: 'status', title: t('executions.cols.status'), width: 160,
                fieldFormat: (r: Registro) => t(`executions.statuses.${r.status}`, { defaultValue: String(r.status) }) },
            (r) => EXECUTION_STATUS_COLOR[r.status as string] ?? 'gray',
        ),
        colunaApagada({ field: 'startedAt', title: t('executions.cols.started'), width: 210,
            fieldFormat: (r: Registro) => formatInstant(r.startedAt as string, locale) }),
        colunaApagada({ field: 'duration', title: t('executions.cols.duration'), width: 120,
            fieldFormat: (r: Registro) => (r.status === 'RUNNING' ? '…' : formatDuration(r.duration as number)) }),
        { field: 'error', title: t('executions.cols.error'), width: 'auto', minWidth: 220,
            fieldFormat: (r: Registro) => (r.error ? String(r.error) : ''),
            style: () => ({ color: selo('red').tinta, fontSize: 12 }) } as VxColumn,
    ], [t, locale]);

    const total = executions.length;

    return (
        <Stack gap="lg" p="md">
            <PageHeader
                icon={IconHistory}
                accent="violet"
                title={t('executions.title')}
                subtitle={t('executions.subtitle')}
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('executions.stats.total')} value={total} icon={IconPlayerPlay} accent="violet"
                          hint={filterWorkflow ? t('executions.hints.filtered') : t('executions.hints.total')} />
                <StatCard label={t('executions.stats.completed')} value={counts.completed} icon={IconCircleCheck}
                          accent="teal"
                          hint={total > 0
                              ? t('executions.hints.successRate', { pct: Math.round((counts.completed / total) * 100) })
                              : t('executions.hints.none')} />
                <StatCard label={t('executions.stats.failed')} value={counts.failed} icon={IconAlertTriangle}
                          accent="rose" color={counts.failed > 0 ? 'red' : undefined}
                          hint={counts.failed > 0 ? t('executions.hints.failed') : t('executions.hints.failedNone')} />
                <StatCard label={t('executions.stats.running')} value={counts.running} icon={IconLoader2}
                          accent="blue" color={counts.running > 0 ? 'blue' : undefined}
                          hint={t('executions.hints.running')} />
            </SimpleGrid>

            <SectionCard
                flush
                icon={IconPlayerPlay}
                accent="violet"
                title={t('executions.listTitle')}
                description={t('executions.listHint')}
                actions={
                    <Select
                        aria-label={t('executions.allWorkflows')}
                        placeholder={t('executions.allWorkflows')}
                        leftSection={<IconFilter size={14} />}
                        value={filterWorkflow || null}
                        onChange={(v) => setFilterWorkflow(v ?? '')}
                        data={workflows.map(w => ({ value: w.id, label: w.name }))}
                        clearable
                        searchable
                        w={260}
                        size="sm"
                    />
                }
            >
                <VxTable
                    legenda={t('executions.listTitle')}
                    records={executions as unknown as Record<string, unknown>[]}
                    columns={colunas}
                    loading={loading}
                    error={error}
                    onRetry={() => fetchExecutions(filterWorkflow || undefined)}
                    // A lista de onde veio, com o filtro: o breadcrumb do detalhe volta para ela.
                    onRowClick={(r) => navigate(`/executions/${r.id}`,
                        { state: { executionsList: `/executions${searchParams.toString() ? `?${searchParams}` : ''}` } })}
                    emptyMessage={t('executions.empty')}
                    emptyDescription={t('executions.emptyHint')}
                    emptyIcon={<IconPlayerPlay />}
                    emptyAccent="violet"
                    emptyAction={
                        <Button variant="light" size="xs" onClick={() => navigate('/workflows')}>
                            {t('executions.emptyCta')}
                        </Button>
                    }
                    maxHeight={640}
                    testid="executions-table"
                    rowTestId={(r) => `exec-row-${r.id}`}
                />
            </SectionCard>
        </Stack>
    );
}
