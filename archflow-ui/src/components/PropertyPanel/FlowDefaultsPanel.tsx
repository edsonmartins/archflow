import { useTranslation } from 'react-i18next'
import { Select, NumberInput, Text, Divider, ScrollArea } from '@mantine/core'
import { IconClick } from '@tabler/icons-react'
import { useWorkflowStore } from '../../stores/workflow-store'
import { useWorkflowConfig } from './useWorkflowConfig'
import { FIELD_STYLES } from './fieldStyles'

/**
 * Flow-level LLM defaults — the "flow" tier of the model inheritance chain
 * (step {@literal >} agent {@literal >} flow {@literal >} tenant {@literal >}
 * platform). Shown in the PropertyPanel when no node is selected. Mirrors the
 * backend {@code LLMConfig} (model / temperature / maxTokens / timeout); a step
 * that omits any of these inherits the value set here.
 */
function readLlmConfig(configuration: unknown): Record<string, unknown> {
  const cfg = (configuration ?? {}) as Record<string, unknown>
  return (cfg.llmConfig as Record<string, unknown>) ?? {}
}

export function FlowDefaultsPanel() {
  const { t } = useTranslation()
  const currentWorkflow = useWorkflowStore(s => s.currentWorkflow)
  const patchFlowLlmConfig = useWorkflowStore(s => s.patchFlowLlmConfig)
  const { providers } = useWorkflowConfig()

  // No workflow loaded — fall back to the original "nothing selected" hint.
  if (!currentWorkflow) {
    return (
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 8, color: 'var(--color-text-tertiary)', padding: 24, textAlign: 'center' }}>
        <IconClick size={22} stroke={1.5} aria-hidden />

        <div style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>{t('editor.properties.noNodeSelected')}</div>
        <div style={{ fontSize: 12 }}>{t('editor.properties.emptyHint')}</div>
      </div>
    )
  }

  const cfg = readLlmConfig(currentWorkflow.configuration)
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const savedModel = typeof cfg.model === 'string' ? cfg.model.trim() : ''
  // Tenant com restrição de modelos: o servidor manda allowCustomModel=false (ver AgentFields).
  const restrito = providers.some(p => p.allowCustomModel === false)

  const modelGroups = (() => {
    const groups = new Map<string, { value: string; label: string }[]>()
    // The backend catalog can expose the same model id under more than one
    // provider/group (e.g. gpt-4o via OpenAI and OpenRouter). Mantine 9 throws
    // on duplicate option values, so keep only the first occurrence of each id.
    const seen = new Set<string>()
    providers.forEach(p => {
      if (!groups.has(p.group)) groups.set(p.group, [])
      p.models.forEach(m => {
        if (seen.has(m.id)) return
        seen.add(m.id)
        groups.get(p.group)!.push({ value: m.id, label: m.name })
      })
    })
    const result = Array.from(groups.entries())
      .map(([group, items]) => ({ group, items }))
      .filter(g => g.items.length > 0)
    // A lista é o catálogo ativo ∩ os permitidos do tenant (RCHFLW-79), não mais o enum inteiro: um
    // padrão gravado antes (id antigo, catálogo da variável, tenant restrito) sumia do campo — o painel
    // mostrava "—" enquanto todo nó que herda rodava com ele (ou falhava com [LLM-MODELO]). Ele aparece
    // como está, marcado quando o tenant não o permite, como no seletor do nó.
    if (savedModel && !seen.has(savedModel)) {
      result.push({
        group: t('editor.properties.flowDefaults.savedGroup'),
        items: [{ value: savedModel, label: restrito ? f('modelNotAllowed', { model: savedModel }) : savedModel }],
      })
    }
    return result
  })()
  const savedNotAllowed = restrito && savedModel !== '' && !providers.some(p => p.models.some(m => m.id === savedModel))

  return (
    <ScrollArea style={{ height: '100%' }} p="md">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>
            {t('editor.properties.flowDefaults.title')}
          </div>
          <Text size="xs" c="dimmed" mt={4} lh={1.4}>
            {t('editor.properties.flowDefaults.hint')}
          </Text>
        </div>

        <Divider />

        <Select
          label={f('model')}
          placeholder="—"
          value={(cfg.model as string) ?? null}
          onChange={v => patchFlowLlmConfig({ model: v })}
          data={modelGroups}
          size="xs"
          clearable
          searchable
          styles={FIELD_STYLES}
        />
        {savedNotAllowed && (
          <Text size="xs" c="red" mt={-8} lh={1.4}>{f('modelNotAllowedHint')}</Text>
        )}

        <NumberInput
          label={f('temperature')}
          value={(cfg.temperature as number) ?? 0.2}
          onChange={v => patchFlowLlmConfig({ temperature: v })}
          min={0}
          max={2}
          step={0.1}
          decimalScale={1}
          size="xs"
          styles={FIELD_STYLES}
        />

        <NumberInput
          label={f('maxTokens')}
          value={(cfg.maxTokens as number) ?? 1024}
          onChange={v => patchFlowLlmConfig({ maxTokens: v })}
          min={1}
          step={256}
          size="xs"
          styles={FIELD_STYLES}
        />

        <NumberInput
          label={f('timeoutSec')}
          value={(cfg.timeout as number) ?? 30}
          onChange={v => patchFlowLlmConfig({ timeout: v })}
          min={1}
          step={5}
          size="xs"
          styles={FIELD_STYLES}
        />

        <Text size="xs" c="dimmed" lh={1.4}>
          {t('editor.properties.flowDefaults.inheritHint')}
        </Text>
      </div>
    </ScrollArea>
  )
}
