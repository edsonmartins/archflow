import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import {
    Alert, Button, Group, Modal, Select, SimpleGrid, Stack, Switch,
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
        let parsedPayload: Record<string, unknown>
        try {
            parsedPayload = draft.payloadJson.trim() ? JSON.parse(draft.payloadJson) : {}
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
        try {
            if (editing && editing.id) {
                await triggersApi.update(editing.id, body)
            } else {
                await triggersApi.create(body)
            }
            setEdit(null)
            await reload()
        } catch (e) {
            notifications.show({ color: 'red', title: t('triggers.saveFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
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
            { field: 'enabled', title: t('triggers.table.status'), width: 120,
              fieldFormat: (r) => (r.enabled ? t('triggers.status.enabled') : t('triggers.status.paused')) },
            (r) => (r.enabled ? 'green' : 'gray'),
        ),
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
            { nome: 'fire', icone: 'play', rotulo: t('triggers.fireNow'), cor: 'violet', mostrar: () => canFire },
            { nome: 'edit', icone: 'pencil', rotulo: t('triggers.edit'), mostrar: () => canUpdate },
            { nome: 'delete', icone: 'trash', rotulo: t('triggers.delete'), cor: 'red', mostrar: () => canUpdate },
        ]),
    ], [t, locale, canFire, canUpdate])

    const enabledCount = items.filter(i => i.enabled).length
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
                <StatCard label={t('triggers.stats.paused')} value={firstLoad ? '—' : items.length - enabledCount}
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
                {t('triggers.info').split(/<code>|<\/code>/).map((seg, i) =>
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
                              styles={{ input: { fontFamily: 'var(--font-mono)' } }} />
                    <Switch label={t('triggers.enabled')}
                            checked={draft.enabled}
                            onChange={e => setDraft({ ...draft, enabled: e.currentTarget.checked })} />
                    <Group justify="flex-end" mt="xs">
                        <Button variant="default" onClick={() => setEdit(null)}>{t('common.cancel')}</Button>
                        <Button onClick={save} data-testid="trigger-save"
                                disabled={!draft.name.trim() || !draft.cronExpression.trim()
                                        || !draftTenant.trim() || !draft.agentId.trim()}>
                            {t('common.save')}
                        </Button>
                    </Group>
                </Stack>
            </Modal>
        </Stack>
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
