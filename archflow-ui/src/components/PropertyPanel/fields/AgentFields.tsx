import { useTranslation } from 'react-i18next'
import {
  Select, TextInput, Textarea, NumberInput, Divider,
  Accordion, Switch, TagsInput, Badge, Text,
} from '@mantine/core'
import type { FlowNodeData } from '../../FlowCanvas/types'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { useWorkflowConfig } from '../useWorkflowConfig'
import { useCatalog } from '../useCatalog'
import { getConfig, dedupeByValue, findProvider, findModel, ctxLabel } from './helpers'

interface AgentFieldsProps {
  nodeData: FlowNodeData
  update: (key: string, value: unknown) => void
  isAssistant: boolean
}

/**
 * Campos de configuração dos nós de agente/assistente (provider, modelo,
 * persona, pattern, governança, memória) — extraído do PropertyPanel
 * monolítico; o estado derivado vem dos mesmos hooks com cache de módulo.
 */
export function AgentFields({ nodeData, update, isAssistant }: AgentFieldsProps) {
  const { t } = useTranslation()
  const { providers, patterns, personas, governance } = useWorkflowConfig()
  const catalog = useCatalog()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)

  // Derived state for agent nodes
  const providerId = getConfig(nodeData, 'provider', providers[0]?.id ?? 'anthropic')
  const modelId    = getConfig(nodeData, 'model', providers[0]?.models[0]?.id ?? 'claude-sonnet-4-6')
  const provider   = findProvider(providers, providerId)
  const model      = findModel(providers, providerId, modelId)
  const maxTemp    = model?.maxTemperature ?? 2.0
  const ctxWindow  = model?.contextWindow ?? 128000

  // Persona selector options ("custom" = free-text system prompt)
  const personaOptions = [
    { value: '__custom__', label: t('common.customFreeText') },
    ...personas.map(p => ({ value: p.id, label: p.label })),
  ]
  const personaId = getConfig(nodeData, 'personaId', '__custom__')

  // Governance profile selector
  const governanceOptions = [
    { value: '__custom__', label: t('common.custom') },
    ...governance.map(g => ({ value: g.id, label: g.name })),
  ]

  return (
    <>
        <>
          {/* Concrete agent / assistant type from the catalog */}
          {(() => {
            const typedItems = isAssistant ? catalog.assistants : catalog.agents
            const configKey = isAssistant ? 'assistantTypeId' : 'agentTypeId'
            if (typedItems.length === 0) return null
            return (
              <div>
                <Select
                  label={isAssistant ? f('assistantType') : f('agentType')}
                  value={getConfig<string | null>(nodeData, configKey, null) ?? typedItems[0].id}
                  onChange={v => update(configKey, v)}
                  size="xs"
                  data={typedItems.map(a => ({ value: a.id, label: a.displayName }))}
                  styles={FIELD_STYLES}
                  searchable
                />
                <Text size="xs" c="dimmed" mt={4} lh={1.4}>
                  {typedItems.find(a => a.id === getConfig<string | null>(nodeData, configKey, typedItems[0].id))?.description}
                </Text>
              </div>
            )
          })()}

          {/* Gap 1: Agent pattern */}
          <div>
            <Select
              label={f('executionStrategy')}
              value={getConfig(nodeData, 'agentPattern', 'react')}
              onChange={v => update('agentPattern', v)}
              size="xs"
              data={patterns.map(p => ({ value: p.id, label: p.label }))}
              styles={FIELD_STYLES}
            />
            <Text size="xs" c="dimmed" mt={4} lh={1.4}>
              {patterns.find(p => p.id === getConfig(nodeData, 'agentPattern', 'react'))?.description}
            </Text>
          </div>

          {/* Gap 2: Provider */}
          <Select
            label={f('provider')}
            value={providerId}
            onChange={v => {
              update('provider', v)
              const firstModel = providers.find(p => p.id === v)?.models[0]
              if (firstModel) update('model', firstModel.id)
            }}
            size="xs"
            data={(() => {
              const groups = new Map<string, { value: string; label: string }[]>()
              // Mantine 9 rejects duplicate option values — guard against a
              // catalog that lists the same provider id more than once.
              const seen = new Set<string>()
              providers.forEach(p => {
                if (seen.has(p.id)) return
                seen.add(p.id)
                if (!groups.has(p.group)) groups.set(p.group, [])
                groups.get(p.group)!.push({ value: p.id, label: p.displayName })
              })
              return Array.from(groups.entries()).map(([group, items]) => ({ group, items }))
            })()}
            styles={FIELD_STYLES}
          />

          {/* Gap 2: Model (filtered by provider) */}
          <div>
            <Select
              label={f('model')}
              value={modelId}
              onChange={v => update('model', v)}
              size="xs"
              data={dedupeByValue((provider?.models ?? []).map(m => ({
                value: m.id,
                label: `${m.name}`,
              })))}
              styles={FIELD_STYLES}
            />
            {model && (
              <Badge size="xs" variant="light" color="gray" mt={4}>{ctxLabel(model.contextWindow)}</Badge>
            )}
          </div>

          {/* Gap 10: Persona selector */}
          {personas.length > 0 && (
            <Select
              label={f('persona')}
              value={personaId}
              onChange={v => {
                update('personaId', v)
                if (v && v !== '__custom__') {
                  const p = personas.find(pp => pp.id === v)
                  if (p) update('systemPrompt', p.description)
                }
              }}
              size="xs"
              data={personaOptions}
              styles={FIELD_STYLES}
            />
          )}

          {/* System prompt */}
          <div>
            <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--color-text-secondary)', marginBottom: 5 }}>
              {f('systemPrompt')}
            </div>
            <Textarea
              value={getConfig(nodeData, 'systemPrompt', '')}
              onChange={e => update('systemPrompt', e.currentTarget.value)}
              placeholder={f('systemPromptPlaceholder')}
              minRows={3}
              maxRows={6}
              autosize
              disabled={personas.length > 0 && personaId !== '__custom__'}
              size="xs"
              styles={{ input: { fontSize: 12, fontFamily: 'var(--font-mono)' } }}
            />
          </div>

          {/* Temperature */}
          <NumberInput
            label={f('temperature')}
            value={getConfig(nodeData, 'temperature', 0.7)}
            onChange={v => update('temperature', v)}
            min={0}
            max={maxTemp}
            step={0.1}
            decimalScale={1}
            size="xs"
            styles={FIELD_STYLES}
          />

          {/* Gap 3: Max tokens */}
          <NumberInput
            label={f('maxTokens')}
            value={getConfig(nodeData, 'maxTokens', 4096)}
            onChange={v => update('maxTokens', v)}
            min={1}
            max={ctxWindow}
            step={256}
            size="xs"
            styles={FIELD_STYLES}
          />

          {/* Max iterations */}
          <NumberInput
            label={f('maxIterations')}
            value={getConfig(nodeData, 'maxIterations', 10)}
            onChange={v => update('maxIterations', v)}
            min={1}
            max={50}
            size="xs"
            styles={FIELD_STYLES}
          />

          <Divider my={4} />

          {/* ── Advanced accordion ───────────────────────────── */}
          <Accordion variant="contained" radius="sm" styles={{ control: { fontSize: 12, padding: '6px 10px' }, content: { padding: '8px 10px' } }}>
            <Accordion.Item value="advanced">
              <Accordion.Control>{f('advanced')}</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {/* Gap 4: Timeout */}
                  <NumberInput
                    label={f('timeoutSec')}
                    value={getConfig(nodeData, 'timeout', 120)}
                    onChange={v => update('timeout', v)}
                    min={1}
                    max={3600}
                    size="xs"
                    styles={FIELD_STYLES}
                  />

                  {/* Gap 5: Memory backend */}
                  <Select
                    label={f('memoryBackend')}
                    value={getConfig(nodeData, 'memoryBackend', 'in-memory')}
                    onChange={v => update('memoryBackend', v)}
                    size="xs"
                    data={[
                      { value: 'in-memory', label: f('memoryOptions.in-memory') },
                      { value: 'redis',     label: f('memoryOptions.redis') },
                      { value: 'jdbc',      label: f('memoryOptions.jdbc') },
                    ]}
                    styles={FIELD_STYLES}
                  />

                  {getConfig(nodeData, 'memoryBackend', 'in-memory') === 'redis' && (
                    <TextInput
                      label={f('redisUrl')}
                      value={getConfig(nodeData, 'redisUrl', 'redis://localhost:6379')}
                      onChange={e => update('redisUrl', e.currentTarget.value)}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}

                  {getConfig(nodeData, 'memoryBackend', 'in-memory') === 'jdbc' && (
                    <TextInput
                      label={f('jdbcDatasource')}
                      value={getConfig(nodeData, 'jdbcDatasource', '')}
                      onChange={e => update('jdbcDatasource', e.currentTarget.value)}
                      placeholder={f('datasourceNamePlaceholder')}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                </div>
              </Accordion.Panel>
            </Accordion.Item>

            {/* ── Gap 9 + 12: Governance + Confidence ──────── */}
            <Accordion.Item value="governance">
              <Accordion.Control>{f('governance')}</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {governance.length > 0 && (
                    <Select
                      label={f('profile')}
                      value={getConfig(nodeData, 'governanceProfileId', '__custom__')}
                      onChange={v => {
                        update('governanceProfileId', v)
                        if (v && v !== '__custom__') {
                          const g = governance.find(gg => gg.id === v)
                          if (g) {
                            update('enabledTools', g.enabledTools)
                            update('disabledTools', g.disabledTools)
                            update('escalationThreshold', g.escalationThreshold)
                            update('maxToolExecutions', g.maxToolExecutions)
                            update('customInstructions', g.customInstructions)
                          }
                        }
                      }}
                      size="xs"
                      data={governanceOptions}
                      styles={FIELD_STYLES}
                    />
                  )}
                  <TagsInput
                    label={f('enabledTools')}
                    value={getConfig<string[]>(nodeData, 'enabledTools', [])}
                    onChange={v => update('enabledTools', v)}
                    placeholder={f('addTool')}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <TagsInput
                    label={f('disabledTools')}
                    value={getConfig<string[]>(nodeData, 'disabledTools', [])}
                    onChange={v => update('disabledTools', v)}
                    placeholder={f('addTool')}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <NumberInput
                    label={f('escalationThreshold')}
                    description={f('escalationHint')}
                    value={getConfig(nodeData, 'escalationThreshold', 0.5)}
                    onChange={v => update('escalationThreshold', v)}
                    min={0}
                    max={1}
                    step={0.05}
                    decimalScale={2}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <NumberInput
                    label={f('maxToolExecutions')}
                    value={getConfig(nodeData, 'maxToolExecutions', 10)}
                    onChange={v => update('maxToolExecutions', v)}
                    min={1}
                    max={100}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <Switch
                    label={f('humanEscalation')}
                    checked={getConfig(nodeData, 'humanEscalation', false)}
                    onChange={e => update('humanEscalation', e.currentTarget.checked)}
                    size="xs"
                    styles={{ label: { fontSize: 12 } }}
                  />
                  <Textarea
                    label={f('customInstructions')}
                    value={getConfig(nodeData, 'customInstructions', '')}
                    onChange={e => update('customInstructions', e.currentTarget.value)}
                    placeholder={f('customInstructionsPlaceholder')}
                    minRows={2}
                    maxRows={4}
                    autosize
                    size="xs"
                    styles={{ input: { fontSize: 12 }, label: { fontSize: 11, fontWeight: 500 } }}
                  />
                </div>
              </Accordion.Panel>
            </Accordion.Item>

            {/* ── Gap 7: MCP Servers ──────────────────────── */}
            <Accordion.Item value="mcp">
              <Accordion.Control>{f('mcpServers')}</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  <TagsInput
                    label={f('connectedServers')}
                    value={getConfig<string[]>(nodeData, 'mcpServers', [])}
                    onChange={v => update('mcpServers', v)}
                    placeholder={f('addServer')}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <Select
                    label={f('transport')}
                    value={getConfig(nodeData, 'mcpTransport', 'stdio')}
                    onChange={v => update('mcpTransport', v)}
                    size="xs"
                    data={[
                      { value: 'stdio', label: f('transportOptions.stdio') },
                      { value: 'sse',   label: f('transportOptions.sse') },
                    ]}
                    styles={FIELD_STYLES}
                  />
                  {getConfig(nodeData, 'mcpTransport', 'stdio') === 'stdio' && (
                    <TextInput
                      label={f('command')}
                      value={getConfig(nodeData, 'mcpCommand', '')}
                      onChange={e => update('mcpCommand', e.currentTarget.value)}
                      placeholder={f('commandPlaceholder')}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                  {getConfig(nodeData, 'mcpTransport', 'stdio') === 'sse' && (
                    <TextInput
                      label={f('serverUrl')}
                      value={getConfig(nodeData, 'mcpUrl', '')}
                      onChange={e => update('mcpUrl', e.currentTarget.value)}
                      placeholder={f('serverUrlPlaceholder')}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                </div>
              </Accordion.Panel>
            </Accordion.Item>
          </Accordion>
        </>
    </>
  )
}
