import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Select, TextInput, Textarea, NumberInput, Divider,
  Accordion, Switch, TagsInput, Badge, Text,
  type ComboboxItem, type OptionsFilter,
} from '@mantine/core'
import type { FlowNodeData } from '../../FlowCanvas/types'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { useWorkflowConfig } from '../useWorkflowConfig'
import { useCatalog } from '../useCatalog'
import { useChatProviders } from '../useChatProviders'
import { useWorkflowStore } from '../../../stores/workflow-store'
import { UpstreamRefs } from './NodeReferences'
import type { ProviderInfo } from '../../../services/workflow-config-api'
import {
  getConfig, dedupeByValue, findModel, ctxLabel, INHERIT,
  numberUpdater, storedChoice, storedFlag, storedNumber, storedText,
} from './helpers'

function texto(v: unknown): string {
  return typeof v === 'string' ? v.trim() : ''
}

/**
 * Busca do seletor de modelo pelo nome OU pelo id: o catálogo mostra o nome ("GPT-4o (OpenRouter)"),
 * mas quem conhece o provider digita o id ("openai/gpt-4o") — e, sem isto, o id existente não
 * aparecia e o painel ofereceu "usar o id" para um modelo que já estava na lista.
 */
const filtrarModelos: OptionsFilter = ({ options, search }) => {
  const q = search.trim().toLowerCase()
  if (!q) return options
  return (options as ComboboxItem[]).filter(o =>
    o.label.toLowerCase().includes(q) || o.value.toLowerCase().includes(q))
}

interface AgentFieldsProps {
  nodeData: FlowNodeData
  update: (key: string, value: unknown) => void
  isAssistant: boolean
  /** O id do nó: as referências dos nós anteriores da "Mensagem do usuário" partem dele. */
  nodeId?: string
}

/**
 * Campos de configuração dos nós de agente/assistente (provider, modelo,
 * persona, pattern, governança, memória) — extraído do PropertyPanel
 * monolítico; o estado derivado vem dos mesmos hooks com cache de módulo.
 */
export function AgentFields({ nodeData, update, isAssistant, nodeId }: AgentFieldsProps) {
  const { t } = useTranslation()
  const { providers, patterns, personas, governance } = useWorkflowConfig()
  const catalog = useCatalog()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  // Só o gravado, com o exemplo no placeholder (RCHFLW-92): os campos do laço de agente não têm execução no
  // servidor (RCHFLW-11), então nenhum valor aqui é "padrão" de nada.
  const ex = (value: unknown) => f('exampleValue', { value: String(value) })
  // llm-chat / llm-streaming são uma chamada de chat a um provider (adapter SPI), não um agente MCP: não
  // têm tipo de agente, estratégia ReAct, laço de iterações, governança de ferramentas nem servidores MCP.
  // Mostrar esses campos fazia o usuário configurar coisas que o nó ignora — então ficam só os de chat.
  const isChat = nodeData.nodeType === 'llm-chat' || nodeData.nodeType === 'llm-streaming'

  const chatProviders = useChatProviders()
  const currentWorkflow = useWorkflowStore(s => s.currentWorkflow)

  // O que está GRAVADO no nó — sem fallback para providers[0]/models[0]. Antes o painel mostrava
  // "OpenAI / GPT-4o" num nó sem provider e não gravava nada: o usuário via uma escolha que o
  // documento não tinha, e o backend rodava (ou falhava) com outra coisa (RCHFLW-16).
  const providerId = texto(nodeData.config?.provider)
  const modelId    = texto(nodeData.config?.model)

  // Nós de chat só executam com provider que tenha adapter de chat no servidor (RCHFLW-43). Os
  // agentes passam pelo provider-hub, que fala com todos — para eles a lista é a inteira.
  // Tenant com restrição de modelos (RCHFLW-79): o servidor manda allowCustomModel=false. Aí só vale a
  // lista — catálogo ativo ∩ permitidos do tenant — e a execução recusa qualquer outro id.
  const restrito = providers.some(p => p.allowCustomModel === false)
  const chatIds = isChat && chatProviders ? new Set(chatProviders.map(c => c.id)) : null
  const offered: ProviderInfo[] = chatIds
    ? [
        ...providers.filter(p => chatIds.has(p.id)),
        // Adapter de chat que o enum de providers não conhece (plugin): entra, sem modelos sugeridos.
        ...chatProviders!.filter(c => !providers.some(p => p.id === c.id)).map(c => ({
          id: c.id, displayName: c.displayName || c.id, requiresApiKey: true, supportsStreaming: false,
          group: 'Cloud' as const, models: [], allowCustomModel: !restrito,
        })),
      ]
    : providers
  // Com restrição, um provider sem nenhum modelo permitido não leva a lugar nenhum: sai do dropdown (o
  // gravado no nó continua aparecendo, como "extra").
  const selectable = restrito ? offered.filter(p => p.models.length > 0) : offered

  // "Padrão do fluxo (x)" quando o fluxo declara configuration.llmConfig.provider; senão, o da plataforma.
  const flowLlm = ((currentWorkflow?.configuration ?? {}) as Record<string, unknown>).llmConfig as
    Record<string, unknown> | undefined
  const flowProvider = texto(flowLlm?.provider)
  const platformProvider = providers.find(p => p.platformDefault)
  const inheritLabel = flowProvider
    ? f('inheritFlow', { provider: flowProvider })
    : platformProvider ? f('inheritPlatformNamed', { provider: platformProvider.displayName }) : f('inheritPlatform')

  // Sem provider no nó, os modelos sugeridos são os do provider herdado (fluxo, senão plataforma): o nó
  // pode herdar o provider e escolher o modelo, e a lista certa é a desse provider.
  const effectiveProviderId = providerId || flowProvider || platformProvider?.id || ''
  const provider   = offered.find(p => p.id === effectiveProviderId)
  const model      = findModel(offered, effectiveProviderId, modelId)
  // 0 = o servidor não conhece o número (id que só o catálogo da plataforma tem): usa o teto genérico.
  const maxTemp    = model?.maxTemperature || 2.0
  const ctxWindow  = model?.contextWindow || 128000
  // Um provider gravado que este servidor não executa em nó de chat (ex.: "gemini" de antes do filtro).
  const providerSemAdapter = chatIds !== null && providerId !== '' && !chatIds.has(providerId)

  // Modelo: lista do catálogo, e id livre só sem restrição.
  const allowCustom = provider ? provider.allowCustomModel !== false : !restrito
  const modelOptions = provider?.models ?? []
  const modelProibido = modelId !== '' && !allowCustom && !modelOptions.some(m => m.id === modelId)

  // O que um nó SEM modelo herda — a regra do backend: nos nós de chat (ChatNodeDefaults) o modelo do
  // fluxo, senão o da plataforma, e só quando o provider do nó é o herdado (outro provider fica com o
  // default do adapter, que ninguém escolheu); nos agentes o resolver põe o do fluxo por cima do da
  // plataforma. Num tenant restrito, herdar um modelo fora da lista — ou nenhum — falha na execução com
  // [LLM-MODELO]; sem este aviso o nó parecia válido no painel. `undefined` = não se sabe (servidor que
  // não manda o defaultModel): aí não se acusa nada.
  const modeloHerdado = ((): string | undefined => {
    if (modelId !== '') return undefined
    const flowModel = texto(flowLlm?.model)
    const inheritedProvider = flowProvider || platformProvider?.id || ''
    if (isChat && providerId !== '' && providerId !== inheritedProvider) return ''
    if (flowModel !== '') return flowModel
    if (isChat && inheritedProvider !== platformProvider?.id) return ''
    return platformProvider?.defaultModel ? platformProvider.defaultModel.trim() : undefined
  })()
  const permitidos = new Set(providers.flatMap(p => p.models.map(m => m.id)))
  const herdadoProibido = restrito && modeloHerdado !== undefined
    && (modeloHerdado === '' || !permitidos.has(modeloHerdado))
  const [buscaModelo, setBuscaModelo] = useState('')

  // Temperatura e teto de tokens: mostra o que está GRAVADO, ou vazio com o herdado no placeholder.
  // Antes o campo mostrava 0.7 / 4096 sem gravar, e o nó rodava com o llmConfig do fluxo (ou o
  // default do adapter — 2048 no OpenRouter): o painel dizia uma coisa e a execução fazia outra.
  const numero = (key: string): number | '' => {
    const v = nodeData.config?.[key]
    return typeof v === 'number' && Number.isFinite(v) ? v : ''
  }
  const herdado = (key: string): string => {
    const v = flowLlm?.[key]
    return typeof v === 'number' ? f('inheritFlowValue', { value: v }) : f('modelDefault')
  }
  // Campo apagado = herdar: remove a chave. Gravar '' fazia o adapter receber um texto onde lê um
  // número. Texto parcial ("0.") não é gravado — espera o número.
  const gravarNumero = (key: string) => (v: number | string) => {
    if (typeof v === 'number') update(key, v)
    else if (v === '') update(key, undefined)
  }

  // Persona selector options ("custom" = free-text system prompt)
  const personaOptions = [
    { value: '__custom__', label: t('common.customFreeText') },
    ...personas.map(p => ({ value: p.id, label: p.label })),
  ]
  // '__custom__' não é um valor de exibição: é a opção "sem persona" (prompt livre), o mesmo que nada gravado.
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
          {!isChat && (() => {
            const typedItems = isAssistant ? catalog.assistants : catalog.agents
            const configKey = isAssistant ? 'assistantTypeId' : 'agentTypeId'
            if (typedItems.length === 0) return null
            return (
              <div>
                <Select
                  label={isAssistant ? f('assistantType') : f('agentType')}
                  value={storedChoice(nodeData, configKey)}
                  onChange={v => update(configKey, v ?? undefined)}
                  size="xs"
                  data={typedItems.map(a => ({ value: a.id, label: a.displayName }))}
                  styles={FIELD_STYLES}
                  searchable
                />
                <Text size="xs" c="dimmed" mt={4} lh={1.4}>
                  {typedItems.find(a => a.id === storedChoice(nodeData, configKey))?.description}
                </Text>
              </div>
            )
          })()}

          {/* Gap 1: Agent pattern */}
          {!isChat && (
          <div data-testid="agent-fields-strategy">
            <Select
              label={f('executionStrategy')}
              value={storedChoice(nodeData, 'agentPattern')}
              onChange={v => update('agentPattern', v ?? undefined)}
              size="xs"
              data={patterns.map(p => ({ value: p.id, label: p.label }))}
              styles={FIELD_STYLES}
            />
            <Text size="xs" c="dimmed" mt={4} lh={1.4}>
              {patterns.find(p => p.id === storedChoice(nodeData, 'agentPattern'))?.description}
            </Text>
          </div>
          )}

          {/* Gap 2: Provider — "herdar" é uma opção explícita, e o que aparece é o que está gravado */}
          <div data-testid="agent-fields-provider">
            <Select
              label={f('provider')}
              value={providerId || INHERIT}
              // Clicar na opção já escolhida não pode desmarcá-la: vazio aqui significa "herdar", e o nó
              // perderia o provider sem o usuário pedir. Herdar é a opção explícita do topo da lista.
              allowDeselect={false}
              onChange={v => {
                if (!v || v === INHERIT) {
                  // Herdar = remover as chaves; modelo de outro provider não pode ficar para trás.
                  update('provider', undefined)
                  update('model', undefined)
                  return
                }
                update('provider', v)
                update('model', offered.find(p => p.id === v)?.models[0]?.id)
              }}
              size="xs"
              data={(() => {
                const groups = new Map<string, { value: string; label: string }[]>()
                // Mantine 9 rejects duplicate option values — guard against a
                // catalog that lists the same provider id more than once.
                const seen = new Set<string>([INHERIT])
                selectable.forEach(p => {
                  if (seen.has(p.id)) return
                  seen.add(p.id)
                  if (!groups.has(p.group)) groups.set(p.group, [])
                  groups.get(p.group)!.push({ value: p.id, label: p.displayName })
                })
                const extra = providerId && !seen.has(providerId)
                  ? [{ value: providerId, label: providerSemAdapter ? f('noChatAdapter', { provider: providerId }) : providerId }]
                  : []
                return [
                  { value: INHERIT, label: inheritLabel },
                  ...Array.from(groups.entries()).map(([group, items]) => ({ group, items })),
                  ...extra,
                ]
              })()}
              styles={FIELD_STYLES}
            />
            {!providerId && (
              <Text size="xs" c="dimmed" mt={4} lh={1.4}>{f('inheritHint')}</Text>
            )}
            {providerSemAdapter && (
              <Text size="xs" c="red" mt={4} lh={1.4}>{f('noChatAdapterHint')}</Text>
            )}
          </div>

          {/* Gap 2: Model — catálogo da plataforma ∩ permitidos do tenant (RCHFLW-79), com busca */}
          <div data-testid="agent-fields-model">
            <Select
              label={f('model')}
              value={modelId || INHERIT}
              allowDeselect={false}
              onChange={v => update('model', !v || v === INHERIT ? undefined : v)}
              size="xs"
              searchable
              searchValue={buscaModelo}
              onSearchChange={setBuscaModelo}
              filter={filtrarModelos}
              nothingFoundMessage={f('modelNothingFound')}
              data={(() => {
                const base = dedupeByValue([
                  { value: INHERIT, label: f('modelDefault') },
                  ...modelOptions.map(m => ({ value: m.id, label: m.name || m.id })),
                  // Um modelo gravado fora da lista (YAML/API) aparece como está, em vez de sumir — marcado
                  // quando o tenant não o permite.
                  ...(modelId ? [{ value: modelId, label: modelProibido ? f('modelNotAllowed', { model: modelId }) : modelId }] : []),
                ])
                // Id livre: só sem restrição, e só quando o texto não é uma opção (nem o rótulo da escolhida,
                // que o Mantine põe na busca ao selecionar).
                const digitado = buscaModelo.trim()
                const novo = allowCustom && digitado !== '' && digitado !== INHERIT
                  && !base.some(o => o.value === digitado || o.label === digitado)
                return novo ? [...base, { value: digitado, label: f('useCustomModel', { model: digitado }) }] : base
              })()}
              styles={FIELD_STYLES}
            />
            {model && model.contextWindow > 0 && (
              <Badge size="xs" variant="light" color="gray" mt={4}>{ctxLabel(model.contextWindow)}</Badge>
            )}
            {modelProibido ? (
              <Text size="xs" c="red" mt={4} lh={1.4}>{f('modelNotAllowedHint')}</Text>
            ) : herdadoProibido ? (
              <Text size="xs" c="red" mt={4} lh={1.4}>
                {modeloHerdado ? f('modelInheritedNotAllowed', { model: modeloHerdado }) : f('modelInheritedMissing')}
              </Text>
            ) : (
              <Text size="xs" c="dimmed" mt={4} lh={1.4}>{allowCustom ? f('modelSearchHint') : f('modelRestrictedHint')}</Text>
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

          {/* Mensagem do usuário (RCHFLW-120): sem ela o LLM recebe a saída do passo anterior — depois de uma
              Decisão ou Condição, o JSON da classificação. As referências seguem as regras das condições, e o
              Executar e o servidor recusam a que nunca funciona (controlExpressions / ControlExpressionRules). */}
          {isChat && (
            <div data-testid="panel-user-message">
              <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--color-text-secondary)', marginBottom: 5 }}>
                {f('userMessage')}
              </div>
              <Textarea
                value={storedText(nodeData, 'userMessage')}
                onChange={e => update('userMessage', e.currentTarget.value)}
                placeholder={f('userMessagePlaceholder')}
                minRows={1}
                maxRows={4}
                autosize
                size="xs"
                styles={{ input: { fontSize: 12, fontFamily: 'var(--font-mono)' } }}
              />
              <Text size="xs" c="dimmed" mt={4} lh={1.4} data-testid="panel-user-message-hint">{f('userMessageHint')}</Text>
              {nodeId && (
                <div style={{ marginTop: 6 }}>
                  <UpstreamRefs nodeId={nodeId} onInsert={snippet => {
                    const atual = storedText(nodeData, 'userMessage')
                    update('userMessage', atual.trim() === '' ? snippet : `${atual.replace(/\s+$/, '')} ${snippet}`)
                  }} />
                </div>
              )}
            </div>
          )}

          {/* Temperature */}
          <NumberInput
            label={f('temperature')}
            value={numero('temperature')}
            placeholder={herdado('temperature')}
            onChange={gravarNumero('temperature')}
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
            value={numero('maxTokens')}
            placeholder={herdado('maxTokens')}
            onChange={gravarNumero('maxTokens')}
            min={1}
            max={ctxWindow}
            step={256}
            size="xs"
            styles={FIELD_STYLES}
          />

          {/* Max iterations */}
          {!isChat && (
          <NumberInput
            label={f('maxIterations')}
            value={storedNumber(nodeData, 'maxIterations')}
            placeholder={ex(10)}
            onChange={numberUpdater(update, 'maxIterations')}
            min={1}
            max={50}
            size="xs"
            styles={FIELD_STYLES}
          />
          )}

          {!isChat && <Divider my={4} />}

          {/* ── Advanced accordion ───────────────────────────── */}
          {!isChat && (
          <Accordion variant="contained" radius="sm" styles={{ control: { fontSize: 12, padding: '6px 10px' }, content: { padding: '8px 10px' } }}>
            <Accordion.Item value="advanced">
              <Accordion.Control>{f('advanced')}</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {/* Gap 4: Timeout */}
                  <NumberInput
                    label={f('timeoutSec')}
                    value={storedNumber(nodeData, 'timeout')}
                    placeholder={ex(120)}
                    onChange={numberUpdater(update, 'timeout')}
                    min={1}
                    max={3600}
                    size="xs"
                    styles={FIELD_STYLES}
                  />

                  {/* Gap 5: Memory backend */}
                  <Select
                    label={f('memoryBackend')}
                    value={storedChoice(nodeData, 'memoryBackend')}
                    onChange={v => update('memoryBackend', v ?? undefined)}
                    size="xs"
                    data={[
                      { value: 'in-memory', label: f('memoryOptions.in-memory') },
                      { value: 'redis',     label: f('memoryOptions.redis') },
                      { value: 'jdbc',      label: f('memoryOptions.jdbc') },
                    ]}
                    styles={FIELD_STYLES}
                  />

                  {storedChoice(nodeData, 'memoryBackend') === 'redis' && (
                    <TextInput
                      label={f('redisUrl')}
                      value={storedText(nodeData, 'redisUrl')}
                      placeholder={ex('redis://localhost:6379')}
                      onChange={e => update('redisUrl', e.currentTarget.value)}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}

                  {storedChoice(nodeData, 'memoryBackend') === 'jdbc' && (
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
                    value={storedNumber(nodeData, 'escalationThreshold')}
                    placeholder={ex(0.5)}
                    onChange={numberUpdater(update, 'escalationThreshold')}
                    min={0}
                    max={1}
                    step={0.05}
                    decimalScale={2}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <NumberInput
                    label={f('maxToolExecutions')}
                    value={storedNumber(nodeData, 'maxToolExecutions')}
                    placeholder={ex(10)}
                    onChange={numberUpdater(update, 'maxToolExecutions')}
                    min={1}
                    max={100}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <Switch
                    label={f('humanEscalation')}
                    checked={storedFlag(nodeData, 'humanEscalation')}
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
                    value={storedChoice(nodeData, 'mcpTransport')}
                    onChange={v => update('mcpTransport', v ?? undefined)}
                    size="xs"
                    data={[
                      { value: 'stdio', label: f('transportOptions.stdio') },
                      { value: 'sse',   label: f('transportOptions.sse') },
                    ]}
                    styles={FIELD_STYLES}
                  />
                  {storedChoice(nodeData, 'mcpTransport') === 'stdio' && (
                    <TextInput
                      label={f('command')}
                      value={getConfig(nodeData, 'mcpCommand', '')}
                      onChange={e => update('mcpCommand', e.currentTarget.value)}
                      placeholder={f('commandPlaceholder')}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                  {storedChoice(nodeData, 'mcpTransport') === 'sse' && (
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
          )}
        </>
    </>
  )
}
