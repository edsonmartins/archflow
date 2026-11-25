import { useTranslation } from 'react-i18next'
import { NumberInput, Select, TextInput } from '@mantine/core'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { numberUpdater, storedChoice, storedNumber, storedText } from './helpers'
import type { FieldProps } from './FieldProps'
import { CONNECTION_FIELD_BY_BACKEND, serverConnectionPrefix } from '../../FlowCanvas/dataNodeKeys'

/** O campo de conexão do servidor de um backend (vector store ou memória). */
export function ServerConnectionField({ nodeData, update, backend }: FieldProps & { backend: string }) {
  const { t } = useTranslation()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const key = CONNECTION_FIELD_BY_BACKEND[backend]
  if (!key) return null
  return (
    <TextInput
      label={f('serverConnection')}
      description={f('serverConnectionHint', { value: serverConnectionPrefix(backend) })}
      withAsterisk
      value={storedText(nodeData, key)}
      onChange={e => update(key, e.currentTarget.value === '' ? undefined : e.currentTarget.value)}
      placeholder={f('serverConnectionPlaceholder')}
      size="xs"
      styles={MONO_INPUT}
    />
  )
}

/**
 * Backend de vetores e o que cada adapter precisa (vector-search, vector-store e o vector store do RAG sem base de
 * conhecimento). As chaves são traduzidas para as do adapter pela DesignerAdapterKeys; o
 * DesignerAdapterKeysConformanceTest lê este arquivo e falha se uma chave daqui não tiver tradução.
 *
 * <p>"padrão: X" só com o padrão do próprio adapter: tabela "embeddings" (PgVectorStoreAdapter), prefixo
 * "embedding:" (RedisVectorStoreAdapter) e índice "embeddings" (PineconeVectorStoreAdapter). A dimensão não tem
 * padrão — os três adapters a exigem.</p>
 */
export function VectorStoreFields({
  nodeData, update, backends, label,
}: FieldProps & { backends: { value: string; label: string }[]; label?: string }) {
  const { t } = useTranslation()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const ex = (value: unknown) => f('exampleValue', { value: String(value) })
  const dflt = (value: unknown) => f('defaultValue', { value: String(value) })
  const backend = storedChoice(nodeData, 'vectorBackend')

  return (
    <>
      <Select
        label={label ?? f('vectorBackend')}
        value={backend}
        onChange={v => update('vectorBackend', v ?? undefined)}
        size="xs"
        data={backends}
        styles={FIELD_STYLES}
      />

      {backend && <ServerConnectionField nodeData={nodeData} update={update} backend={backend} />}

      {backend === 'pgvector' && (
        <>
          <TextInput
            label={f('tableName')}
            value={storedText(nodeData, 'pgTableName')}
            placeholder={dflt('embeddings')}
            onChange={e => update('pgTableName', e.currentTarget.value === '' ? undefined : e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
          />
          <NumberInput
            label={f('dimensions')}
            withAsterisk
            value={storedNumber(nodeData, 'pgDimensions')}
            placeholder={ex(1536)}
            onChange={numberUpdater(update, 'pgDimensions')}
            min={1}
            max={4096}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {backend === 'redis' && (
        <TextInput
          label={f('keyPrefix')}
          value={storedText(nodeData, 'vectorRedisPrefix')}
          placeholder={dflt('embedding:')}
          onChange={e => update('vectorRedisPrefix', e.currentTarget.value === '' ? undefined : e.currentTarget.value)}
          size="xs"
          styles={MONO_INPUT}
        />
      )}

      {backend === 'pinecone' && (
        <TextInput
          label={f('indexName')}
          value={storedText(nodeData, 'pineconeIndex')}
          placeholder={dflt('embeddings')}
          onChange={e => update('pineconeIndex', e.currentTarget.value === '' ? undefined : e.currentTarget.value)}
          size="xs"
          styles={MONO_INPUT}
        />
      )}

      {(backend === 'redis' || backend === 'pinecone') && (
        <NumberInput
          label={f('dimensions')}
          withAsterisk
          value={storedNumber(nodeData, 'vectorDimensions')}
          placeholder={ex(1536)}
          onChange={numberUpdater(update, 'vectorDimensions')}
          min={1}
          max={4096}
          size="xs"
          styles={FIELD_STYLES}
        />
      )}
    </>
  )
}
