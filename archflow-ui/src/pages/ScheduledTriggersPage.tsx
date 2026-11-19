import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Group, Modal, Select, SimpleGrid, Stack, Switch,
    Textarea, TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconAlertTriangle, IconClockPause, IconClockPlay, IconInfoCircle, IconPlayerPlay, IconPlus, IconRefresh,
} from '@tabler/icons-react'
import { triggersApi, type ScheduledTrigger } from '../services/triggers-api'
import { useCatalog } from '../components/PropertyPanel/useCatalog'
import { ApiError } from '../services/api'
import { confirmAction } from '../lib/confirm'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import { VxTable, colunaAcoes, colunaApagada, colunaDupla, colunaStatus, type VxColumn } from '../components/VTable'
import { formatInstant } from '../lib/format'

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
    const [items, setItems]   = useState<ScheduledTrigger[]>([])
    const [loading, setL]     = useState(true)
    const [error, setError]   = useState<string | null>(null)
    const [editing, setEdit]  = useState<ScheduledTrigger | null>(null)
    const [draft, setDraft]   = useState<NewDraft>(blankDraft)

    const catalog = useCatalog()

    const agentOptions = [
        ...catalog.agents.map(a => ({ value: a.id, label: `${a.displayName} (${t('triggers.agentLabel')})` })),
        ...catalog.assistants.map(a => ({ value: a.id, label: `${a.displayName} (${t('triggers.assistantLabel')})` })),
    ]

    const reload = async () => {
        setL(true); setError(null)
        try {
            setItems(await triggersApi.list())
        } catch (e) {
            setError(e instanceof ApiError ? e.message : String(e))
        } finally { setL(false) }
    }
    useEffect(() => { reload() }, [])

    const openCreate = () => { setDraft(blankDraft); setEdit({} as ScheduledTrigger) }
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
            tenantId: draft.tenantId,
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
            await triggersApi.fireNow(trigger.id)
            notifications.show({ color: 'green', title: t('triggers.fired'),
                message: t('triggers.firedMsg', { name: trigger.name }) })
            await reload()
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
        colunaAcoes([
            { nome: 'fire', icone: 'play', rotulo: t('triggers.fireNow'), cor: 'violet' },
            { nome: 'edit', icone: 'pencil', rotulo: t('triggers.edit') },
            { nome: 'delete', icone: 'trash', rotulo: t('triggers.delete'), cor: 'red' },
        ]),
    ], [t, locale])

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
                        <Button leftSection={<IconPlus size={16} />} onClick={openCreate} data-testid="trigger-new">
                            {t('triggers.newTrigger')}
                        </Button>
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
                    onRowClick={(r) => openEdit(r as unknown as ScheduledTrigger)}
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
                    emptyAction={
                        <Button variant="light" size="xs" leftSection={<IconPlus size={14} />} onClick={openCreate}>
                            {t('triggers.newTrigger')}
                        </Button>
                    }
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
                                   value={draft.tenantId}
                                   onChange={e => setDraft({ ...draft, tenantId: e.currentTarget.value })}
                                   description={t('triggers.tenantHint')}
                                   placeholder="tenant_demo" />
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
                                        || !draft.tenantId.trim() || !draft.agentId.trim()}>
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
