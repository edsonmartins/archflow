import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import {
    Alert, Badge, Button, Code, Group, Loader, Modal, Select, SimpleGrid, Stack, Switch, Text,
    Textarea, TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconAlertTriangle, IconClockPause, IconClockPlay, IconInfoCircle, IconPlayerPlay, IconPlus, IconRefresh,
} from '@tabler/icons-react'
import { triggersApi, type ScheduledTrigger } from '../services/triggers-api'
import {
    agentPlaygroundApi, InvocationNotFoundError, isTerminal, type InvocationResult,
} from '../services/agent-playground-api'
import { useCatalog } from '../components/PropertyPanel/useCatalog'
import { JsonViewer } from '../components/JsonViewer'
import { ApiError } from '../services/api'
import { confirmAction } from '../lib/confirm'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import { VxTable, colunaAcoes, colunaApagada, colunaDupla, colunaStatus, type VxColumn } from '../components/VTable'
import { formatInstant } from '../lib/format'
import { useActingTenant } from '../lib/actingTenant'
import { approvalTenantParam } from '../lib/approvalTenant'
import { useTenantStore } from '../stores/useTenantStore'
import { usePermissions } from '../lib/permissions'
import { PermissionTooltip } from '../components/PermissionTooltip'

/** Acompanhamento do "Disparar agora": os mesmos intervalo, prazo e 404s tolerados do Agent lab. */
const RUN_POLL_INTERVAL_MS = 1000
const RUN_POLL_DEADLINE_MS = 120_000
const RUN_MAX_NOT_FOUND = 3

/**
 * Acompanha a invocação que o "Disparar agora" enfileirou e avisa como o agente terminou (RCHFLW-80).
 * O disparo só enfileira: antes o gatilho dizia "disparado" e o desfecho do agente (COMPLETED/FAILED)
 * não chegava a lugar nenhum — o lastError do gatilho é a falha do disparo, não a do agente.
 *
 * @returns o resultado final, ou null quando não deu para saber (prazo, consulta falhou, 404)
 */
export async function followTriggerRun(
    trigger: Pick<ScheduledTrigger, 'name' | 'tenantId'>, requestId: string, t: TFunction,
    opts: { intervalMs?: number; deadlineMs?: number } = {},
): Promise<InvocationResult | null> {
    const interval = opts.intervalMs ?? RUN_POLL_INTERVAL_MS
    const deadline = Date.now() + (opts.deadlineMs ?? RUN_POLL_DEADLINE_MS)
    const unknown = (key: 'stillRunning' | 'queryFailed') => {
        notifications.show({ color: 'yellow', title: t('triggers.run.unknown', { name: trigger.name }),
            message: t(`triggers.run.${key}`, { id: requestId }) })
        return null
    }
    let notFound = 0
    for (;;) {
        try {
            const r = await agentPlaygroundApi.invocation(requestId, trigger.tenantId)
            notFound = 0
            if (isTerminal(r.status)) {
                if (r.status === 'COMPLETED') {
                    notifications.show({ color: 'green', title: t('triggers.run.completed'),
                        message: t('triggers.run.completedMsg', { name: trigger.name }) })
                } else {
                    notifications.show({ color: 'red', title: t('triggers.run.failed', { name: trigger.name }),
                        message: r.error || t('triggers.run.noErrorMessage') })
                }
                return r
            }
        } catch (e) {
            // Logo depois do disparo, a fila em memória pode ainda não ter registrado a invocação: alguns
            // 404 seguidos são tolerados; outro erro (ou 404 persistente) é "não deu para saber".
            if (!(e instanceof InvocationNotFoundError) || ++notFound >= RUN_MAX_NOT_FOUND) {
                return unknown('queryFailed')
            }
        }
        if (Date.now() >= deadline) return unknown('stillRunning')
        await new Promise(resolve => setTimeout(resolve, interval))
    }
}

/**
 * O gatilho está mesmo agendado: ativo E com próximo disparo no Quartz (RCHFLW-140). Um ativo sem agendamento
 * — o job sumiu, ou o cron já não tem disparo — não pode aparecer como "ativo" nem contar como tal.
 */
export function isScheduled(trigger: Pick<ScheduledTrigger, 'enabled' | 'nextFireTime'>): boolean {
    return trigger.enabled && !!trigger.nextFireTime
}

/** O texto da coluna "Status": ativo (agendado), sem agendamento ou pausado. */
export function statusLabel(trigger: Pick<ScheduledTrigger, 'enabled' | 'nextFireTime'>, t: TFunction): string {
    if (!trigger.enabled) return t('triggers.status.paused')
    return isScheduled(trigger) ? t('triggers.status.enabled') : t('triggers.status.unscheduled')
}

/** Códigos de recusa do servidor que a tela traduz (RCHFLW-191); o resto mostra a mensagem da API. */
const SAVE_ERROR_CODES = new Set([
    'TRIGGER_NAME_REQUIRED', 'TRIGGER_AGENT_UNKNOWN', 'TRIGGER_CRON_INVALID', 'TRIGGER_CRON_NEVER_FIRES',
    'TRIGGER_BODY_INVALID', 'TRIGGER_CONFLICT',
])

/**
 * A mensagem de um Salvar recusado. O servidor fala inglês e o Quartz mais ainda ("Based on configured
 * schedule, the given trigger ... will never fire."); o código da recusa vira um texto que explica o que
 * corrigir — o formato Quartz, no caso do cron.
 */
export function saveErrorMessage(e: unknown, t: TFunction, cron: string): string {
    if (e instanceof ApiError) {
        const code = e.details?.code
        if (typeof code === 'string' && SAVE_ERROR_CODES.has(code)) {
            return t(`triggers.errors.${code}`, { cron })
        }
        return e.message
    }
    return String(e)
}

/** O texto da coluna "Último resultado": o status do último disparo, ou "—" se nunca disparou. */
export function lastRunLabel(trigger: Pick<ScheduledTrigger, 'lastStatus'>, t: TFunction): string {
    return trigger.lastStatus ? t(`triggers.lastStatus.${trigger.lastStatus}`) : '—'
}

/**
 * Cron-scheduled trigger admin surface.
 *
 * <p>Lets operators configure recurring agent invocations (daily
 * digests, hourly health checks, etc.) without writing YAML or
 * touching Quartz directly. The cron expression is validated
 * server-side before the trigger is materialised.</p>
 */
export default function ScheduledTriggersPage() {
    const { t, i18n } = useTranslation()
    const locale = i18n.resolvedLanguage ?? i18n.language
    // O que o servidor exige (QA2 RCHFLW-158): criar, editar e apagar o agendamento pedem workflow:update;
    // disparar agora é executar o fluxo, workflow:execute.
    const can = usePermissions()
    const canUpdate = can('workflow:update')
    const canFire = can('workflow:execute')
    const [items, setItems]   = useState<ScheduledTrigger[]>([])
    const [loading, setL]     = useState(true)
    const [error, setError]   = useState<string | null>(null)
    const [editing, setEdit]  = useState<ScheduledTrigger | null>(null)
    const [draft, setDraft]   = useState<NewDraft>(blankDraft)
    // O Salvar em andamento (RCHFLW-175): o segundo clique, ou o Enter repetido, mandava outro POST.
    const [saving, setSaving] = useState(false)
    const savingRef = useRef(false)
    // Os gatilhos com "Disparar agora" em andamento (RCHFLW-176): o clique duplo disparava o agente duas vezes.
    // O ref decide na hora do clique (o estado só chega no próximo render); o estado troca o ícone da linha.
    const firingRef = useRef(new Set<string>())
    const [firing, setFiring] = useState<ReadonlySet<string>>(new Set())
    // A última execução de um gatilho, aberta pela ação "Ver resposta" (RCHFLW-191).
    const [viewing, setViewing] = useState<ScheduledTrigger | null>(null)

    const catalog = useCatalog()
    // RCHFLW-103: o gatilho roda no tenant da identidade. Quem não é admin vê o próprio tenant e não o
    // troca (o servidor recusaria com 403); a listagem segue o recorte da fila de aprovações.
    const acting = useActingTenant()
    const impersonating = useTenantStore(s => s.impersonating)
    const currentRole = useTenantStore(s => s.currentRole)
    const listTenant = approvalTenantParam(impersonating, currentRole)
    const draftTenant = acting.locked ? (acting.tenantId ?? '') : draft.tenantId

    const agentOptions = [
        ...catalog.agents.map(a => ({ value: a.id, label: `${a.displayName} (${t('triggers.agentLabel')})` })),
        ...catalog.assistants.map(a => ({ value: a.id, label: `${a.displayName} (${t('triggers.assistantLabel')})` })),
    ]

    const reload = async () => {
        setL(true); setError(null)
        try {
            setItems(await triggersApi.list(listTenant))
        } catch (e) {
            setError(e instanceof ApiError ? e.message : String(e))
        } finally { setL(false) }
    }
    useEffect(() => { reload() }, [listTenant])

    const openCreate = () => {
        setDraft({ ...blankDraft, tenantId: acting.tenantId ?? '' })
        setEdit({} as ScheduledTrigger)
    }
    const openEdit = (trig: ScheduledTrigger) => {
        setDraft({
            name: trig.name,
            cronExpression: trig.cronExpression,
            tenantId: trig.tenantId,
            agentId: trig.agentId,
            payloadJson: JSON.stringify(trig.payload ?? {}, null, 2),
            enabled: trig.enabled,
        })
        setEdit(trig)
    }

    const save = async () => {
        if (savingRef.current) return
        let parsedPayload: Record<string, unknown>
        try {
            const parsed: unknown = draft.payloadJson.trim() ? JSON.parse(draft.payloadJson) : {}
            // Um array ou uma string são JSON válido, mas não um payload: o servidor recusava com um
            // "Bad Request" mudo (RCHFLW-191).
            if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object')
            parsedPayload = parsed as Record<string, unknown>
        } catch {
            notifications.show({ color: 'red', title: t('triggers.invalidJson'),
                message: t('triggers.invalidJsonMsg') })
            return
        }
        const body = {
            name: draft.name,
            cronExpression: draft.cronExpression,
            tenantId: draftTenant,
            agentId: draft.agentId,
            payload: parsedPayload,
            enabled: draft.enabled,
        }
        savingRef.current = true
        setSaving(true)
        try {
            if (editing && editing.id) {
                // A versão que o formulário abriu: se outra pessoa salvou no meio, 409 em vez de desfazer em
                // silêncio a edição dela (RCHFLW-191).
                await triggersApi.update(editing.id, body, editing.updatedAt)
            } else {
                await triggersApi.create(body)
            }
            setEdit(null)
            await reload()
        } catch (e) {
            notifications.show({ color: 'red', title: t('triggers.saveFailed'),
                message: saveErrorMessage(e, t, draft.cronExpression) })
            // No conflito a lista é relida: quem fechar e abrir de novo edita a versão atual.
            if (e instanceof ApiError && e.details?.code === 'TRIGGER_CONFLICT') void reload()
        } finally {
            savingRef.current = false
            setSaving(false)
        }
    }

    const remove = (trigger: ScheduledTrigger) => {
        confirmAction({
            title: t('confirmations.deleteTriggerTitle'),
            message: t('confirmations.deleteTriggerMessage', { name: trigger.name }),
            confirmLabel: t('confirmations.delete'),
            onConfirm: async () => {
                try {
                    await triggersApi.remove(trigger.id)
                    await reload()
                } catch (e) {
                    notifications.show({ color: 'red', title: t('triggers.deleteFailed'),
                        message: e instanceof ApiError ? e.message : String(e) })
                }
            },
        })
    }

    const fire = async (trigger: ScheduledTrigger) => {
        if (firingRef.current.has(trigger.id)) return
        firingRef.current.add(trigger.id)
        setFiring(new Set(firingRef.current))
        try {
            const fired = await triggersApi.fireNow(trigger.id)
            notifications.show({ color: 'green', title: t('triggers.fired'),
                message: t('triggers.firedMsg', { name: trigger.name }) })
            await reload()
            // Terminado o agente, a lista é relida: o gatilho já guarda o desfecho (RCHFLW-104), e o card
            // "Com falha" e a linha passam a refleti-lo sem esperar alguém clicar em Atualizar.
            if (fired.lastRequestId) {
                void followTriggerRun(fired, fired.lastRequestId, t).then(r => { if (r) void reload() })
            }
        } catch (e) {
            notifications.show({ color: 'red', title: t('triggers.fireFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
            // O servidor grava a falha no gatilho (status "com falha", sem requestId). Sem reler, a linha
            // seguia com o desfecho e o "Ver resposta" do disparo anterior, como se ele fosse o último.
            await reload()
        } finally {
            firingRef.current.delete(trigger.id)
            setFiring(new Set(firingRef.current))
        }
    }

    const columns = useMemo((): VxColumn[] => [
        colunaDupla(
            { field: 'name', title: t('triggers.table.name'), width: 'auto', minWidth: 220 },
            (r) => String(r.name ?? ''),
            (r) => String(r.id ?? ''),
        ),
        { field: 'cronExpression', title: t('triggers.table.cron'), width: 140,
          style: { fontWeight: 600 } },
        colunaApagada({ field: 'tenantId', title: t('triggers.table.tenant'), width: 130 }),
        { field: 'agentId', title: t('triggers.table.agent'), width: 190 },
        colunaStatus(
            { field: 'enabled', title: t('triggers.table.status'), width: 150,
              fieldFormat: (r) => statusLabel(r as unknown as ScheduledTrigger, t) },
            (r) => (!r.enabled ? 'gray' : isScheduled(r as unknown as ScheduledTrigger) ? 'green' : 'red'),
        ),
        // O próximo disparo, no horário de quem olha (RCHFLW-191): antes só havia o último, e o cron cru.
        colunaApagada({ field: 'nextFireTime', title: t('triggers.table.nextFire'), width: 190,
          fieldFormat: (r) => formatInstant(r.nextFireTime as string | null, locale) }),
        colunaApagada({ field: 'lastFiredAt', title: t('triggers.table.lastFired'), width: 190,
          fieldFormat: (r) => formatInstant(r.lastFiredAt as string | null, locale) }),
        // O desfecho do último disparo, inclusive o automático (RCHFLW-104): antes a falha do agente só
        // aparecia no aviso do "Disparar agora", e a do cron em lugar nenhum.
        colunaDupla(
            { field: 'lastStatus', title: t('triggers.table.lastResult'), width: 240 },
            (r) => lastRunLabel(r as unknown as ScheduledTrigger, t),
            (r) => (r.lastError ? String(r.lastError) : undefined),
        ),
        colunaAcoes([
            { nome: 'fire', icone: 'play', rotulo: t('triggers.fireNow'), cor: 'violet',
              mostrar: (r) => canFire && !firing.has(String(r.id)) },
            // Enquanto o disparo está em andamento, o play vira um indicador sem ação.
            { nome: 'firing', icone: 'dots', rotulo: t('triggers.firing'), mostrar: (r) => firing.has(String(r.id)) },
            { nome: 'result', icone: 'eye', rotulo: t('triggers.viewResult'), mostrar: (r) => !!r.lastRequestId },
            { nome: 'edit', icone: 'pencil', rotulo: t('triggers.edit'), mostrar: () => canUpdate },
            { nome: 'delete', icone: 'trash', rotulo: t('triggers.delete'), cor: 'red', mostrar: () => canUpdate },
        ]),
    ], [t, locale, canFire, canUpdate, firing])

    const enabledCount = items.filter(isScheduled).length
    const pausedCount = items.filter(i => !i.enabled).length
    const serverZone = items.find(i => i.timeZone)?.timeZone
    const failingCount = items.filter(i => i.lastError).length
    const firstLoad = loading && items.length === 0

    return (
        <Stack gap="lg" p="md" data-testid="triggers-page">
            <PageHeader
                icon={IconClockPlay}
                accent="slate"
                title={t('triggers.title')}
                subtitle={t('triggers.subtitle')}
                actions={
                    <>
                        <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={reload}>
                            {t('common.refresh')}
                        </Button>
                        <PermissionTooltip missing={canUpdate ? null : 'workflow:update'}>
                            <Button leftSection={<IconPlus size={16} />} onClick={openCreate} data-testid="trigger-new"
                                    disabled={!canUpdate}>
                                {t('triggers.newTrigger')}
                            </Button>
                        </PermissionTooltip>
                    </>
                }
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('triggers.stats.total')} value={firstLoad ? '—' : items.length}
                          icon={IconClockPlay} accent="blue" hint={t('triggers.stats.totalHint')} />
                <StatCard label={t('triggers.stats.enabled')} value={firstLoad ? '—' : enabledCount}
                          icon={IconPlayerPlay} accent="teal" hint={t('triggers.stats.enabledHint')} />
                <StatCard label={t('triggers.stats.paused')} value={firstLoad ? '—' : pausedCount}
                          icon={IconClockPause} accent="slate" hint={t('triggers.stats.pausedHint')} />
                <StatCard label={t('triggers.stats.failing')} value={firstLoad ? '—' : failingCount}
                          color={failingCount > 0 ? 'red' : undefined}
                          icon={IconAlertTriangle} accent="rose" hint={t('triggers.stats.failingHint')} />
            </SimpleGrid>

            <SectionCard
                flush
                icon={IconClockPlay}
                accent="slate"
                title={t('triggers.listTitle')}
                description={t('triggers.listHint')}
            >
                <VxTable
                    legenda={t('triggers.listTitle')}
                    records={items as unknown as Record<string, unknown>[]}
                    columns={columns}
                    rowHeight={58}
                    rightFrozenColCount={1}
                    loading={loading}
                    error={error}
                    onRetry={reload}
                    onRowClick={canUpdate ? (r) => openEdit(r as unknown as ScheduledTrigger) : undefined}
                    onIconClick={(nome, r) => {
                        const trig = r as unknown as ScheduledTrigger
                        if (nome === 'fire') fire(trig)
                        else if (nome === 'result') setViewing(trig)
                        else if (nome === 'edit') openEdit(trig)
                        else if (nome === 'delete') remove(trig)
                    }}
                    emptyMessage={t('triggers.empty')}
                    emptyDescription={t('triggers.emptyHint')}
                    emptyIcon={<IconClockPlay />}
                    emptyAccent="slate"
                    emptyAction={canUpdate ? (
                        <Button variant="light" size="xs" leftSection={<IconPlus size={14} />} onClick={openCreate}>
                            {t('triggers.newTrigger')}
                        </Button>
                    ) : undefined}
                    testid="triggers-table"
                    rowTestId={(r) => `trigger-${r.id}`}
                />
            </SectionCard>

            <Alert color="blue" variant="light" icon={<IconInfoCircle size={16} />}>
                {(serverZone ? t('triggers.infoZone', { zone: serverZone }) : t('triggers.info'))
                    .split(/<code>|<\/code>/).map((seg, i) =>
                    i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
            </Alert>

            <Modal opened={editing !== null} onClose={() => setEdit(null)}
                   title={editing?.id ? t('triggers.editTitle') : t('triggers.newTitle')}
                   size="lg" data-testid="trigger-modal">
                <Stack gap="sm">
                    <TextInput size="sm" label={t('triggers.name')} value={draft.name}
                               onChange={e => setDraft({ ...draft, name: e.currentTarget.value })}
                               data-testid="trigger-name" />
                    <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                        <TextInput size="sm" label={t('triggers.cron')}
                                   value={draft.cronExpression}
                                   onChange={e => setDraft({ ...draft, cronExpression: e.currentTarget.value })}
                                   placeholder="0 0 2 * * ?"
                                   description={t('triggers.cronHint')}
                                   styles={{ input: { fontFamily: 'var(--font-mono)' } }}
                                   data-testid="trigger-cron" />
                        <TextInput size="sm" label={t('triggers.tenant')}
                                   value={draftTenant}
                                   readOnly={acting.locked}
                                   onChange={e => setDraft({ ...draft, tenantId: e.currentTarget.value })}
                                   description={t(acting.locked ? 'triggers.tenantLockedHint' : 'triggers.tenantHint')}
                                   placeholder="tenant_demo"
                                   data-testid="trigger-tenant" />
                    </SimpleGrid>
                    <Select size="sm" label={t('triggers.agent')}
                            value={draft.agentId || null}
                            onChange={v => setDraft({ ...draft, agentId: v ?? '' })}
                            data={agentOptions}
                            searchable
                            data-testid="trigger-agent" />
                    <Textarea size="sm" label={t('triggers.payload')}
                              value={draft.payloadJson}
                              onChange={e => setDraft({ ...draft, payloadJson: e.currentTarget.value })}
                              autosize minRows={3}
                              styles={{ input: { fontFamily: 'var(--font-mono)' } }}
                              data-testid="trigger-payload" />
                    <Switch label={t('triggers.enabled')}
                            checked={draft.enabled}
                            onChange={e => setDraft({ ...draft, enabled: e.currentTarget.checked })} />
                    <Group justify="flex-end" mt="xs">
                        <Button variant="default" onClick={() => setEdit(null)}>{t('common.cancel')}</Button>
                        <Button onClick={save} data-testid="trigger-save" loading={saving}
                                disabled={saving || !draft.name.trim() || !draft.cronExpression.trim()
                                        || !draftTenant.trim() || !draft.agentId.trim()}>
                            {t('common.save')}
                        </Button>
                    </Group>
                </Stack>
            </Modal>

            <TriggerRunModal trigger={viewing} onClose={() => setViewing(null)} />
        </Stack>
    )
}

/**
 * A última execução do gatilho — a resposta do agente, ou o erro (RCHFLW-191). Antes ela só existia em
 * GET /archflow/agents/invocations/{requestId}: a tela mostrava "concluído" e o aviso mandava consultar um
 * requestId que ela nem exibia.
 */
function TriggerRunModal({ trigger, onClose }: { trigger: ScheduledTrigger | null; onClose: () => void }) {
    const { t, i18n } = useTranslation()
    const locale = i18n.resolvedLanguage ?? i18n.language
    const [result, setResult] = useState<InvocationResult | null>(null)
    const [failure, setFailure] = useState<'notFound' | 'failed' | null>(null)
    const requestId = trigger?.lastRequestId ?? null
    const tenantId = trigger?.tenantId ?? ''

    useEffect(() => {
        setResult(null)
        setFailure(null)
        if (!requestId) return
        let vivo = true
        agentPlaygroundApi.invocation(requestId, tenantId)
            .then(r => { if (vivo) setResult(r) })
            .catch(e => { if (vivo) setFailure(e instanceof InvocationNotFoundError ? 'notFound' : 'failed') })
        return () => { vivo = false }
    }, [requestId, tenantId])

    return (
        <Modal opened={trigger !== null} onClose={onClose} size="lg" data-testid="trigger-run-modal"
               title={t('triggers.runResult.title', { name: trigger?.name ?? '' })}>
            <Stack gap="sm">
                <Text size="sm">{t('triggers.runResult.requestId')} <Code data-testid="trigger-run-request">
                    {requestId}</Code></Text>
                {!result && !failure && <Loader size="sm" data-testid="trigger-run-loading" />}
                {failure && (
                    <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}
                           data-testid="trigger-run-unavailable">
                        {t(failure === 'notFound' ? 'triggers.runResult.notFound' : 'triggers.runResult.queryFailed')}
                    </Alert>
                )}
                {result && (
                    <>
                        <Group gap="xs">
                            <Badge variant="light" data-testid="trigger-run-status"
                                   color={result.status === 'COMPLETED' ? 'green'
                                       : result.status === 'FAILED' ? 'red' : 'blue'}>
                                {t(`triggers.lastStatus.${result.status}`)}
                            </Badge>
                            {result.finishedAt && (
                                <Text size="xs" c="dimmed">{formatInstant(result.finishedAt, locale)}</Text>
                            )}
                        </Group>
                        {result.status === 'FAILED' && (
                            <Alert color="red" variant="light" icon={<IconAlertTriangle size={16} />}
                                   data-testid="trigger-run-error">
                                {result.error || t('triggers.run.noErrorMessage')}
                            </Alert>
                        )}
                        {result.status === 'COMPLETED' && (result.output == null
                            ? <Text size="sm" c="dimmed">{t('triggers.runResult.noOutput')}</Text>
                            : <JsonViewer value={result.output} autoExpandDepth={2} testId="trigger-run-output" />)}
                        {!isTerminal(result.status) && (
                            <Text size="sm" c="dimmed">{t('triggers.runResult.notFinished')}</Text>
                        )}
                    </>
                )}
            </Stack>
        </Modal>
    )
}

type NewDraft = {
    name: string
    cronExpression: string
    tenantId: string
    agentId: string
    payloadJson: string
    enabled: boolean
}

const blankDraft: NewDraft = {
    name: '', cronExpression: '', tenantId: '', agentId: '',
    payloadJson: '{}', enabled: true,
}
