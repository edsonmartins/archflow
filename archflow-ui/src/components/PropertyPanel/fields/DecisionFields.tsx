import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NumberInput, Select, Text, TextInput, Textarea } from '@mantine/core'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { getConfig } from './helpers'
import type { FieldProps } from './FieldProps'

/**
 * Um campo cujo valor é JSON. Guarda o texto digitado à parte e só grava na config quando ele
 * parseia: gravar a cada tecla trocaria o objeto por lixo no meio da digitação — e perderia o que a
 * pessoa estava escrevendo.
 */
function JsonField({
  label, hint, value, onValid, minRows = 4,
}: {
  label: string
  hint?: string
  value: unknown
  onValid: (v: unknown) => void
  minRows?: number
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

/** Config of the `decision` node: typed questions answered by a pluggable decision provider. */
export function DecisionFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string) => t(`editor.properties.fields.${key}`)
  const thresholds = getConfig(nodeData, 'thresholds', {} as { auto?: number; review?: number })
  const setThreshold = (key: 'auto' | 'review', v: number | string) =>
    update('thresholds', { ...thresholds, [key]: typeof v === 'number' ? v : Number(v) })
  const gate = getConfig(nodeData, 'gate', [] as string[])
  const consensus = getConfig(nodeData, 'consensus', {} as { models?: unknown; onDisagree?: string })

  return (
    <>
      <Select
        label={f('decisionProvider')}
        value={getConfig(nodeData, 'provider', 'http-decisions')}
        onChange={v => update('provider', v ?? 'http-decisions')}
        data={PROVIDERS}
        size="xs"
        allowDeselect={false}
        styles={FIELD_STYLES}
      />
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
        value={thresholds.auto ?? 0.9}
        onChange={v => setThreshold('auto', v)}
        min={0} max={1} step={0.05} decimalScale={2}
        size="xs"
        styles={FIELD_STYLES}
      />
      <NumberInput
        label={f('thresholdReview')}
        value={thresholds.review ?? 0.5}
        onChange={v => setThreshold('review', v)}
        min={0} max={1} step={0.05} decimalScale={2}
        size="xs"
        styles={FIELD_STYLES}
        description={f('thresholdHint')}
      />
      <Select
        label={f('decisionOnError')}
        value={getConfig(nodeData, 'onError', 'ESCALATE')}
        onChange={v => update('onError', v ?? 'ESCALATE')}
        data={['ESCALATE', 'FAIL']}
        size="xs"
        allowDeselect={false}
        styles={FIELD_STYLES}
        description={f('decisionOnErrorHint')}
      />

      <Text size="xs" fw={600} mt={4}>{f('decisionAdvanced')}</Text>
      <NumberInput
        label={f('decisionAcceptAt')}
        value={getConfig(nodeData, 'acceptAt', thresholds.auto ?? 0.9)}
        onChange={v => update('acceptAt', typeof v === 'number' ? v : Number(v))}
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
        value={consensus.onDisagree ?? 'REVIEW'}
        onChange={v => update('consensus', { ...consensus, onDisagree: v ?? 'REVIEW' })}
        data={['REVIEW', 'ESCALATE']}
        size="xs"
        allowDeselect={false}
        styles={FIELD_STYLES}
      />
      <Text size="xs" c="dimmed">{f('decisionRoutesHint')}</Text>
    </>
  )
}
