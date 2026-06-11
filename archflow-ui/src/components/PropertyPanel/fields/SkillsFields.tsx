import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Select, TextInput } from '@mantine/core'
import { FIELD_STYLES } from '../fieldStyles'
import { getConfig } from './helpers'
import type { FieldProps } from './FieldProps'
import { skillsApi } from '../../../services/skills-api'

export function SkillsFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const [skills, setSkills] = useState<{ name: string; description: string; active: boolean }[]>([])

  useEffect(() => {
    skillsApi.list()
        .then(xs => setSkills(xs.map(s => ({ name: s.name, description: s.description, active: s.active }))))
        .catch(() => setSkills([]))
  }, [])

  const operation = getConfig(nodeData, 'skillsOperation', 'activate') as string

  return (
    <>
      <Select
        label={f('operation')}
        value={operation}
        onChange={v => update('skillsOperation', v)}
        size="xs"
        data={[
          { value: 'list',        label: f('skillsOps.list') },
          { value: 'activate',    label: f('skillsOps.activate') },
          { value: 'deactivate',  label: f('skillsOps.deactivate') },
          { value: 'read',        label: f('skillsOps.read') },
          { value: 'active',      label: f('skillsOps.active') },
        ]}
        styles={FIELD_STYLES}
      />
      {(operation === 'activate' || operation === 'deactivate' || operation === 'read') && (
        <Select
          label={f('skillLabel')}
          value={(getConfig(nodeData, 'skillName', '') as string) || null}
          onChange={v => update('skillName', v ?? '')}
          size="xs"
          data={skills.map(s => ({ value: s.name, label: `${s.name}${s.active ? f('skillActiveSuffix') : ''}` }))}
          searchable
          placeholder={skills.length ? f('skillPick') : f('noSkills')}
          styles={FIELD_STYLES}
        />
      )}
      {operation === 'read' && (
        <TextInput
          label={f('resourceName')}
          value={getConfig(nodeData, 'skillResource', '')}
          onChange={e => update('skillResource', e.currentTarget.value)}
          size="xs"
          styles={FIELD_STYLES}
        />
      )}
      <TextInput
        label={f('outputVariable')}
        value={getConfig(nodeData, 'skillsOutputVar', 'skillsResult')}
        onChange={e => update('skillsOutputVar', e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />
    </>
  )
}

// ── Execution Logs ──────────────────────────────────────────────
