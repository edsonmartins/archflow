import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NumberInput, Select, Text, TextInput, Textarea } from '@mantine/core'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { getConfig, storedChoice, storedNumber } from './helpers'
import type { FieldProps } from './FieldProps'

/**
 * Um campo cujo valor é JSON. Guarda o texto digitado à parte e só grava na config quando ele
 * parseia: gravar a cada tecla trocaria o objeto por lixo no meio da digitação — e perderia o que a
 * pessoa estava escrevendo.
 */
function JsonField({
  label, hint, value, onValid, minRows = 4, required = false,
}: {
  label: string
  hint?: string
  value: unknown
  onValid: (v: unknown) => void
  minRows?: number
  /** O motor recusa o nó sem o campo; o Executar do editor confere antes (runReadiness). */
  required?: boolean
}) {
  const { t } = useTranslation()
  const external = value === undefined ? '' : JSON.stringify(value, null, 2)
  const [draft, setDraft] = useState(external)
  const [error, setError] = useState<string | null>(null)

  // Se o valor mudar por fora (undo, outro nó selecionado), o texto acompanha.
  useEffect(() => { setDraft(external); setError(null) }, [external])

  return (
    <Textarea
      label={label}
      withAsterisk={required}
      description={hint}
      value={draft}
      onChange={e => {
        const text = e.currentTarget.value
        setDraft(text)
        if (text.trim() === '') { setError(null); onValid(undefined); return }
        try {
          onValid(JSON.parse(text))
          setError(null)
        } catch {
          setError(t('editor.properties.fields.decisionInvalidJson'))
        }
      }}
      error={error}
      autosize minRows={minRows}
      size="xs"
      styles={MONO_INPUT}
    />
  )
}

const PROVIDERS = [
  { value: 'http-decisions', label: 'http-decisions (OpenRouter Decisions API)' },
  { value: 'llm',            label: 'llm (LLM as classifier — not calibrated)' },
  { value: 'rules',          label: 'rules (deterministic)' },
]

function semChave(obj: Record<string, unknown>, chave: string): Record<string, unknown> {
  return Object.fromEntries(Object.entries(obj).filter(([k]) => k !== chave))
}

/** Config of the `decision` node: typed questions answered by a pluggable decision provider. */
export function DecisionFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string) => t(`editor.properties.fields.${key}`)
  // Padrões do motor, conferidos no DecisionComponent.initialize: RoutePolicy.DEFAULT (auto 0.9, review 0.5),
  // provedor HttpDecisionProvider.ID, onError ESCALATE, acceptAt = thresholds.auto e consensus.onDisagree REVIEW.
  // O painel mostra só o gravado e diz o padrão no placeholder — antes exibia estes valores como se estivessem no
  // nó (RCHFLW-92).
  const dflt = (value: unknown) => t('editor.properties.fields.defaultValue', { value: String(value) })
  const thresholds = getConfig(nodeData, 'thresholds', {} as { auto?: number; review?: number })
  // Campo apagado tira o limiar (e o objeto, se vazio): o Number('') de antes gravava 0 — todo ramo virava AUTO.
  const setThreshold = (key: 'auto' | 'review', v: number | string) => {
    if (typeof v !== 'number' && v !== '') return
    const next: Record<string, unknown> = { ...thresholds }
    if (typeof v === 'number') next[key] = v
    else delete next[key]
    update('thresholds', Object.keys(next).length > 0 ? next : undefined)
  }
  const gate = getConfig(nodeData, 'gate', [] as string[])
  const consensus = getConfig(nodeData, 'consensus', {} as { models?: unknown; onDisagree?: string })
  const provider = storedChoice(nodeData, 'provider')
  // O motor entrega ao provedor `providerOptions.<id>` (DecisionComponent.tentar), e o `rules` lê dali
  // `rules`: daí o caminho providerOptions.rules.rules. Grava mesclando, para não apagar as opções de
  // outros provedores (degraus da cascata, reservas) nem outras chaves do próprio `rules`.
  const providerOptions = getConfig(nodeData, 'providerOptions', {} as Record<string, unknown>)
  const rulesOptions = (providerOptions.rules ?? {}) as Record<string, unknown>
  const setRules = (v: unknown) => {
    // Campo esvaziado: remove a chave em vez de gravar `rules: undefined` (que deixaria `rules: {}`
    // no JSON salvo). Para o motor dá no mesmo — sem regras é "sem regras para a pergunta" —, mas o
    // documento volta a ser o de antes de alguém mexer no campo.
    const novoRules = v === undefined ? semChave(rulesOptions, 'rules') : { ...rulesOptions, rules: v }
    update('providerOptions', Object.keys(novoRules).length > 0
      ? { ...providerOptions, rules: novoRules }
      : semChave(providerOptions, 'rules'))
  }

  return (
    <>
      <Select
        label={f('decisionProvider')}
        value={provider}
        placeholder={dflt('http-decisions')}
        onChange={v => update('provider', v ?? undefined)}
        data={PROVIDERS}
        size="xs"
        allowDeselect={false}
        styles={FIELD_STYLES}
      />
      {provider === 'rules' && (
        <JsonField
          label={f('decisionRules')}
          hint={f('decisionRulesHint')}
          value={rulesOptions.rules}
          onValid={setRules}
          minRows={6}
        />
      )}
      <TextInput
        label={f('decisionModel')}
        value={getConfig(nodeData, 'model', '')}
        onChange={e => update('model', e.currentTarget.value)}
        placeholder="typesafe/jev-1.13"
        size="xs"
        styles={MONO_INPUT}
        description={f('decisionModelHint')}
      />
      <JsonField
        label={f('decisionQuestions')}
        hint={f('decisionQuestionsHint')}
        value={getConfig(nodeData, 'questions', undefined as unknown)}
        onValid={v => update('questions', v)}
        required
        minRows={8}
      />
      <JsonField
        label={f('decisionState')}
        hint={f('decisionStateHint')}
        value={getConfig(nodeData, 'state', undefined as unknown)}
        onValid={v => update('state', v)}
        minRows={3}
      />
      <TextInput
        label={f('decisionPrimary')}
        value={getConfig(nodeData, 'primary', '')}
        onChange={e => update('primary', e.currentTarget.value || undefined)}
        size="xs"
        styles={MONO_INPUT}
      />
      <TextInput
        label={f('decisionGate')}
        value={gate.join(', ')}
        onChange={e => update('gate', e.currentTarget.value.split(',').map(s => s.trim()).filter(Boolean))}
        size="xs"
        styles={MONO_INPUT}
        description={f('decisionGateHint')}
      />
      <NumberInput
        label={f('thresholdAuto')}
        value={thresholds.auto ?? ''}
        placeholder={dflt(0.9)}
        onChange={v => setThreshold('auto', v)}
        min={0} max={1} step={0.05} decimalScale={2}
        size="xs"
        styles={FIELD_STYLES}
      />
      <NumberInput
        label={f('thresholdReview')}
        value={thresholds.review ?? ''}
        placeholder={dflt(0.5)}
        onChange={v => setThreshold('review', v)}
        min={0} max={1} step={0.05} decimalScale={2}
        size="xs"
        styles={FIELD_STYLES}
        description={f('thresholdHint')}
      />
      <Select
        label={f('decisionOnError')}
        value={storedChoice(nodeData, 'onError')}
        placeholder={dflt('ESCALATE')}
        onChange={v => update('onError', v ?? undefined)}
        data={['ESCALATE', 'FAIL']}
        size="xs"
        allowDeselect={false}
        styles={FIELD_STYLES}
        description={f('decisionOnErrorHint')}
      />

      <Text size="xs" fw={600} mt={4}>{f('decisionAdvanced')}</Text>
      <NumberInput
        label={f('decisionAcceptAt')}
        value={storedNumber(nodeData, 'acceptAt')}
        placeholder={dflt(thresholds.auto ?? 0.9)}
        onChange={v => {
          if (typeof v === 'number') update('acceptAt', v)
          else if (v === '') update('acceptAt', undefined)
        }}
        min={0} max={1} step={0.05} decimalScale={2}
        size="xs"
        styles={FIELD_STYLES}
      />
      <JsonField
        label={f('decisionCascade')}
        hint={f('decisionCascadeHint')}
        value={getConfig(nodeData, 'cascade', undefined as unknown)}
        onValid={v => update('cascade', v)}
      />
      <JsonField
        label={f('decisionFallbacks')}
        hint={f('decisionFallbacksHint')}
        value={getConfig(nodeData, 'fallbacks', undefined as unknown)}
        onValid={v => update('fallbacks', v)}
      />
      <JsonField
        label={f('decisionConsensusModels')}
        hint={f('decisionConsensusHint')}
        value={consensus.models}
        onValid={v => update('consensus', { ...consensus, models: v })}
      />
      <Select
        label={f('decisionOnDisagree')}
        value={consensus.onDisagree ?? null}
        placeholder={dflt('REVIEW')}
        onChange={v => update('consensus', { ...consensus, onDisagree: v ?? undefined })}
        data={['REVIEW', 'ESCALATE']}
        size="xs"
        allowDeselect={false}
        styles={FIELD_STYLES}
      />
      <Text size="xs" c="dimmed">{f('decisionRoutesHint')}</Text>
    </>
  )
}
