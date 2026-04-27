import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Card, Code, Group, Modal, Select, Stack, Switch,
    Table, Text, Textarea, TextInput, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { triggersApi, type ScheduledTrigger } from '../services/triggers-api'
import { useCatalog } from '../components/PropertyPanel/useCatalog'
import { ApiError } from '../services/api'

/**
 * Cron-scheduled trigger admin surface.
 *
 * <p>Lets operators configure recurring agent invocations (daily
 * digests, hourly health checks, etc.) without writing YAML or
 * touching Quartz directly. The cron expression is validated
 * server-side before the trigger is materialised.</p>
 */
export default function ScheduledTriggersPage() {
    const { t } = useTranslation()
    const [items, setItems]   = useState<ScheduledTrigger[]>([])
    const [loading, setL]     = useState(true)
    const [error, setError]   = useState<string | null>(null)
    const [editing, setEdit]  = useState<ScheduledTrigger | null>(null)
    const [draft, setDraft]   = useState<NewDraft>(blankDraft)

    const catalog = useCatalog()

    const agentOptions = [
        ...catalog.agents.map(a => ({ value: a.id, label: `${a.displayName} (agent)` })),
        ...catalog.assistants.map(a => ({ value: a.id, label: `${a.displayName} (assistant)` })),
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
    const openEdit = (t: ScheduledTrigger) => {
        setDraft({
            name: t.name,
            cronExpression: t.cronExpression,
            tenantId: t.tenantId,
            agentId: t.agentId,
            payloadJson: JSON.stringify(t.payload ?? {}, null, 2),
            enabled: t.enabled,
        })
        setEdit(t)
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

    const remove = async (trigger: ScheduledTrigger) => {
        if (!confirm(t('triggers.confirmDelete', { name: trigger.name }))) return
        try {
            await triggersApi.remove(trigger.id)
            await reload()
        } catch (e) {
            notifications.show({ color: 'red', title: t('triggers.deleteFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        }
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

    return (
        <Stack gap="md" style={{ padding: 24 }} data-testid="triggers-page">
            <Group justify="space-between">
                <Title order={2}>{t('triggers.title')}</Title>
                <Group>
                    <Button variant="default" onClick={reload}>{t('common.refresh')}</Button>
                    <Button onClick={openCreate} data-testid="trigger-new">{t('triggers.newTrigger')}</Button>
                </Group>
            </Group>

            <Alert color="blue" variant="light">
                {t('triggers.info').split(/<code>|<\/code>/).map((seg, i) =>
                    i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
            </Alert>

            {error && <Alert color="red">{error}</Alert>}

            {loading && <Text c="dimmed">{t('triggers.loading')}</Text>}

            {!loading && (
                <Card withBorder p={0}>
                    <Table>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>{t('triggers.table.name')}</Table.Th>
                                <Table.Th>{t('triggers.table.cron')}</Table.Th>
                                <Table.Th>{t('triggers.table.tenant')}</Table.Th>
                                <Table.Th>{t('triggers.table.agent')}</Table.Th>
                                <Table.Th>{t('triggers.table.status')}</Table.Th>
                                <Table.Th>{t('triggers.table.lastFired')}</Table.Th>
                                <Table.Th />
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {items.length === 0 && (
                                <Table.Tr><Table.Td colSpan={7}>
                                    <Text c="dimmed" ta="center" py="md">{t('triggers.empty')}</Text>
                                </Table.Td></Table.Tr>
                            )}
                            {items.map(trig => (
                                <Table.Tr key={trig.id} data-testid={`trigger-${trig.id}`}>
                                    <Table.Td>{trig.name}</Table.Td>
                                    <Table.Td><Code>{trig.cronExpression}</Code></Table.Td>
                                    <Table.Td>{trig.tenantId}</Table.Td>
                                    <Table.Td>{trig.agentId}</Table.Td>
                                    <Table.Td>
                                        <Badge color={trig.enabled ? 'green' : 'gray'}>
                                            {trig.enabled ? t('triggers.status.enabled') : t('triggers.status.paused')}
                                        </Badge>
                                    </Table.Td>
                                    <Table.Td>{trig.lastFiredAt ?? '—'}</Table.Td>
                                    <Table.Td>
                                        <Group gap={4}>
                                            <Button size="xs" variant="light"
                                                    onClick={() => fire(trig)}>{t('triggers.fireNow')}</Button>
                                            <Button size="xs" variant="default"
                                                    onClick={() => openEdit(trig)}>{t('triggers.edit')}</Button>
                                            <Button size="xs" color="red" variant="outline"
                                                    onClick={() => remove(trig)}>{t('triggers.delete')}</Button>
                                        </Group>
                                    </Table.Td>
                                </Table.Tr>
                            ))}
                        </Table.Tbody>
                    </Table>
                </Card>
            )}

            <Modal opened={editing !== null} onClose={() => setEdit(null)}
                   title={editing?.id ? t('triggers.editTitle') : t('triggers.newTitle')}
                   size="lg" data-testid="trigger-modal">
                <Stack>
                    <TextInput label={t('triggers.name')} value={draft.name}
                               onChange={e => setDraft({ ...draft, name: e.currentTarget.value })}
                               data-testid="trigger-name" />
                    <TextInput label={t('triggers.cron')}
                               value={draft.cronExpression}
                               onChange={e => setDraft({ ...draft, cronExpression: e.currentTarget.value })}
                               placeholder="0 0 2 * * ?"
                               description={t('triggers.cronHint')}
                               data-testid="trigger-cron" />
                    <TextInput label={t('triggers.tenant')}
                               value={draft.tenantId}
                               onChange={e => setDraft({ ...draft, tenantId: e.currentTarget.value })}
                               placeholder="tenant_demo" />
                    <Select label={t('triggers.agent')}
                            value={draft.agentId || null}
                            onChange={v => setDraft({ ...draft, agentId: v ?? '' })}
                            data={agentOptions}
                            searchable
                            data-testid="trigger-agent" />
                    <Textarea label={t('triggers.payload')}
                              value={draft.payloadJson}
                              onChange={e => setDraft({ ...draft, payloadJson: e.currentTarget.value })}
                              autosize minRows={3}
                              styles={{ input: { fontFamily: 'var(--font-mono)' } }} />
                    <Switch label={t('triggers.enabled')}
                            checked={draft.enabled}
                            onChange={e => setDraft({ ...draft, enabled: e.currentTarget.checked })} />
                    <Group justify="flex-end">
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
