import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Code, Group, Modal, MultiSelect, Stack, Text, TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconPlus, IconShieldLock, IconInfoCircle, IconCircleCheck, IconKey } from '@tabler/icons-react'
import {
    scopedApiKeyApi,
    type CreateScopedApiKeyResponse,
    type ScopedApiKey,
} from '../../../services/scoped-apikey-api'
import { confirmAction } from '../../../lib/confirm'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { VxTable, colunaAcoes, colunaApagada, colunaStatus, type VxColumn } from '../../../components/VTable'

const DEFAULT_SCOPES = ['workflow.read', 'workflow.execute', 'event.ingest', 'observability.read']

/**
 * Surfaces the scoped API key system
 * ({@code /api/apikeys/*}) which is separate from the dev
 * "workspace keys" page. These keys carry explicit scopes and an
 * optional expiry. The plaintext secret is shown exactly once after
 * creation — the UI mirrors that by only displaying it inside the
 * "just created" banner.
 */
export default function ScopedApiKeysPage() {
    const { t } = useTranslation()
    const [keys, setKeys]       = useState<ScopedApiKey[]>([])
    const [loading, setLoading] = useState(true)
    const [open, setOpen]       = useState(false)
    const [name, setName]       = useState('')
    const [scopes, setScopes]   = useState<string[]>(['workflow.read'])
    const [created, setCreated] = useState<CreateScopedApiKeyResponse | null>(null)

    const reload = async () => {
        setLoading(true)
        try {
            setKeys(await scopedApiKeyApi.list())
        } catch (e) {
            notifications.show({ color: 'red', title: t('admin.tenant.scopedKeys.loadFailed'),
                message: e instanceof Error ? e.message : String(e) })
        } finally {
            setLoading(false)
        }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    useEffect(() => { void reload() }, [])

    const create = async () => {
        try {
            const res = await scopedApiKeyApi.create({ name, scopes })
            setCreated(res)
            setName('')
            await reload()
        } catch (e) {
            notifications.show({ color: 'red', title: t('admin.tenant.scopedKeys.createFailed'),
                message: e instanceof Error ? e.message : String(e) })
        }
    }

    const remove = (id: string, name: string) => {
        confirmAction({
            title: t('confirmations.revokeKeyTitle'),
            message: t('confirmations.revokeKeyMessage', { name }),
            confirmLabel: t('confirmations.revoke'),
            onConfirm: async () => {
                try {
                    await scopedApiKeyApi.remove(id)
                    notifications.show({ color: 'green', title: t('admin.tenant.scopedKeys.revoked'),
                        message: t('admin.tenant.scopedKeys.revokedMsg', { id }) })
                    await reload()
                } catch (e) {
                    notifications.show({ color: 'red', title: t('admin.tenant.scopedKeys.revokeFailed'),
                        message: e instanceof Error ? e.message : String(e) })
                }
            },
        })
    }

    const colunas = useMemo((): VxColumn[] => [
        { field: 'name', title: t('admin.tenant.scopedKeys.columns.name'), width: 'auto', minWidth: 180,
          style: { fontWeight: 600 } },
        colunaApagada({ field: 'keyId', title: t('admin.tenant.scopedKeys.columns.keyId'), width: 160 }),
        { field: 'scopes', title: t('admin.tenant.scopedKeys.columns.scopes'), width: 280,
          fieldFormat: (r) => (r.scopes as string[]).join(', ') },
        colunaApagada({ field: 'createdAt', title: t('admin.tenant.scopedKeys.columns.created'), width: 130 }),
        colunaApagada({ field: 'expiresAt', title: t('admin.tenant.scopedKeys.columns.expires'), width: 130,
          fieldFormat: (r) => (r.expiresAt as string | undefined) ?? '—' }),
        colunaStatus(
            { field: 'enabled', title: t('admin.tenant.scopedKeys.columns.status'), width: 130,
              fieldFormat: (r) => (r.enabled ? t('admin.tenant.scopedKeys.status.active') : t('admin.tenant.scopedKeys.status.revoked')) },
            (r) => (r.enabled ? 'green' : 'gray'),
        ),
        colunaAcoes([
            { nome: 'revoke', icone: 'trash', rotulo: t('admin.tenant.scopedKeys.revoke'), cor: 'red' },
        ]),
    ], [t])

    return (
        <Stack gap="lg" p="md" data-testid="scoped-apikeys-page">
            <PageHeader
                icon={IconShieldLock}
                accent="slate"
                title={t('admin.tenant.scopedKeys.title')}
                subtitle={t('admin.tenant.scopedKeys.subtitle')}
                actions={
                    <Button leftSection={<IconPlus size={16} />} onClick={() => setOpen(true)} data-testid="scoped-key-new">
                        {t('admin.tenant.scopedKeys.new')}
                    </Button>
                }
            />
            <Alert color="blue" variant="light" icon={<IconInfoCircle size={16} />}>
                {t('admin.tenant.scopedKeys.info')}
            </Alert>

            {created && (
                <Alert color="green" variant="light" icon={<IconCircleCheck size={16} />}
                       title={t('admin.tenant.scopedKeys.created')} data-testid="scoped-key-created">
                    <Stack gap="xs">
                        <Text size="sm">{t('admin.tenant.scopedKeys.createdHint')}</Text>
                        <Code block>{created.keySecret}</Code>
                        <Group justify="flex-end">
                            <Button size="xs" variant="default" onClick={() => setCreated(null)}>{t('admin.tenant.scopedKeys.dismiss')}</Button>
                        </Group>
                    </Stack>
                </Alert>
            )}

            <SectionCard
                flush
                icon={IconKey}
                accent="slate"
                title={t('admin.tenant.scopedKeys.listTitle')}
                description={t('admin.tenant.scopedKeys.listHint')}
            >
                <VxTable
                    legenda={t('admin.tenant.scopedKeys.listTitle')}
                    records={keys as unknown as Record<string, unknown>[]}
                    columns={colunas}
                    loading={loading && keys.length === 0}
                    onIconClick={(_nome, r) => remove(String(r.id), String(r.name))}
                    emptyMessage={t('admin.tenant.scopedKeys.empty')}
                    emptyDescription={t('admin.tenant.scopedKeys.emptyHint')}
                    emptyIcon={<IconShieldLock />}
                    emptyAccent="slate"
                    emptyAction={
                        <Button variant="light" size="xs" leftSection={<IconPlus size={14} />} onClick={() => setOpen(true)}>
                            {t('admin.tenant.scopedKeys.new')}
                        </Button>
                    }
                    testid="scoped-key-list"
                    rowTestId={(r) => `scoped-key-row-${r.id}`}
                />
            </SectionCard>

            <Modal opened={open} onClose={() => setOpen(false)} title={t('admin.tenant.scopedKeys.newTitle')}>
                <Stack>
                    <TextInput label={t('admin.tenant.scopedKeys.form.name')} value={name} onChange={e => setName(e.currentTarget.value)} />
                    <MultiSelect
                        label={t('admin.tenant.scopedKeys.form.scopes')}
                        data={DEFAULT_SCOPES}
                        value={scopes}
                        onChange={setScopes}
                        searchable
                    />
                    <Group justify="flex-end">
                        <Button variant="default" onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
                        <Button disabled={!name.trim() || scopes.length === 0}
                                onClick={() => { void create(); setOpen(false) }}>
                            {t('common.create')}
                        </Button>
                    </Group>
                </Stack>
            </Modal>
        </Stack>
    )
}
