import { useTranslation } from 'react-i18next'
import { Select, NumberInput, Text, Divider, ScrollArea } from '@mantine/core'
import { IconClick } from '@tabler/icons-react'
import { useWorkflowStore } from '../../stores/workflow-store'
import { useWorkflowConfig } from './useWorkflowConfig'
import { FIELD_STYLES } from './fieldStyles'
import { ReadOnlyFieldset } from './ReadOnlyFieldset'

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

/** `readOnly`: sem workflow:update (RCHFLW-74) os padrões aparecem, mas não mudam — ver PropertyPanel. */
export function FlowDefaultsPanel({ readOnly = false }: { readOnly?: boolean } = {}) {
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

  // O modelo do fluxo só vale para o provider que os nós herdam — configuration.llmConfig.provider, senão o da
  // plataforma (ChatNodeDefaults e o resolver dos agentes) —, e o painel só grava o id. Ele oferecia os modelos de
  // todos os providers num grupo só: escolher "Claude 3 Haiku" gravava claude-3-haiku-20240307, que os nós mandavam
  // ao OpenRouter, e a execução falhava com "not a valid model ID" (RCHFLW-182). Agora só os do provider herdado,
  // como o seletor do nó já faz (AgentFields); provider desconhecido = nenhuma sugestão, sem chutar outro.
  const flowProvider = typeof cfg.provider === 'string' ? cfg.provider.trim() : ''
  const inheritedId = flowProvider || providers.find(p => p.platformDefault)?.id || ''
  const inherited = providers.find(p => p.id === inheritedId)
  const inheritedName = inherited?.displayName || inheritedId

  const modelGroups = (() => {
    // Mantine 9 recusa valores repetidos: o catálogo pode repetir um id no mesmo provider.
    const seen = new Set<string>()
    const items: { value: string; label: string }[] = []
    inherited?.models.forEach(m => {
      if (seen.has(m.id)) return
      seen.add(m.id)
      items.push({ value: m.id, label: m.name })
    })
    const result = items.length > 0 ? [{ group: inheritedName, items }] : []
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
  // Um padrão gravado antes desta correção com o id de OUTRO provider (o claude-3-haiku da Anthropic num fluxo que
  // herda o OpenRouter): aparece como está, avisando que os nós que herdam vão mandá-lo ao provider herdado. Só
  // quando o id é do catálogo de outro provider e não do herdado — um id livre pode ser válido no herdado.
  const savedOtherProvider = !savedNotAllowed && savedModel !== '' && inherited != null
    && !inherited.models.some(m => m.id === savedModel)
    && providers.find(p => p.id !== inherited.id && p.models.some(m => m.id === savedModel))
  // Campo numérico apagado = sem valor no fluxo: tira a chave, e cada nó usa o padrão dele (RCHFLW-188, item 23).
  const patchNumber = (key: string) => (v: number | string) => {
    if (typeof v === 'number') patchFlowLlmConfig({ [key]: v })
    else if (v === '') patchFlowLlmConfig({ [key]: undefined })
  }
  const stored = (key: string): number | '' => {
    const v = cfg[key]
    return typeof v === 'number' && Number.isFinite(v) ? v : ''
  }

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

        <ReadOnlyFieldset readOnly={readOnly}>
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
          {savedNotAllowed ? (
            <Text size="xs" c="red" mt={-8} lh={1.4}>{f('modelNotAllowedHint')}</Text>
          ) : savedOtherProvider ? (
            <Text size="xs" c="red" mt={-8} lh={1.4} data-testid="flow-model-other-provider">
              {t('editor.properties.flowDefaults.modelOtherProvider', {
                model: savedModel, provider: savedOtherProvider.displayName, inherited: inheritedName,
              })}
            </Text>
          ) : (
            <Text size="xs" c="dimmed" mt={-8} lh={1.4} data-testid="flow-model-provider-hint">
              {inheritedId
                ? t('editor.properties.flowDefaults.modelProviderHint', { provider: inheritedName })
                : t('editor.properties.flowDefaults.modelProviderUnknown')}
            </Text>
          )}

          {/* Só o gravado (RCHFLW-92). Antes os campos vazios mostravam 0.2 / 1024 / 30 — os padrões do caminho
              dos agentes —, enquanto os nós LLM Chat rodavam com os do adapter (0.7 / 2048 no OpenRouter): o painel
              dizia uma coisa e a execução fazia outra (RCHFLW-188, item 23). Vazio = cada nó usa o padrão dele. */}
          <NumberInput
            label={f('temperature')}
            value={stored('temperature')}
            placeholder={f('modelDefault')}
            onChange={patchNumber('temperature')}
            min={0}
            max={2}
            step={0.1}
            decimalScale={1}
            size="xs"
            styles={FIELD_STYLES}
          />

          <NumberInput
            label={f('maxTokens')}
            value={stored('maxTokens')}
            placeholder={f('modelDefault')}
            onChange={patchNumber('maxTokens')}
            min={1}
            step={256}
            size="xs"
            styles={FIELD_STYLES}
          />

          <NumberInput
            label={f('timeoutSec')}
            value={stored('timeout')}
            placeholder={f('modelDefault')}
            onChange={patchNumber('timeout')}
            min={1}
            step={5}
            size="xs"
            styles={FIELD_STYLES}
            // O tempo limite do fluxo só chega aos agentes (o provider hub o aplica); os adapters de chat não têm
            // essa chave, e um LLM Chat de 1 s rodava 14 s (RCHFLW-188, item 22). O campo diz a quem vale.
            description={t('editor.properties.flowDefaults.timeoutAgentsOnly')}
            data-testid="flow-timeout"
          />
        </ReadOnlyFieldset>

        <Text size="xs" c="dimmed" lh={1.4}>
          {t('editor.properties.flowDefaults.inheritHint')}
        </Text>
      </div>
    </ScrollArea>
  )
}
