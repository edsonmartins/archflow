import { useState, useRef, useEffect } from 'react';
import { Container, Title, TextInput, PasswordInput, Button, Select, Stack, Alert, Paper, Text, Group } from '@mantine/core';
import { login, listWorkflows, executeWorkflow, getExecution, type Workflow, type Execution } from './archflow-client';

// Register the web component types
declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace JSX {
    interface IntrinsicElements {
      'archflow-designer': React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement> & { 'workflow-id'?: string }, HTMLElement>;
    }
  }
}

export default function App() {
  const [authenticated, setAuthenticated] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [execution, setExecution] = useState<Execution | null>(null);
  const [error, setError] = useState('');
  const designerRef = useRef<HTMLElement>(null);

  const handleLogin = async () => {
    try {
      setError('');
      await login(username, password);
      setAuthenticated(true);
      const wfs = await listWorkflows();
      setWorkflows(wfs);
    } catch {
      setError('Login failed. Check credentials and that the backend is running on :8080.');
    }
  };

  const handleExecute = async () => {
    if (!selectedId) return;
    try {
      setError('');
      const exec = await executeWorkflow(selectedId, { channel: 'web', query: 'Help with my order' });
      setExecution(exec);
    } catch {
      setError('Execution failed.');
    }
  };

  const handlePollStatus = async () => {
    if (!execution) return;
    try {
      const updated = await getExecution(execution.id);
      setExecution(updated);
    } catch {
      setError('Failed to poll execution status.');
    }
  };

  useEffect(() => {
    // Load the web component script (served from archflow backend or npm)
    const script = document.createElement('script');
    script.type = 'module';
    script.src = 'http://localhost:8080/assets/archflow-designer.js';
    document.head.appendChild(script);
    return () => { document.head.removeChild(script); };
  }, []);

  if (!authenticated) {
    return (
      <Container size="xs" mt="xl">
        <Title order={2} mb="md">Archflow - Customer Support Demo</Title>
        {error && <Alert color="red" mb="md">{error}</Alert>}
        <Stack>
          <TextInput label="Username" value={username} onChange={e => setUsername(e.currentTarget.value)} />
          <PasswordInput label="Password" value={password} onChange={e => setPassword(e.currentTarget.value)} />
          <Button onClick={handleLogin}>Login</Button>
        </Stack>
      </Container>
    );
  }

  return (
    <Container size="lg" mt="xl">
      <Title order={2} mb="md">Customer Support Workflows</Title>
      {error && <Alert color="red" mb="md">{error}</Alert>}

      <Stack>
        <Select
          label="Select Workflow"
          placeholder="Choose a workflow..."
          data={workflows.map(w => ({ value: w.id, label: w.name }))}
          value={selectedId}
          onChange={setSelectedId}
        />

        {selectedId && (
          <Paper shadow="xs" p="md" withBorder>
            <Text fw={500} mb="sm">Visual Designer</Text>
            <archflow-designer ref={designerRef} workflow-id={selectedId} style={{ display: 'block', height: 400 }} />
          </Paper>
        )}

        <Group>
          <Button onClick={handleExecute} disabled={!selectedId}>Execute Workflow</Button>
          {execution && <Button variant="outline" onClick={handlePollStatus}>Refresh Status</Button>}
        </Group>

        {execution && (
          <Paper shadow="xs" p="md" withBorder>
            <Text fw={500}>Execution: {execution.id}</Text>
            <Text>Status: {execution.status}</Text>
            {execution.result && <Text size="sm" c="dimmed">Result: {JSON.stringify(execution.result)}</Text>}
          </Paper>
        )}
      </Stack>
    </Container>
  );
}
