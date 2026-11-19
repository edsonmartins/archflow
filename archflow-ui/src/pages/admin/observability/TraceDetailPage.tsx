import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    Alert,
    Badge,
    Box,
    Button,
    Group,
    SimpleGrid,
    Skeleton,
    Stack,
    Text,
    UnstyledButton,
} from '@mantine/core';
import {
    IconAlertCircle,
    IconAlertTriangle,
    IconClock,
    IconHierarchy2,
    IconInfoCircle,
    IconList,
    IconStack2,
    IconTimeline,
} from '@tabler/icons-react';
import { Meta } from '../../../components/Meta';
import { PageHeader } from '../../../components/PageHeader';
import { SectionCard } from '../../../components/SectionCard';
import { StatCard } from '../../../components/admin/StatCard';
import EmptyState from '../../../components/EmptyState';
import { VxTable, colunaApagada, type Registro, type VxColumn } from '../../../components/VTable';
import { formatInstant } from '../../../lib/format';
import { observabilityApi, type SpanDto, type TraceDetailDto } from '../../../services/observability-api';

/** Width of the name column of the waterfall; the bar track takes the rest. */
const NAME_COL = 'minmax(220px, 34%)';
const DURATION_COL = '84px';
const TICKS = [0, 0.25, 0.5, 0.75, 1];

/**
 * Orders spans as a tree (parent before children, siblings by start time) and
 * records each one's depth, so the waterfall reads top-down like a call stack.
 * A span whose parent is not in the trace is treated as a root.
 */
function toTree(spans: SpanDto[]): { span: SpanDto; depth: number }[] {
    const ids = new Set(spans.map((s) => s.spanId));
    const children = new Map<string | null, SpanDto[]>();
    for (const s of spans) {
        const parent = s.parentSpanId && ids.has(s.parentSpanId) ? s.parentSpanId : null;
        const list = children.get(parent) ?? [];
        list.push(s);
        children.set(parent, list);
    }
    const byStart = (a: SpanDto, b: SpanDto) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime();
    const out: { span: SpanDto; depth: number }[] = [];
    const seen = new Set<string>();
    const walk = (parent: string | null, depth: number) => {
        for (const s of (children.get(parent) ?? []).sort(byStart)) {
            if (seen.has(s.spanId)) continue;
            seen.add(s.spanId);
            out.push({ span: s, depth });
            walk(s.spanId, depth + 1);
        }
    };
    walk(null, 0);
    return out;
}

export default function TraceDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const [trace, setTrace] = useState<TraceDetailDto | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [selected, setSelected] = useState<string | null>(null);

    useEffect(() => {
        if (!id) return;
        let cancelled = false;
        setLoading(true);
        observabilityApi
            .getTrace(id)
            .then((res) => {
                if (!cancelled) setTrace(res);
            })
            .catch((e) => {
                if (!cancelled) setError(e instanceof Error ? e.message : t('admin.observability.traceDetail.loadFailed'));
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [id]);

    const tree = useMemo(() => (trace ? toTree(trace.spans) : []), [trace]);

    const attributeRows = useMemo(() => {
        if (!trace) return [];
        return trace.spans
            .filter((s) => !selected || s.spanId === selected)
            .flatMap((s) => Object.entries(s.attributes).map(([key, value]) => ({
                id: `${s.spanId}-${key}`, span: s.name, key, value: String(value),
            })));
    }, [trace, selected]);

    const attributeColumns = useMemo((): VxColumn[] => [
        colunaApagada({ field: 'span', title: t('admin.observability.traceDetail.attrCols.span'), width: 200 }),
        { field: 'key', title: t('admin.observability.traceDetail.attrCols.attribute'), width: 220,
          style: { fontWeight: 600 } },
        { field: 'value', title: t('admin.observability.traceDetail.attrCols.value'), width: 'auto', minWidth: 240 },
    ], [t]);

    const crumbs = [
        { label: t('admin.observability.title'), to: '/admin/observability' },
        { label: t('admin.observability.tabs.traces'), to: '/admin/observability/traces' },
    ];

    if (loading) {
        return (
            <Stack gap="lg" aria-busy="true">
                <Skeleton height={64} radius="md" />
                <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                    {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} height={120} radius="md" />)}
                </SimpleGrid>
                <Skeleton height={260} radius="md" />
            </Stack>
        );
    }

    if (error || !trace) {
        return (
            <Stack gap="lg">
                <PageHeader
                    icon={IconTimeline}
                    accent="rose"
                    title={t('admin.observability.traceDetail.notFoundTitle')}
                    breadcrumbs={crumbs}
                    backTo="/admin/observability/traces"
                    backLabel={t('admin.observability.traceDetail.back')}
                />
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                    {error ?? t('admin.observability.traceDetail.notFound')}
                </Alert>
            </Stack>
        );
    }

    const failed = trace.status !== 'OK';
    const totalMs = trace.durationMs || 1;
    const traceStart = new Date(trace.startedAt).getTime();
    const spanErrors = trace.spans.filter((s) => s.status !== 'OK').length;
    const maxDepth = tree.reduce((m, r) => Math.max(m, r.depth), 0);
    const selectedSpan = trace.spans.find((s) => s.spanId === selected) ?? null;

    return (
        <Stack gap="lg">
            <PageHeader
                icon={IconTimeline}
                accent={failed ? 'rose' : 'green'}
                title={t('admin.observability.traceDetail.title', { id: trace.traceId.slice(0, 12) })}
                subtitle={<Text span size="sm" c="dimmed" ff="monospace">{trace.traceId}</Text>}
                breadcrumbs={[...crumbs, { label: trace.traceId.slice(0, 12) }]}
                backTo="/admin/observability/traces"
                backLabel={t('admin.observability.traceDetail.back')}
                actions={
                    <>
                        {trace.personaId && (
                            <Badge size="lg" variant="light" color="violet">
                                {trace.personaId}
                            </Badge>
                        )}
                        <Badge size="lg" variant="light" color={failed ? 'red' : 'green'}>
                            {trace.status}
                        </Badge>
                    </>
                }
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('admin.observability.traceDetail.meta.duration')}
                    value={`${trace.durationMs} ms`}
                    icon={IconClock}
                    accent="green"
                    hint={formatInstant(trace.startedAt, locale)}
                />
                <StatCard
                    label={t('admin.observability.traceDetail.meta.spans')}
                    value={trace.spans.length}
                    icon={IconStack2}
                    accent="teal"
                    hint={t('admin.observability.traceDetail.depthHint', { count: maxDepth + 1 })}
                />
                <StatCard
                    label={t('admin.observability.traceDetail.meta.errors')}
                    value={spanErrors}
                    color={spanErrors > 0 ? 'red' : undefined}
                    icon={IconAlertTriangle}
                    accent="rose"
                    hint={spanErrors > 0
                        ? t('admin.observability.traceDetail.errorsHint')
                        : t('admin.observability.traceDetail.errorsNone')}
                />
                <StatCard
                    label={t('admin.observability.traceDetail.meta.flow')}
                    value={<Text span fz={20} fw={700} truncate="end" display="block">{trace.flowId ?? '—'}</Text>}
                    icon={IconHierarchy2}
                    accent="blue"
                    hint={trace.executionId ?? '—'}
                />
            </SimpleGrid>

            <SectionCard
                icon={IconInfoCircle}
                accent="green"
                title={t('admin.observability.traceDetail.properties')}
            >
                <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="lg">
                    <Meta label={t('admin.observability.traceDetail.meta.tenant')} value={trace.tenantId ?? '—'} />
                    <Meta label={t('admin.observability.traceDetail.meta.flow')} value={trace.flowId ?? '—'} />
                    <Meta label={t('admin.observability.traceDetail.meta.execution')} value={trace.executionId ?? '—'} />
                    <Meta label={t('admin.observability.traceDetail.meta.persona')} value={trace.personaId ?? '—'} />
                </SimpleGrid>
                {trace.error && (
                    <Alert mt="md" color="red" icon={<IconAlertCircle size={14} />} variant="light">
                        {trace.error}
                    </Alert>
                )}
            </SectionCard>

            <SectionCard
                flush
                icon={IconTimeline}
                accent="green"
                title={t('admin.observability.traceDetail.timeline')}
                description={t('admin.observability.traceDetail.timelineHint')}
            >
                {tree.length === 0 ? (
                    <EmptyState
                        compact
                        accent="green"
                        icon={<IconTimeline />}
                        title={t('admin.observability.traceDetail.noSpans')}
                    />
                ) : (
                    <Box role="list" aria-label={t('admin.observability.traceDetail.timeline')}>
                        {/* Time axis */}
                        <Box
                            aria-hidden
                            px="lg"
                            py={8}
                            style={{
                                display: 'grid',
                                gridTemplateColumns: `${NAME_COL} 1fr ${DURATION_COL}`,
                                gap: 16,
                                background: 'var(--table-head-bg)',
                                borderBottom: '1px solid var(--surface-border)',
                            }}
                        >
                            <Text size="xs" fw={600} c="dimmed" tt="uppercase" style={{ letterSpacing: '0.06em' }}>
                                {t('admin.observability.traceDetail.attrCols.span')}
                            </Text>
                            <Box style={{ position: 'relative', height: 16 }}>
                                {TICKS.map((f) => (
                                    <Text
                                        key={f}
                                        size="xs"
                                        c="dimmed"
                                        style={{
                                            position: 'absolute',
                                            left: `${f * 100}%`,
                                            transform: f === 0 ? 'none' : f === 1 ? 'translateX(-100%)' : 'translateX(-50%)',
                                            fontVariantNumeric: 'tabular-nums',
                                            whiteSpace: 'nowrap',
                                        }}
                                    >
                                        {Math.round(totalMs * f)} ms
                                    </Text>
                                ))}
                            </Box>
                            <Text size="xs" fw={600} c="dimmed" tt="uppercase" ta="right" style={{ letterSpacing: '0.06em' }}>
                                {t('admin.observability.traceDetail.meta.duration')}
                            </Text>
                        </Box>

                        {tree.map(({ span, depth }) => {
                            const spanStart = new Date(span.startedAt).getTime();
                            const offsetPct = Math.max(0, Math.min(100, ((spanStart - traceStart) / totalMs) * 100));
                            const widthPct = Math.max(0.6, Math.min(100 - offsetPct, (span.durationMs / totalMs) * 100));
                            const bad = span.status !== 'OK';
                            const isSelected = selected === span.spanId;
                            return (
                                <Box key={span.spanId} role="listitem">
                                    <UnstyledButton
                                        aria-pressed={isSelected}
                                        onClick={() => setSelected((cur) => (cur === span.spanId ? null : span.spanId))}
                                        px="lg"
                                        py={9}
                                        w="100%"
                                        style={{
                                            display: 'grid',
                                            gridTemplateColumns: `${NAME_COL} 1fr ${DURATION_COL}`,
                                            gap: 16,
                                            alignItems: 'center',
                                            borderBottom: '1px solid var(--surface-border)',
                                            background: isSelected ? 'var(--mantine-color-green-light)' : undefined,
                                        }}
                                    >
                                        <Group gap={8} wrap="nowrap" style={{ minWidth: 0, paddingLeft: depth * 18 }}>
                                            {depth > 0 && (
                                                <Text span size="xs" c="dimmed" aria-hidden style={{ flexShrink: 0 }}>└</Text>
                                            )}
                                            <span
                                                aria-hidden
                                                style={{
                                                    width: 8,
                                                    height: 8,
                                                    borderRadius: 999,
                                                    flexShrink: 0,
                                                    background: bad ? 'var(--mantine-color-red-6)' : 'var(--mantine-color-green-6)',
                                                }}
                                            />
                                            <Text size="sm" fw={depth === 0 ? 600 : 500} truncate="end" style={{ minWidth: 0 }}>
                                                {span.name}
                                            </Text>
                                            <Badge size="xs" variant="default" style={{ flexShrink: 0 }}>
                                                {span.kind}
                                            </Badge>
                                            {bad && (
                                                <Badge size="xs" variant="light" color="red" style={{ flexShrink: 0 }}>
                                                    {span.status}
                                                </Badge>
                                            )}
                                        </Group>
                                        <Box
                                            style={{
                                                position: 'relative',
                                                height: 14,
                                                borderRadius: 999,
                                                background: 'var(--mantine-color-default-hover)',
                                                backgroundImage:
                                                    'linear-gradient(90deg, transparent calc(25% - 1px), var(--surface-border) 25%, transparent calc(25% + 1px), transparent calc(50% - 1px), var(--surface-border) 50%, transparent calc(50% + 1px), transparent calc(75% - 1px), var(--surface-border) 75%, transparent calc(75% + 1px))',
                                            }}
                                        >
                                            <Box
                                                title={`${span.name} — ${span.durationMs} ms`}
                                                style={{
                                                    position: 'absolute',
                                                    left: `${offsetPct}%`,
                                                    width: `${widthPct}%`,
                                                    top: 0,
                                                    bottom: 0,
                                                    minWidth: 4,
                                                    borderRadius: 999,
                                                    background: bad
                                                        ? 'linear-gradient(90deg, var(--mantine-color-red-4), var(--mantine-color-red-6))'
                                                        : 'linear-gradient(90deg, var(--mantine-color-green-4), var(--mantine-color-green-6))',
                                                }}
                                            />
                                        </Box>
                                        <Text size="xs" fw={600} ta="right" style={{ fontVariantNumeric: 'tabular-nums' }}>
                                            {span.durationMs} ms
                                        </Text>
                                    </UnstyledButton>
                                </Box>
                            );
                        })}
                    </Box>
                )}
            </SectionCard>

            <SectionCard
                flush
                icon={IconList}
                accent="green"
                title={t('admin.observability.traceDetail.attributes')}
                description={selectedSpan
                    ? t('admin.observability.traceDetail.attributesOf', { name: selectedSpan.name })
                    : t('admin.observability.traceDetail.attributesHint')}
                actions={selectedSpan && (
                    <Button size="xs" variant="subtle" color="gray" onClick={() => setSelected(null)}>
                        {t('admin.observability.traceDetail.showAll')}
                    </Button>
                )}
            >
                <VxTable
                    legenda={t('admin.observability.traceDetail.attributes')}
                    records={attributeRows as unknown as Registro[]}
                    columns={attributeColumns}
                    maxHeight={360}
                    emptyMessage={t('admin.observability.traceDetail.noAttributes')}
                    emptyIcon={<IconList />}
                    emptyAccent="green"
                    testid="trace-attributes"
                />
            </SectionCard>
        </Stack>
    );
}
