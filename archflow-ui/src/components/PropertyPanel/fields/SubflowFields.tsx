import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Select, TextInput, Textarea, NumberInput, Switch } from '@mantine/core'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { getConfig } from './helpers'
import type { FieldProps } from './FieldProps'
import { workflowApi } from '../../../services/api'

export function SubflowFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string) => t(`editor.properties.fields.${key}`)
  const [workflows, setWorkflows] = useState<{ id: string; name: string }[]>([])
  const [loading, setLoading]     = useState(true)

  useEffect(() => {
    workflowApi.list()
        .then(items => setWorkflows(items.map(w => ({ id: w.id, name: w.name ?? w.id }))))
        .catch(() => setWorkflows([]))
        .finally(() => setLoading(false))
  }, [])

  return (
    <>
      <Select
        label={f('workflowToInvoke')}
        value={(getConfig(nodeData, 'subflowId', '') as string) || null}
        onChange={v => update('subflowId', v ?? '')}
        size="xs"
        data={workflows.map(w => ({ value: w.id, label: `${w.name} (${w.id})` }))}
        searchable
        disabled={loading}
        placeholder={loading ? f('loadingWorkflows') : f('pickWorkflow')}
        styles={FIELD_STYLES}
      />
      <Textarea
        label={f('inputMapping')}
        value={getConfig(nodeData, 'subflowInput', '{}')}
        onChange={e => update('subflowInput', e.currentTarget.value)}
        autosize minRows={3}
        size="xs"
        styles={MONO_INPUT}
        description={f('inputMappingHint')}
      />
      <TextInput
        label={f('outputVariable')}
        value={getConfig(nodeData, 'subflowOutputVar', 'subflowResult')}
        onChange={e => update('subflowOutputVar', e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />
      <NumberInput
        label={f('timeoutSec')}
        value={getConfig(nodeData, 'subflowTimeoutSeconds', 120)}
        onChange={v => update('subflowTimeoutSeconds', v)}
        min={1} max={3600}
        size="xs"
        styles={FIELD_STYLES}
      />
      <Switch
        checked={getConfig(nodeData, 'subflowAsync', false)}
        onChange={e => update('subflowAsync', e.currentTarget.checked)}
        label={f('fireAndForget')}
        size="xs"
      />
    </>
  )
}

// ── Skills fields ───────────────────────────────────────────────
