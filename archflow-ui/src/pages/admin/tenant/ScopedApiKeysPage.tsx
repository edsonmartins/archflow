import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Code, Group, Modal, MultiSelect, Stack, Text, TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconPlus, IconShieldLock, IconInfoCircle, IconCircleCheck, IconKey } from '@tabler/icons-react'
import {
    scopedApiKeyApi,
    type ApiKeyScopeOption,
    type CreateScopedApiKeyResponse,
    type ScopedApiKey,
} from '../../../services/scoped-apikey-api'
import { confirmAction } from '../../../lib/confirm'
import { mensagemDoErro } from '../../../lib/errorMessage'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { VxTable, colunaAcoes, colunaApagada, colunaStatus, type VxColumn } from '../../../components/VTable'
import { useTenantStore } from '../../../stores/useTenantStore'

/**
 * Surfaces the scoped API key system
 * ({@code /api/apikeys/*}) which is separate from the dev
 * "workspace keys" page. These keys carry explicit scopes and an
 * optional expiry. The plaintext secret is shown exactly once after
 * creation — the UI mirrors that by only displaying it inside the
 * "just created" banner.
 *
 * <p>Os escopos do seletor vêm do backend ({@code GET /api/apikeys/scopes}): um vocabulário só, o
 * que a criação aceita (RCHFLW-83). O que aparece no banner é a credencial completa
 * ({@code apiKey}), a que vai em {@code Authorization: Bearer} — o segredo sozinho não autentica.
 * Erros aparecem como a frase do backend, nunca como o JSON da resposta.
 *
 * <p>O seletor segue o que o backend marca em cada escopo: o que o papel do usuário não concede fica
 * desabilitado (a chave nunca vale mais que o dono) e o que ainda não abre endpoint nenhum é avisado —
 * uma chave só com ele recebe 403 em toda chamada.</p>
 *
 * <p>As chaves são do usuário logado, no tenant do login dele: o servidor ignora a impersonação aqui.
 * Um superadmin que está vendo outro workspace é avisado disso, para não achar que a chave age nele.</p>
 */
export default function ScopedApiKeysPage() {
    const { t } = useTranslation()
    const { impersonating } = useTenantStore()
    const [keys, setKeys]       = useState<ScopedApiKey[]>([])
    const [loading, setLoading] = useState(true)
    const [open, setOpen]       = useState(false)
    const [name, setName]       = useState('')
    const [scopes, setScopes]   = useState<string[]>([])
    const [created, setCreated] = useState<CreateScopedApiKeyResponse | null>(null)
    const [scopeOptions, setScopeOptions] = useState<ApiKeyScopeOption[]>([])
    const [scopesError, setScopesError]   = useState<string | null>(null)
    const [createError, setCreateError]   = useState<string | null>(null)
    const [creating, setCreating]         = useState(false)

    const reload = async () => {
        setLoading(true)
        try {
            setKeys(await scopedApiKeyApi.list())
        } catch (e) {
            notifications.show({ color: 'red', title: t('admin.tenant.scopedKeys.loadFailed'),
                message: mensagemDoErro(e, t('admin.tenant.scopedKeys.loadFailed')) })
        } finally {
            setLoading(false)
        }
    }
    const loadScopes = async () => {
        try {
            setScopeOptions(await scopedApiKeyApi.scopes())
            setScopesError(null)
        } catch (e) {
            setScopesError(mensagemDoErro(e, t('admin.tenant.scopedKeys.scopesFailed')))
        }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    useEffect(() => { void reload(); void loadScopes() }, [])

    const scopeLabel = (scope: string) => {
        const option = scopeOptions.find(o => o.name === scope)
        return option ? option.permission : scope
    }

    const semEndpoint = scopes.filter(s => scopeOptions.find(o => o.name === s)?.opensEndpoints === false)

    const scopeOptionLabel = (o: ApiKeyScopeOption) => {
        const base = `${t(`admin.tenant.scopedKeys.scopeNames.${o.name}`)} (${o.permission})`
        if (!o.grantable) return `${base} — ${t('admin.tenant.scopedKeys.scopeNotGrantable')}`
        if (!o.opensEndpoints) return `${base} — ${t('admin.tenant.scopedKeys.scopeOpensNothing')}`
        return base
    }

    const openCreate = () => {
        setCreateError(null)
        setOpen(true)
    }

    // O diálogo só fecha quando a chave existe: numa falha ele fica aberto, com a frase do erro, e o
    // usuário corrige sem redigitar.
    const create = async () => {
        setCreating(true)
        setCreateError(null)
        try {
            const res = await scopedApiKeyApi.create({ name: name.trim(), scopes })
            setCreated(res)
            setName('')
            setScopes([])
            setOpen(false)
            await reload()
        } catch (e) {
            setCreateError(mensagemDoErro(e, t('admin.tenant.scopedKeys.createFailed')))
        } finally {
            setCreating(false)
        }
    }

    const remove = (keyId: string, name: string) => {
        confirmAction({
            title: t('confirmations.revokeKeyTitle'),
            message: t('confirmations.revokeKeyMessage', { name }),
            confirmLabel: t('confirmations.revoke'),
            onConfirm: async () => {
                try {
                    await scopedApiKeyApi.remove(keyId)
                    notifications.show({ color: 'green', title: t('admin.tenant.scopedKeys.revoked'),
                        message: t('admin.tenant.scopedKeys.revokedMsg', { id: keyId }) })
                    await reload()
                } catch (e) {
                    notifications.show({ color: 'red', title: t('admin.tenant.scopedKeys.revokeFailed'),
                        message: mensagemDoErro(e, t('admin.tenant.scopedKeys.revokeFailed')) })
                }
            },
        })
    }

    const colunas = useMemo((): VxColumn[] => [
        { field: 'name', title: t('admin.tenant.scopedKeys.columns.name'), width: 'auto', minWidth: 180,
          style: { fontWeight: 600 } },
        colunaApagada({ field: 'keyId', title: t('admin.tenant.scopedKeys.columns.keyId'), width: 160 }),
        { field: 'scopes', title: t('admin.tenant.scopedKeys.columns.scopes'), width: 280,
          fieldFormat: (r) => (r.scopes as string[]).map(scopeLabel).join(', ') },
        colunaApagada({ field: 'createdAt', title: t('admin.tenant.scopedKeys.columns.created'), width: 130 }),
        colunaApagada({ field: 'expiresAt', title: t('admin.tenant.scopedKeys.columns.expires'), width: 130,
          fieldFormat: (r) => (r.expiresAt as string | undefined) ?? '—' }),
        colunaStatus(
            { field: 'enabled', title: t('admin.tenant.scopedKeys.columns.status'), width: 130,
              fieldFormat: (r) => (r.enabled ? t('admin.tenant.scopedKeys.status.active') : t('admin.tenant.scopedKeys.status.revoked')) },
            (r) => (r.enabled ? 'green' : 'gray'),
        ),
        colunaAcoes([
            // Revogada não volta: oferecer "Revogar" de novo era um botão morto.
            { nome: 'revoke', icone: 'trash', rotulo: t('admin.tenant.scopedKeys.revoke'), cor: 'red',
              mostrar: (r) => Boolean(r.enabled) },
        ]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    ], [t, scopeOptions])

    return (
        <Stack gap="lg" p="md" data-testid="scoped-apikeys-page">
            <PageHeader
                icon={IconShieldLock}
                accent="slate"
                title={t('admin.tenant.scopedKeys.title')}
                subtitle={t('admin.tenant.scopedKeys.subtitle')}
                actions={
                    <Button leftSection={<IconPlus size={16} />} onClick={openCreate} data-testid="scoped-key-new">
                        {t('admin.tenant.scopedKeys.new')}
                    </Button>
                }
            />
            <Alert color="blue" variant="light" icon={<IconInfoCircle size={16} />}>
                {t('admin.tenant.scopedKeys.info')}
            </Alert>
            {impersonating && (
                <Alert color="yellow" variant="light" icon={<IconInfoCircle size={16} />}
                       data-testid="scoped-key-impersonation">
                    {t('admin.tenant.scopedKeys.impersonationNotice', { name: impersonating.name })}
                </Alert>
            )}

            {created && (
                <Alert color="green" variant="light" icon={<IconCircleCheck size={16} />}
                       title={t('admin.tenant.scopedKeys.created')} data-testid="scoped-key-created">
                    <Stack gap="xs">
                        <Text size="sm">{t('admin.tenant.scopedKeys.createdHint')}</Text>
                        <Code block data-testid="scoped-key-credential">{created.apiKey}</Code>
                        <Text size="sm" c="dimmed">{t('admin.tenant.scopedKeys.usageHint')}</Text>
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
                    onIconClick={(_nome, r) => remove(String(r.keyId), String(r.name))}
                    emptyMessage={t('admin.tenant.scopedKeys.empty')}
                    emptyDescription={t('admin.tenant.scopedKeys.emptyHint')}
                    emptyIcon={<IconShieldLock />}
                    emptyAccent="slate"
                    emptyAction={
                        <Button variant="light" size="xs" leftSection={<IconPlus size={14} />} onClick={openCreate}>
                            {t('admin.tenant.scopedKeys.new')}
                        </Button>
                    }
                    testid="scoped-key-list"
                    rowTestId={(r) => `scoped-key-row-${r.id}`}
                />
            </SectionCard>

            <Modal opened={open} onClose={() => setOpen(false)} title={t('admin.tenant.scopedKeys.newTitle')}>
                <Stack data-testid="scoped-key-dialog">
                    <TextInput label={t('admin.tenant.scopedKeys.form.name')} value={name} onChange={e => setName(e.currentTarget.value)} />
                    {scopesError ? (
                        <Alert color="red" variant="light" title={t('admin.tenant.scopedKeys.scopesFailed')}
                               data-testid="scoped-key-scopes-error">
                            {scopesError}
                        </Alert>
                    ) : (
                        <MultiSelect
                            label={t('admin.tenant.scopedKeys.form.scopes')}
                            data={scopeOptions.map(o => ({
                                value: o.name,
                                label: scopeOptionLabel(o),
                                disabled: !o.grantable,
                            }))}
                            value={scopes}
                            onChange={setScopes}
                            searchable
                            data-testid="scoped-key-scopes"
                        />
                    )}
                    {semEndpoint.length > 0 && (
                        <Alert color="yellow" variant="light" data-testid="scoped-key-scopes-unused">
                            {t('admin.tenant.scopedKeys.scopesOpenNothing', {
                                scopes: semEndpoint.map(s => t(`admin.tenant.scopedKeys.scopeNames.${s}`)).join(', '),
                            })}
                        </Alert>
                    )}
                    {createError && (
                        <Alert color="red" variant="light" title={t('admin.tenant.scopedKeys.createFailed')}
                               data-testid="scoped-key-create-error">
                            {createError}
                        </Alert>
                    )}
                    <Group justify="flex-end">
                        <Button variant="default" onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
                        <Button disabled={!name.trim() || scopes.length === 0 || creating}
                                loading={creating}
                                onClick={() => { void create() }}
                                data-testid="scoped-key-create">
                            {t('common.create')}
                        </Button>
                    </Group>
                </Stack>
            </Modal>
        </Stack>
    )
}
