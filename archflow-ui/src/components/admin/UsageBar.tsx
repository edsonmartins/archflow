import { Progress, Group, Text } from '@mantine/core'

interface UsageBarProps {
  current: number
  limit:   number
  label?:  string
  unit?:   string
}

export function UsageBar({ current, limit, label, unit = '' }: UsageBarProps) {
  const pct = limit > 0 ? Math.min((current / limit) * 100, 100) : 0
  const color = pct >= 90 ? 'red' : pct >= 70 ? 'yellow' : 'blue'

  return (
    <div>
      {label && <Text size="xs" fw={500} mb={4}>{label}</Text>}
      <Progress value={pct} color={color} size="sm" radius="xl" />
      <Group justify="space-between" mt={2}>
        <Text size="xs" c="dimmed">
          {current.toLocaleString()}{unit}
        </Text>
        <Text size="xs" c="dimmed">
          {limit.toLocaleString()}{unit} ({pct.toFixed(0)}%)
        </Text>
      </Group>
    </div>
  )
}
