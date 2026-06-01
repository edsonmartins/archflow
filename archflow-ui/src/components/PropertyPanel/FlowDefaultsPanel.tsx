import { useTranslation } from 'react-i18next'
import { Select, NumberInput, Text, Divider, ScrollArea } from '@mantine/core'
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
        <div style={{ fontSize: 20 }}>{'◎'}</div>
        <div style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>{t('editor.properties.noNodeSelected')}</div>
        <div style={{ fontSize: 12 }}>{t('editor.properties.emptyHint')}</div>
      </div>
    )
  }

  const cfg = readLlmConfig(currentWorkflow.configuration)
  const f = (key: string) => t(`editor.properties.fields.${key}`)

  const modelGroups = (() => {
    const groups = new Map<string, { value: string; label: string }[]>()
    providers.forEach(p => {
      if (!groups.has(p.group)) groups.set(p.group, [])
      p.models.forEach(m => groups.get(p.group)!.push({ value: m.id, label: m.name }))
    })
    return Array.from(groups.entries()).map(([group, items]) => ({ group, items }))
  })()

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
