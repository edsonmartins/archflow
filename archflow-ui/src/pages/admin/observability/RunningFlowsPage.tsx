import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActionIcon, Badge, Button, Group, Modal, Stack, Text, Tooltip } from '@mantine/core';
import { IconPlayerPlay, IconRefresh } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { RunningFlowDto, observabilityApi } from '../../../services/observability-api';
import { SectionCard } from '../../../components/SectionCard';
import { mensagemDoErro } from '../../../lib/errorMessage';
import { ApiError } from '../../../services/api';
import {
    VxTable, colunaAcoes, colunaApagada, colunaBarra, colunaStatus, type Registro, type VxColumn,
} from '../../../components/VTable';

const POLL_INTERVAL_MS = 2_000;

/**
 * Formats millisecond duration as "1m 23s" or "45s" or "< 1s".
 */
function formatDuration(ms: number, shortLabel: string): string {
    if (ms < 1000) return shortLabel;
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return m > 0 ? `${m}m ${sec}s` : `${sec}s`;
}

const progressPercent = (flow: RunningFlowDto) => {
    if (flow.stepCount <= 0) return 0;
    if (flow.stepIndex < 0) return 0;
    return Math.round(((flow.stepIndex + 1) / flow.stepCount) * 100);
};

/** One row of the table: the DTO plus the values the canvas paints (live duration, progress). */
type Row = RunningFlowDto & { pct: number; liveMs: number };

/**
 * Running Flows Dashboard — shows a live table of currently-executing flows.
 *
 * Update strategy (hybrid):
 * 1. Fetch initial list on mount.
 * 2. Poll every 2 seconds for reconciliation.
 * 3. Duration ticks every second client-side via a separate interval.
 * 4. Cancel button with confirmation modal.
 */
export default function RunningFlowsPage() {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const [flows, setFlows] = useState<RunningFlowDto[]>([]);
    const [loading, setLoading] = useState(true);
    const [tick, setTick] = useState(0);
    const [confirmCancel, setConfirmCancel] = useState<string | null>(null);
    const [cancelling, setCancelling] = useState(false);
    // Cancelamentos aceitos pelo servidor cujo fluxo ainda termina o passo em curso: o motor descarta
    // o resultado e não roda o seguinte, mas a entrada do registro só sai quando o passo acaba. Sem
    // isto a linha sumia e voltava no poll seguinte, parecendo um cancelar que falhou.
    const cancelados = useRef(new Set<string>());

    // Fetch from backend
    const fetchFlows = useCallback(async () => {
        try {
            const data = await observabilityApi.listRunningFlows();
            const listados = new Set(data.map((f) => f.flowId));
            // O servidor já não lista: o fluxo parou de fato, a marca pode sair.
            for (const id of cancelados.current) if (!listados.has(id)) cancelados.current.delete(id);
            setFlows(data.filter((f) => !cancelados.current.has(f.flowId)));
        } catch {
            // Gracefully handle unreachable backend
        } finally {
            setLoading(false);
        }
    }, []);

    // Initial fetch + poll
    useEffect(() => {
        fetchFlows();
        const poll = setInterval(fetchFlows, POLL_INTERVAL_MS);
        return () => clearInterval(poll);
    }, [fetchFlows]);

    // Duration tick — increments every second so displayed durations stay live
    useEffect(() => {
        const timer = setInterval(() => setTick((n) => n + 1), 1000);
        return () => clearInterval(timer);
    }, []);

    // A linha só sai quando o servidor aceitou o cancelamento. Antes o erro era engolido e a linha
    // sumia do mesmo jeito: o operador achava que tinha parado uma execução que seguia rodando
    // (RCHFLW-72).
    const handleCancelConfirm = async () => {
        if (!confirmCancel) return;
        const flowId = confirmCancel;
        setCancelling(true);
        try {
            await observabilityApi.cancelRunningFlow(flowId);
            cancelados.current.add(flowId);
            setFlows((prev) => prev.filter((f) => f.flowId !== flowId));
        } catch (e) {
            notifications.show({
                color: 'red',
                title: t('admin.observability.running.cancelFailed'),
                // 404 é o caso comum (terminou entre a listagem e o cancelar); a frase do servidor vem
                // em inglês, então a tela diz no idioma dela.
                message: e instanceof ApiError && e.status === 404
                    ? t('admin.observability.running.cancelNotRunning')
                    : mensagemDoErro(e, t('admin.observability.running.cancelFailedMsg')),
            });
        } finally {
            setCancelling(false);
            setConfirmCancel(null);
        }
    };

    // `tick` re-derives the rows every second, so the duration column stays live.
    const rows = useMemo<Row[]>(
        () => flows.map((f) => ({ ...f, pct: progressPercent(f), liveMs: Date.now() - new Date(f.startedAt).getTime() })),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [flows, tick],
    );

    const columns = useMemo((): VxColumn[] => [
        { field: 'flowId', title: t('admin.observability.running.cols.flow'), width: 'auto', minWidth: 220,
          style: { fontWeight: 600 } },
        colunaApagada({ field: 'tenantId', title: t('admin.observability.running.cols.tenant'), width: 140,
          fieldFormat: (r: Registro) => (r.tenantId as string | null) ?? '—' }),
        colunaApagada({ field: 'startedAt', title: t('admin.observability.running.cols.started'), width: 130,
          fieldFormat: (r: Registro) => new Date(r.startedAt as string).toLocaleTimeString(locale) }),
        colunaStatus(
            { field: 'currentStepId', title: t('admin.observability.running.cols.currentStep'), width: 200,
              fieldFormat: (r: Registro) => (r.currentStepId as string | null) ?? '' },
            () => 'blue',
        ),
        colunaBarra(
            { field: 'pct', title: t('admin.observability.running.cols.progress'), width: 170,
              fieldFormat: (r: Registro) => {
                  const idx = r.stepIndex as number;
                  return `${idx >= 0 ? idx + 1 : 0} / ${r.stepCount as number}`;
              } },
            (r) => ((r.pct as number) === 100 ? 'green' : 'blue'),
        ),
        { field: 'liveMs', title: t('admin.observability.running.cols.duration'), width: 120,
          style: { fontWeight: 600 },
          fieldFormat: (r: Registro) => formatDuration(r.liveMs as number, t('admin.observability.running.durShort')) },
        colunaAcoes([
            { nome: 'cancel', icone: 'stop', rotulo: t('admin.observability.running.cancelTooltip'), cor: 'red' },
        ]),
    ], [t, locale]);

    return (
        <>
            <SectionCard
                flush
                icon={IconPlayerPlay}
                accent="green"
                title={t('admin.observability.running.title')}
                description={t('admin.observability.running.hint')}
                actions={
                    <>
                        <Badge
                            variant="light"
                            color={flows.length > 0 ? 'green' : 'gray'}
                            role="status"
                            aria-live="polite"
                        >
                            {t('admin.observability.running.activeCount', { count: flows.length })}
                        </Badge>
                        <Tooltip label={t('admin.observability.running.refreshNow')}>
                            <ActionIcon
                                variant="subtle"
                                color="gray"
                                onClick={fetchFlows}
                                aria-label={t('admin.observability.running.refreshNow')}
                            >
                                <IconRefresh size={16} />
                            </ActionIcon>
                        </Tooltip>
                    </>
                }
            >
                <VxTable
                    legenda={t('admin.observability.running.title')}
                    records={rows as unknown as Registro[]}
                    columns={columns}
                    loading={loading}
                    onIconClick={(nome, r) => {
                        if (nome === 'cancel') setConfirmCancel(r.flowId as string);
                    }}
                    emptyMessage={t('admin.observability.running.empty')}
                    emptyDescription={t('admin.observability.running.emptyHint')}
                    emptyIcon={<IconPlayerPlay />}
                    emptyAccent="green"
                    testid="running-flows"
                    rowTestId={(r) => `running-row-${r.flowId}`}
                />
            </SectionCard>

            {/* Cancel confirmation modal */}
            <Modal
                opened={confirmCancel !== null}
                onClose={() => setConfirmCancel(null)}
                title={t('admin.observability.running.cancelTitle')}
                centered
                size="sm"
            >
                <Stack gap="md">
                    <Text size="sm">
                        {t('admin.observability.running.cancelPrompt')}{' '}
                        <Text component="span" fw={600} ff="monospace">
                            {confirmCancel}
                        </Text>
                        {t('admin.observability.running.cancelWarn')}
                    </Text>
                    <Group justify="flex-end" gap="xs">
                        <Button variant="default" onClick={() => setConfirmCancel(null)}>
                            {t('admin.observability.running.keepRunning')}
                        </Button>
                        <Button color="red" loading={cancelling} onClick={handleCancelConfirm}>
                            {t('admin.observability.running.cancelBtn')}
                        </Button>
                    </Group>
                </Stack>
            </Modal>
        </>
    );
}
