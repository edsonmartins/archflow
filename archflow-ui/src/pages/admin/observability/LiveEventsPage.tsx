import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActionIcon, Alert, Badge, Box, Button, Grid, Group, Select, Switch, TextInput, Tooltip } from '@mantine/core';
import {
    IconAlertCircle,
    IconBraces,
    IconPlayerPause,
    IconPlayerPlay,
    IconPlugConnected,
    IconPlugConnectedX,
    IconRadar2,
    IconRefresh,
    IconTrash,
} from '@tabler/icons-react';
import { ArchflowEventStream, type ArchflowEvent, type StreamStatus } from '../../../services/event-stream';
import { executionApi, type ExecutionSummary } from '../../../services/api';
import { isTerminalExecutionStatus } from '../../../lib/executionStatus';
import { JsonViewer } from '../../../components/JsonViewer';
import EmptyState from '../../../components/EmptyState';
import { SectionCard } from '../../../components/SectionCard';
import { VxTable, colunaApagada, colunaStatus, type Registro, type VxColumn } from '../../../components/VTable';

/**
 * Live SSE event viewer for debugging. Reuses {@link ArchflowEventStream}
 * from P0 — the user picks an execution and sees every frame arriving in real
 * time. Scrollback is capped at 500 entries. Clicking an event opens its
 * payload in the side panel.
 *
 * Escolher a execução, e não digitar tenant e sessão (RCHFLW-50): o motor publica os eventos de
 * fluxo e de passo no canal `<tenant da execução>:<executionId>`, e a lista vem de
 * `GET /executions`, que só devolve as do tenant da requisição. O stream é aberto sem tenant
 * (`GET /stream/{executionId}`): o servidor usa o tenant de quem está logado (o do token, ou o
 * personificado já conferido) — a página não tem como escolher o canal de outro tenant, e a rota com
 * tenant responde 403 a qualquer outro (RCHFLW-49).
 *
 * O canal livre (tenant + sessão digitados) continua disponível atrás de uma chave, desligada por
 * padrão: é o único jeito de assinar uma conversa ou o canal administrativo (`__admin__` + o próprio
 * tenant, com os eventos de todos os fluxos dele), que não são execuções da lista. O servidor confere o
 * tenant do mesmo jeito nas duas formas.
 */
export default function LiveEventsPage() {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const [executions, setExecutions] = useState<ExecutionSummary[]>([]);
    const [loadingExecutions, setLoadingExecutions] = useState(false);
    const [executionId, setExecutionId] = useState<string | null>(null);
    const [freeChannel, setFreeChannel] = useState(false);
    const [tenantId, setTenantId] = useState('');
    const [sessionId, setSessionId] = useState('');
    const [paused, setPaused] = useState(false);
    const [expandedId, setExpandedId] = useState<string | null>(null);
    const [status, setStatus] = useState<StreamStatus>('idle');
    const [events, setEvents] = useState<ArchflowEvent[]>([]);
    const [error, setError] = useState<string | null>(null);
    const [attached, setAttached] = useState<{ tenantId?: string; sessionId: string } | null>(null);
    const streamRef = useRef<ArchflowEventStream | null>(null);

    const canAttach = freeChannel ? sessionId.trim().length > 0 : !!executionId;
    // O stream é só ao vivo, sem replay: uma execução que já terminou não emite mais nada, e conectar
    // nela deixaria o feed em "Aguardando eventos…" para sempre sem dizer por quê.
    const selectedExecution = executions.find((e) => e.id === executionId) ?? null;
    const selectedFinished = !freeChannel && isTerminalExecutionStatus(selectedExecution?.status);

    const loadExecutions = async () => {
        setLoadingExecutions(true);
        try {
            setExecutions(await executionApi.list({ limit: 50 }));
        } catch {
            // Sem a lista não há o que escolher: dizer por quê, em vez de um seletor vazio mudo.
            setError(t('admin.observability.live.executionsFailed'));
        } finally {
            setLoadingExecutions(false);
        }
    };

    // Só na montagem; depois, pelo botão de atualizar.
    useEffect(() => {
        void loadExecutions();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const executionOptions = useMemo(
        () => executions.map((e) => ({
            value: e.id,
            label: `${e.workflowName || e.workflowId} · ${e.id} · ${e.status}`,
        })),
        [executions],
    );

    useEffect(() => {
        if (!attached) return;
        const stream = new ArchflowEventStream({
            tenantId: attached.tenantId,
            sessionId: attached.sessionId,
            autoReconnect: true,
        });
        streamRef.current = stream;
        const offStatus = stream.onStatus(setStatus);
        const offEvent = stream.onEvent((evt) => {
            if (paused) return;
            setEvents((prev) => {
                const next = [...prev, evt];
                return next.length > 500 ? next.slice(next.length - 500) : next;
            });
        });
        try {
            stream.open();
        } catch (e) {
            setError(e instanceof Error ? e.message : t('admin.observability.live.openFailed'));
        }
        return () => {
            offStatus();
            offEvent();
            stream.close();
            streamRef.current = null;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [attached]);

    const handleAttach = () => {
        if (!canAttach) return;
        setError(null);
        setEvents([]);
        setExpandedId(null);
        setAttached(freeChannel
            ? { tenantId: tenantId.trim() || undefined, sessionId: sessionId.trim() }
            : { sessionId: executionId! });
    };

    const handleDetach = () => {
        streamRef.current?.close();
        setAttached(null);
        setStatus('closed');
    };

    const domainCounts = useMemo(() => {
        const counts: Record<string, number> = {};
        for (const e of events) {
            counts[e.envelope.domain] = (counts[e.envelope.domain] ?? 0) + 1;
        }
        return counts;
    }, [events]);

    const rows = useMemo(
        () => events.map((e) => ({
            id: e.envelope.id,
            timestamp: e.envelope.timestamp,
            domain: e.envelope.domain,
            type: e.envelope.type,
            payload: JSON.stringify(e.data),
        })),
        [events],
    );

    const columns = useMemo((): VxColumn[] => [
        colunaApagada({ field: 'timestamp', title: t('admin.observability.live.cols.time'), width: 110,
          fieldFormat: (r: Registro) => formatTime(r.timestamp as string, locale) }),
        colunaStatus({ field: 'domain', title: t('admin.observability.live.cols.domain'), width: 150 }, () => 'teal'),
        { field: 'type', title: t('admin.observability.live.cols.type'), width: 200, style: { fontWeight: 600 } },
        colunaApagada({ field: 'payload', title: t('admin.observability.live.cols.payload'), width: 'auto', minWidth: 220 }),
    ], [t, locale]);

    const selectedEvent = events.find((e) => e.envelope.id === expandedId) ?? null;

    return (
        <>
            <SectionCard
                icon={IconPlugConnected}
                accent="green"
                title={t('admin.observability.live.connection')}
                description={freeChannel
                    ? t('admin.observability.live.channelHint')
                    : t('admin.observability.live.connectionHint')}
                actions={
                    <Badge size="lg" variant="dot" color={statusColor(status)} data-testid="live-status">
                        {status}
                    </Badge>
                }
            >
                <Group gap="sm" wrap="wrap" align="flex-end">
                    {freeChannel ? (
                        <>
                            <TextInput
                                size="sm"
                                label={t('admin.observability.live.tenantId')}
                                value={tenantId}
                                onChange={(e) => setTenantId(e.currentTarget.value)}
                                w={220}
                                placeholder={t('admin.observability.live.tenantPlaceholder')}
                                disabled={!!attached}
                                data-testid="live-tenant"
                            />
                            <TextInput
                                size="sm"
                                label={t('admin.observability.live.sessionId')}
                                value={sessionId}
                                onChange={(e) => setSessionId(e.currentTarget.value)}
                                w={280}
                                placeholder={t('admin.observability.live.sessionPlaceholder')}
                                disabled={!!attached}
                                data-testid="live-session"
                            />
                        </>
                    ) : (
                        <>
                            <Select
                                size="sm"
                                label={t('admin.observability.live.execution')}
                                data={executionOptions}
                                value={executionId}
                                onChange={setExecutionId}
                                searchable
                                w={420}
                                placeholder={t('admin.observability.live.executionPlaceholder')}
                                nothingFoundMessage={t('admin.observability.live.executionsEmpty')}
                                disabled={!!attached}
                                data-testid="live-execution"
                            />
                            <Tooltip label={t('admin.observability.live.refreshExecutions')}>
                                <ActionIcon
                                    variant="default"
                                    size="lg"
                                    onClick={() => void loadExecutions()}
                                    loading={loadingExecutions}
                                    disabled={!!attached}
                                    aria-label={t('admin.observability.live.refreshExecutions')}
                                    data-testid="live-refresh"
                                >
                                    <IconRefresh size={16} />
                                </ActionIcon>
                            </Tooltip>
                        </>
                    )}
                    <Switch
                        label={t('admin.observability.live.freeChannel')}
                        checked={freeChannel}
                        onChange={(e) => setFreeChannel(e.currentTarget.checked)}
                        disabled={!!attached}
                        mb={8}
                        data-testid="live-free-channel"
                    />
                    <Group gap="xs" ml="auto">
                        {attached ? (
                            <>
                                <Button
                                    variant="default"
                                    leftSection={paused ? <IconPlayerPlay size={14} /> : <IconPlayerPause size={14} />}
                                    onClick={() => setPaused((p) => !p)}
                                    data-testid="live-toggle"
                                >
                                    {paused ? t('admin.observability.live.resume') : t('admin.observability.live.pause')}
                                </Button>
                                <Button
                                    variant="default"
                                    leftSection={<IconTrash size={14} />}
                                    onClick={() => {
                                        setEvents([]);
                                        setExpandedId(null);
                                    }}
                                >
                                    {t('admin.observability.live.clear')}
                                </Button>
                                <Button
                                    color="red"
                                    variant="light"
                                    leftSection={<IconPlugConnectedX size={14} />}
                                    onClick={handleDetach}
                                >
                                    {t('admin.observability.live.detach')}
                                </Button>
                            </>
                        ) : (
                            <Button
                                color="green"
                                leftSection={<IconPlugConnected size={14} />}
                                disabled={!canAttach}
                                onClick={handleAttach}
                                data-testid="live-attach"
                            >
                                {t('admin.observability.live.attach')}
                            </Button>
                        )}
                    </Group>
                </Group>
            </SectionCard>

            {error && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}
            {selectedFinished && (
                <Alert color="yellow" variant="light" icon={<IconAlertCircle size={16} />} data-testid="live-finished">
                    {t('admin.observability.live.finished')}
                </Alert>
            )}
            {attached && status === 'error' && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />} data-testid="live-rejected">
                    {t('admin.observability.live.rejected')}
                </Alert>
            )}

            <Grid gap="md">
                <Grid.Col span={{ base: 12, lg: 8 }}>
                    <SectionCard
                        flush
                        h="100%"
                        icon={IconRadar2}
                        accent="green"
                        title={t('admin.observability.live.eventFeed')}
                        description={t('admin.observability.live.eventFeedHint')}
                        actions={
                            Object.keys(domainCounts).length > 0 && (
                                <Group gap={6}>
                                    {Object.entries(domainCounts).map(([domain, count]) => (
                                        <Badge key={domain} size="sm" variant="light" color="teal">
                                            {domain}: {count}
                                        </Badge>
                                    ))}
                                </Group>
                            )
                        }
                    >
                        <Box
                            role="log"
                            aria-live="polite"
                            aria-relevant="additions"
                            aria-label={t('admin.observability.live.eventFeed')}
                        >
                            <VxTable
                                legenda={t('admin.observability.live.eventFeed')}
                                records={rows as unknown as Registro[]}
                                columns={columns}
                                maxHeight={440}
                                onRowClick={(r) => setExpandedId((cur) => (cur === r.id ? null : (r.id as string)))}
                                emptyMessage={attached
                                    ? t('admin.observability.live.waiting')
                                    : t('admin.observability.live.attachHint')}
                                emptyIcon={<IconRadar2 />}
                                emptyAccent="green"
                                testid="live-events"
                            />
                        </Box>
                    </SectionCard>
                </Grid.Col>
                <Grid.Col span={{ base: 12, lg: 4 }}>
                    <SectionCard
                        h="100%"
                        icon={IconBraces}
                        accent="teal"
                        title={t('admin.observability.live.payload')}
                        description={selectedEvent
                            ? selectedEvent.envelope.type
                            : t('admin.observability.live.payloadHint')}
                    >
                        {selectedEvent ? (
                            <Box style={{ maxHeight: 400, overflow: 'auto' }}>
                                <JsonViewer value={selectedEvent.data} autoExpandDepth={2} />
                            </Box>
                        ) : (
                            <EmptyState
                                compact
                                accent="teal"
                                icon={<IconBraces />}
                                title={t('admin.observability.live.noSelection')}
                            />
                        )}
                    </SectionCard>
                </Grid.Col>
            </Grid>
        </>
    );
}

function statusColor(status: StreamStatus): string {
    switch (status) {
        case 'open':
            return 'teal';
        case 'connecting':
        case 'reconnecting':
            return 'yellow';
        case 'error':
            return 'red';
        default:
            return 'gray';
    }
}

function formatTime(iso: string, locale: string): string {
    try {
        return new Date(iso).toLocaleTimeString(locale);
    } catch {
        return iso;
    }
}
