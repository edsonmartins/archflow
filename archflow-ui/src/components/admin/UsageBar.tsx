import { Progress, Group, Text } from '@mantine/core'
import { useTranslation } from 'react-i18next'

interface UsageBarProps {
  current: number
  limit:   number
  label?:  string
  unit?:   string
}

export function UsageBar({ current, limit, label, unit = '' }: UsageBarProps) {
  const { i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
  const pct = limit > 0 ? Math.min((current / limit) * 100, 100) : 0
  const color = pct >= 90 ? 'red' : pct >= 70 ? 'yellow' : 'blue'

  return (
    <div>
      {label && <Text size="xs" fw={500} mb={4}>{label}</Text>}
      <Progress value={pct} color={color} size="sm" radius="xl" />
      <Group justify="space-between" mt={2}>
        <Text size="xs" c="dimmed">
          {current.toLocaleString(locale)}{unit}
        </Text>
        <Text size="xs" c="dimmed">
          {limit.toLocaleString(locale)}{unit} ({pct.toFixed(0)}%)
        </Text>
      </Group>
    </div>
  )
}
