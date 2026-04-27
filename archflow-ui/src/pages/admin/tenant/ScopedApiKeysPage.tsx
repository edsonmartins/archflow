import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Card, Code, Group, Modal, MultiSelect, Stack, Table,
    Text, TextInput, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    scopedApiKeyApi,
    type CreateScopedApiKeyResponse,
    type ScopedApiKey,
} from '../../../services/scoped-apikey-api'

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
    useEffect(() => { reload() }, [])

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

    const remove = async (id: string) => {
        try {
            await scopedApiKeyApi.remove(id)
            notifications.show({ color: 'green', title: t('admin.tenant.scopedKeys.revoked'),
                message: t('admin.tenant.scopedKeys.revokedMsg', { id }) })
            await reload()
        } catch (e) {
            notifications.show({ color: 'red', title: t('admin.tenant.scopedKeys.revokeFailed'),
                message: e instanceof Error ? e.message : String(e) })
        }
    }

    return (
        <Stack gap="md" style={{ padding: 24 }} data-testid="scoped-apikeys-page">
            <Group justify="space-between">
                <Title order={2}>{t('admin.tenant.scopedKeys.title')}</Title>
                <Button onClick={() => setOpen(true)} data-testid="scoped-key-new">{t('admin.tenant.scopedKeys.new')}</Button>
            </Group>
            <Alert color="blue" variant="light">
                {t('admin.tenant.scopedKeys.info')}
            </Alert>

            {created && (
                <Alert color="green" title={t('admin.tenant.scopedKeys.created')} data-testid="scoped-key-created">
                    <Stack gap={6}>
                        <Text size="sm">{t('admin.tenant.scopedKeys.createdHint')}</Text>
                        <Code block>{created.keySecret}</Code>
                        <Button variant="default" onClick={() => setCreated(null)}>{t('admin.tenant.scopedKeys.dismiss')}</Button>
                    </Stack>
                </Alert>
            )}

            {loading ? <Text c="dimmed">{t('triggers.loading')}</Text> : (
                <Card withBorder p={0}>
                    <Table>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>{t('admin.tenant.scopedKeys.columns.name')}</Table.Th>
                                <Table.Th>{t('admin.tenant.scopedKeys.columns.keyId')}</Table.Th>
                                <Table.Th>{t('admin.tenant.scopedKeys.columns.scopes')}</Table.Th>
                                <Table.Th>{t('admin.tenant.scopedKeys.columns.created')}</Table.Th>
                                <Table.Th>{t('admin.tenant.scopedKeys.columns.expires')}</Table.Th>
                                <Table.Th>{t('admin.tenant.scopedKeys.columns.status')}</Table.Th>
                                <Table.Th>{t('admin.tenant.scopedKeys.columns.actions')}</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {keys.length === 0 && (
                                <Table.Tr>
                                    <Table.Td colSpan={7}>
                                        <Text c="dimmed" ta="center" py="md">{t('admin.tenant.scopedKeys.empty')}</Text>
                                    </Table.Td>
                                </Table.Tr>
                            )}
                            {keys.map(k => (
                                <Table.Tr key={k.id}>
                                    <Table.Td>{k.name}</Table.Td>
                                    <Table.Td><Code>{k.keyId}</Code></Table.Td>
                                    <Table.Td>
                                        {k.scopes.map(s => (
                                            <Badge key={s} size="sm" variant="light" mr={4}>{s}</Badge>
                                        ))}
                                    </Table.Td>
                                    <Table.Td>{k.createdAt}</Table.Td>
                                    <Table.Td>{k.expiresAt ?? '—'}</Table.Td>
                                    <Table.Td>
                                        <Badge color={k.enabled ? 'green' : 'gray'}>
                                            {k.enabled ? t('admin.tenant.scopedKeys.status.active') : t('admin.tenant.scopedKeys.status.revoked')}
                                        </Badge>
                                    </Table.Td>
                                    <Table.Td>
                                        <Button size="xs" variant="outline" color="red"
                                                onClick={() => remove(k.id)}>
                                            {t('admin.tenant.scopedKeys.revoke')}
                                        </Button>
                                    </Table.Td>
                                </Table.Tr>
                            ))}
                        </Table.Tbody>
                    </Table>
                </Card>
            )}

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
