import {
  Text, Paper, Stack, Group, Button, Modal, TextInput, Select, CopyButton, LoadingOverlay, Alert,
} from '@mantine/core'
import { IconPlus, IconCopy, IconCheck, IconAlertCircle, IconKey, IconAlertTriangle } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { apiKeyApi, type ApiKey } from '../../../services/admin-api'
import { confirmAction } from '../../../lib/confirm'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { VxTable, colunaAcoes, colunaApagada, colunaStatus, type VxColumn } from '../../../components/VTable'

const TYPE_COLOR: Record<string, string> = {
  production: 'green', staging: 'yellow', web_component: 'blue',
}

export default function ApiKeys() {
  const { t } = useTranslation()
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [keyName, setKeyName] = useState('')
  const [keyType, setKeyType] = useState<string | null>('production')
  const [newKey, setNewKey] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      setKeys(await apiKeyApi.list())
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.tenant.apiKeys.loadFailed'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleCreate = async () => {
    if (!keyType || !keyName) return
    try {
      const created = await apiKeyApi.create({ name: keyName, type: keyType as ApiKey['type'] })
      setKeys((prev) => [...prev, created])
      setNewKey(created.fullKey)
      notifications.show({ title: t('admin.tenant.apiKeys.created'), message: t('admin.tenant.apiKeys.createdHint'), color: 'teal' })
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.tenant.apiKeys.createFailed'))
    }
  }

  const handleRevoke = (key: ApiKey) => {
    confirmAction({
      title: t('confirmations.revokeKeyTitle'),
      message: t('confirmations.revokeKeyMessage', { name: key.name }),
      confirmLabel: t('confirmations.revoke'),
      onConfirm: async () => {
        try {
          await apiKeyApi.revoke(key.id)
          setKeys((prev) => prev.filter((entry) => entry.id !== key.id))
        } catch (e) {
          setError(e instanceof Error ? e.message : t('admin.tenant.apiKeys.revokeFailed'))
        }
      },
    })
  }

  const copyMasked = async (key: ApiKey) => {
    try {
      await navigator.clipboard.writeText(key.maskedKey)
      notifications.show({ title: t('admin.tenant.apiKeys.copied'), message: key.name, color: 'teal' })
    } catch {
      // Sem permissão de área de transferência: a chave mascarada continua visível na linha.
    }
  }

  const colunas = useMemo((): VxColumn[] => [
    { field: 'name', title: t('admin.tenant.apiKeys.columns.name'), width: 'auto', minWidth: 200,
      style: { fontWeight: 600 } },
    colunaStatus(
      { field: 'type', title: t('admin.tenant.apiKeys.columns.type'), width: 170,
        fieldFormat: (r) => t(`admin.tenant.apiKeys.types.${r.type}`, { defaultValue: String(r.type) }) },
      (r) => TYPE_COLOR[r.type as string] ?? 'gray',
    ),
    colunaApagada({ field: 'maskedKey', title: t('admin.tenant.apiKeys.columns.key'), width: 220 }),
    colunaApagada({ field: 'createdAt', title: t('admin.tenant.apiKeys.columns.created'), width: 140 }),
    colunaApagada({ field: 'lastUsedAt', title: t('admin.tenant.apiKeys.columns.lastUsed'), width: 140,
      fieldFormat: (r) => (r.lastUsedAt as string | null) ?? '—' }),
    colunaAcoes([
      { nome: 'copy', icone: 'copy', rotulo: t('admin.tenant.apiKeys.copy') },
      { nome: 'revoke', icone: 'trash', rotulo: t('admin.tenant.apiKeys.revoke'), cor: 'red' },
    ]),
  ], [t])

  const openCreate = () => { setCreateOpen(true); setNewKey(null); setKeyName('') }

  return (
    <Stack gap="lg" p="md" pos="relative">
      <LoadingOverlay visible={loading && keys.length > 0} />

      <PageHeader
        icon={IconKey}
        accent="slate"
        title={t('admin.tenant.apiKeys.title')}
        subtitle={t('admin.tenant.apiKeys.subtitle')}
        actions={
          <Button leftSection={<IconPlus size={16} />} onClick={openCreate}>
            {t('admin.tenant.apiKeys.create')}
          </Button>
        }
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SectionCard
        flush
        icon={IconKey}
        accent="slate"
        title={t('admin.tenant.apiKeys.listTitle')}
        description={t('admin.tenant.apiKeys.listHint')}
      >
        <VxTable
          legenda={t('admin.tenant.apiKeys.listTitle')}
          records={keys as unknown as Record<string, unknown>[]}
          columns={colunas}
          loading={loading && keys.length === 0}
          onIconClick={(nome, r) => {
            const key = r as unknown as ApiKey
            if (nome === 'copy') void copyMasked(key)
            else handleRevoke(key)
          }}
          emptyMessage={t('admin.tenant.apiKeys.emptyTitle')}
          emptyDescription={t('admin.tenant.apiKeys.emptyHint')}
          emptyIcon={<IconKey />}
          emptyAccent="slate"
          emptyAction={
            <Button variant="light" size="xs" leftSection={<IconPlus size={14} />} onClick={openCreate}>
              {t('admin.tenant.apiKeys.create')}
            </Button>
          }
          testid="api-key-list"
          rowTestId={(r) => `api-key-row-${r.id}`}
        />
      </SectionCard>

      <Modal opened={createOpen} onClose={() => setCreateOpen(false)} title={t('admin.tenant.apiKeys.createTitle')} centered>
        <Stack gap="md">
          {newKey ? (
            <>
              <Text size="sm" fw={500}>{t('admin.tenant.apiKeys.newKeyLabel')}</Text>
              <Paper withBorder p="sm" radius="md" bg="var(--color-background-tertiary)">
                <Group gap="xs">
                  <Text size="sm" ff="monospace" style={{ wordBreak: 'break-all' }}>{newKey}</Text>
                  <CopyButton value={newKey}>
                    {({ copied, copy }) => (
                      <Button size="xs" variant="light" onClick={copy} leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}>
                        {copied ? t('admin.tenant.apiKeys.copiedExcl') : t('admin.tenant.apiKeys.copy')}
                      </Button>
                    )}
                  </CopyButton>
                </Group>
              </Paper>
              <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />}>
                {t('admin.tenant.apiKeys.storeWarning')}
              </Alert>
              <Button onClick={() => setCreateOpen(false)}>{t('admin.tenant.apiKeys.done')}</Button>
            </>
          ) : (
            <>
              <TextInput label={t('admin.tenant.apiKeys.form.name')} required placeholder={t('admin.tenant.apiKeys.form.namePlaceholder')} value={keyName}
                onChange={e => setKeyName(e.currentTarget.value)} />
              <Select label={t('admin.tenant.apiKeys.form.type')} required data={[
                { value: 'production',    label: t('admin.tenant.apiKeys.types.production') },
                { value: 'staging',       label: t('admin.tenant.apiKeys.types.staging') },
                { value: 'web_component', label: t('admin.tenant.apiKeys.types.web_component') },
              ]} value={keyType} onChange={setKeyType} />
              <Group justify="flex-end">
                <Button variant="light" onClick={() => setCreateOpen(false)}>{t('common.cancel')}</Button>
                <Button onClick={() => void handleCreate()} disabled={!keyName}>{t('common.create')}</Button>
              </Group>
            </>
          )}
        </Stack>
      </Modal>
    </Stack>
  )
}
