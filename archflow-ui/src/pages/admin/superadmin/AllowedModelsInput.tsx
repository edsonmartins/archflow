import { MultiSelect, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { globalConfigApi, type LLMModel } from '../../../services/admin-api'

/**
 * Modelos ligados do catálogo da plataforma (Configuração global), para escolher os permitidos de um
 * tenant. `null` enquanto carrega; `[]` quando a chamada falha — aí o campo ainda mostra e deixa
 * remover o que já está gravado, só não sugere nada novo.
 */
function useActiveModelCatalog(): { models: LLMModel[] | null; failed: boolean } {
  const [models, setModels] = useState<LLMModel[] | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let cancelled = false
    globalConfigApi.getModels()
      .then(list => { if (!cancelled) setModels((list ?? []).filter(m => m.status !== 'deprecated')) })
      .catch(() => { if (!cancelled) { setModels([]); setFailed(true) } })
    return () => { cancelled = true }
  }, [])
  return { models, failed }
}

interface AllowedModelsInputProps {
  value: string[]
  onChange: (value: string[]) => void
  label?: string
  disabled?: boolean
}

/**
 * Campo de modelos permitidos de um tenant (RCHFLW-79): busca no catálogo ativo, agrupado por
 * provider. Vazio = o tenant usa o catálogo inteiro (e pode digitar um id livre no nó); preenchido =
 * só estes, no seletor dos nós e na execução.
 *
 * <p>Um id gravado que saiu do catálogo continua aparecendo (para poder ser removido) — sumir com ele
 * esconderia uma restrição que o servidor ainda aplica.</p>
 */
export function AllowedModelsInput({ value, onChange, label, disabled }: AllowedModelsInputProps) {
  const { t } = useTranslation()
  const { models, failed } = useActiveModelCatalog()
  const k = (key: string) => t(`admin.superadmin.tenantDetail.${key}`)

  const groups = new Map<string, { value: string; label: string }[]>()
  const seen = new Set<string>()
  for (const m of models ?? []) {
    // O mesmo id em dois providers (gpt-4o na OpenAI e na Azure) é uma opção só: a restrição é por id.
    if (seen.has(m.id)) continue
    seen.add(m.id)
    const group = m.provider || '—'
    if (!groups.has(group)) groups.set(group, [])
    groups.get(group)!.push({ value: m.id, label: m.name && m.name !== m.id ? `${m.name} · ${m.id}` : m.id })
  }
  const orphans = value.filter(id => !seen.has(id)).map(id => ({ value: id, label: id }))
  const data = [
    ...Array.from(groups.entries()).map(([group, items]) => ({ group, items })),
    ...orphans,
  ]

  return (
    <div data-testid="allowed-models-input">
      <MultiSelect
        size="sm"
        label={label ?? k('allowedModels')}
        description={k('allowedModelsHint')}
        placeholder={value.length === 0 ? k('allowedModelsPlaceholder') : undefined}
        data={data}
        value={value}
        onChange={onChange}
        searchable
        clearable
        disabled={disabled || models === null}
        hidePickedOptions
      />
      {failed && <Text size="xs" c="red" mt={4}>{k('allowedModelsCatalogFailed')}</Text>}
    </div>
  )
}
