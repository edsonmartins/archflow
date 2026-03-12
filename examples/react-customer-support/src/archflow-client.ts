const BASE_URL = '/api';

let token = '';

export async function login(username: string, password: string): Promise<string> {
  const res = await fetch(`${BASE_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error('Login failed');
  const data = await res.json();
  token = data.token;
  return token;
}

function authHeaders(): HeadersInit {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };
}

export interface Workflow {
  id: string;
  name: string;
  description?: string;
}

export interface Execution {
  id: string;
  status: string;
  result?: unknown;
}

export async function listWorkflows(): Promise<Workflow[]> {
  const res = await fetch(`${BASE_URL}/workflows`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to list workflows');
  return res.json();
}

export async function executeWorkflow(workflowId: string, input?: Record<string, unknown>): Promise<Execution> {
  const res = await fetch(`${BASE_URL}/workflows/${workflowId}/execute`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(input ?? {}),
  });
  if (!res.ok) throw new Error('Failed to execute workflow');
  return res.json();
}

export async function getExecution(executionId: string): Promise<Execution> {
  const res = await fetch(`${BASE_URL}/executions/${executionId}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to get execution');
  return res.json();
}
