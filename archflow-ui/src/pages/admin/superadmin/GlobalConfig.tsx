import { Title, Table, Badge, Text, Paper, Stack, Switch, Group, Divider } from '@mantine/core'
import { useState } from 'react'

const MOCK_MODELS = [
  { id: 'gpt-4o',            name: 'GPT-4o',            provider: 'OpenAI',    status: 'active',     costIn: 2.50, costOut: 10.00 },
  { id: 'claude-sonnet-4-6', name: 'Claude Sonnet 4.6', provider: 'Anthropic', status: 'active',     costIn: 3.00, costOut: 15.00 },
  { id: 'gemma-3-27b',       name: 'Gemma 3 27B',       provider: 'Local',     status: 'beta',       costIn: 0,    costOut: 0 },
  { id: 'gpt-3.5-turbo',     name: 'GPT-3.5 Turbo',     provider: 'OpenAI',    status: 'deprecated', costIn: 0.50, costOut: 1.50 },
]

const STATUS_COLORS: Record<string, string> = { active: 'green', beta: 'orange', deprecated: 'gray' }

export default function GlobalConfig() {
  const [toggles, setToggles] = useState({
    allowLocalModels: true,
    humanInTheLoop: true,
    brainSentry: true,
    debugMode: false,
    linktorNotifications: false,
    auditLog: true,
  })

  const toggle = (key: keyof typeof toggles) =>
    setToggles(prev => ({ ...prev, [key]: !prev[key] }))

  return (
    <Stack gap="md">
      <Title order={3}>Global Configuration</Title>

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">LLM Models</Text>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Model</Table.Th>
              <Table.Th>Provider</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Cost/1M input</Table.Th>
              <Table.Th>Cost/1M output</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {MOCK_MODELS.map(m => (
              <Table.Tr key={m.id}>
                <Table.Td><Text size="sm" fw={500} ff="monospace">{m.name}</Text></Table.Td>
                <Table.Td><Text size="sm">{m.provider}</Text></Table.Td>
                <Table.Td><Badge color={STATUS_COLORS[m.status]} size="sm">{m.status}</Badge></Table.Td>
                <Table.Td><Text size="sm">${m.costIn.toFixed(2)}</Text></Table.Td>
                <Table.Td><Text size="sm">${m.costOut.toFixed(2)}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">Feature Toggles</Text>
        <Stack gap="sm">
          <Switch label="Allow local models (Ollama / RTX)" checked={toggles.allowLocalModels} onChange={() => toggle('allowLocalModels')} />
          <Switch label="Human-in-the-loop" checked={toggles.humanInTheLoop} onChange={() => toggle('humanInTheLoop')} />
          <Switch label="Brain Sentry (long-term memory via FalkorDB)" checked={toggles.brainSentry} onChange={() => toggle('brainSentry')} />
          <Switch label="Debug mode — full trace per node" checked={toggles.debugMode} onChange={() => toggle('debugMode')} />
          <Switch label="Linktor notifications (WhatsApp)" checked={toggles.linktorNotifications} onChange={() => toggle('linktorNotifications')} />
          <Switch label="Audit log for user actions" checked={toggles.auditLog} onChange={() => toggle('auditLog')} />
        </Stack>
      </Paper>
    </Stack>
  )
}
