import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, ActionIcon,
  Tooltip, Modal, TextInput, Select, CopyButton, LoadingOverlay, Alert,
} from '@mantine/core'
import { IconPlus, IconCopy, IconTrash, IconCheck, IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { apiKeyApi, type ApiKey } from '../../../services/admin-api'

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
  }, [])

  const handleCreate = async () => {
    if (!keyType || !keyName) return
    try {
      const created = await apiKeyApi.create({ name: keyName, type: keyType as any })
      setKeys((prev) => [...prev, created])
      setNewKey(created.fullKey)
      notifications.show({ title: t('admin.tenant.apiKeys.created'), message: t('admin.tenant.apiKeys.createdHint'), color: 'teal' })
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.tenant.apiKeys.createFailed'))
    }
  }

  const handleRevoke = async (key: ApiKey) => {
    try {
      await apiKeyApi.revoke(key.id)
      setKeys((prev) => prev.filter((entry) => entry.id !== key.id))
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.tenant.apiKeys.revokeFailed'))
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />

      <Group justify="space-between">
        <Title order={3}>{t('admin.tenant.apiKeys.title')}</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => { setCreateOpen(true); setNewKey(null); setKeyName('') }}>
          {t('admin.tenant.apiKeys.create')}
        </Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <Paper withBorder radius="lg" style={{ overflow: 'hidden' }}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.tenant.apiKeys.columns.name')}</Table.Th>
              <Table.Th>{t('admin.tenant.apiKeys.columns.type')}</Table.Th>
              <Table.Th>{t('admin.tenant.apiKeys.columns.key')}</Table.Th>
              <Table.Th>{t('admin.tenant.apiKeys.columns.created')}</Table.Th>
              <Table.Th>{t('admin.tenant.apiKeys.columns.lastUsed')}</Table.Th>
              <Table.Th w={80}>{t('admin.tenant.apiKeys.columns.actions')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {keys.map(k => {
              const color = TYPE_COLOR[k.type] ?? 'gray'
              const typeLabel = t(`admin.tenant.apiKeys.types.${k.type}`, { defaultValue: k.type })
              return (
                <Table.Tr key={k.id}>
                  <Table.Td><Text size="sm" fw={500}>{k.name}</Text></Table.Td>
                  <Table.Td><Badge color={color} size="sm" variant="light">{typeLabel}</Badge></Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      <Text size="xs" ff="monospace" c="dimmed">{k.maskedKey}</Text>
                      <CopyButton value={k.maskedKey}>
                        {({ copied, copy }) => (
                          <Tooltip label={copied ? t('admin.tenant.apiKeys.copied') : t('admin.tenant.apiKeys.copy')}>
                            <ActionIcon variant="subtle" size="xs" onClick={copy} color={copied ? 'teal' : 'gray'}>
                              {copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
                            </ActionIcon>
                          </Tooltip>
                        )}
                      </CopyButton>
                    </Group>
                  </Table.Td>
                  <Table.Td><Text size="xs" c="dimmed">{k.createdAt}</Text></Table.Td>
                  <Table.Td><Text size="xs" c="dimmed">{k.lastUsedAt ?? '—'}</Text></Table.Td>
                  <Table.Td>
                    <Tooltip label={t('admin.tenant.apiKeys.revoke')}>
                      <ActionIcon variant="subtle" color="red" size="sm" onClick={() => void handleRevoke(k)}>
                        <IconTrash size={14} />
                      </ActionIcon>
                    </Tooltip>
                  </Table.Td>
                </Table.Tr>
              )
            })}
          </Table.Tbody>
        </Table>
      </Paper>

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
              <Text size="xs" c="red">{t('admin.tenant.apiKeys.storeWarning')}</Text>
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
