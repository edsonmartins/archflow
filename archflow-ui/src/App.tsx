import './App.css'
import '@mantine/core/styles.css'
import '@mantine/dates/styles.css'
import '@mantine/notifications/styles.css'
import '@mantine/spotlight/styles.css'
import { MantineProvider, Button, Container, Stack } from '@mantine/core';
import FlowEditorPage from './components/FlowEditorPage';
import { ArchflowDesignerTest } from './components/ArchflowDesignerTest';
import { useState } from 'react';

function App() {
  const [mode, setMode] = useState<'old' | 'webcomponent'>('old');

  return (
    <MantineProvider>
      <Container size="xl" h="100vh" p="md">
        <Stack gap="md" h="100%">
          <div style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: '8px 16px',
            background: '#f8fafc',
            borderRadius: '8px',
            border: '1px solid #e2e8f0'
          }}>
            <div>
              <strong style={{ fontSize: '18px' }}>Archflow UI</strong>
              <span style={{ marginLeft: '16px', color: '#64748b', fontSize: '14px' }}>
                Visual AI Workflow Builder
              </span>
            </div>
            <Button.Group>
              <Button
                variant={mode === 'old' ? 'filled' : 'light'}
                onClick={() => setMode('old')}
              >
                React Editor (Old)
              </Button>
              <Button
                variant={mode === 'webcomponent' ? 'filled' : 'light'}
                onClick={() => setMode('webcomponent')}
              >
                Web Component Test
              </Button>
            </Button.Group>
          </div>

          {mode === 'old' ? (
            <div style={{ flex: 1, overflow: 'hidden' }}>
              <FlowEditorPage />
            </div>
          ) : (
            <div style={{ flex: 1, overflow: 'hidden', borderRadius: '8px', border: '1px solid #e2e8f0' }}>
              <ArchflowDesignerTest />
            </div>
          )}
        </Stack>
      </Container>
    </MantineProvider>
  );
}

export default App
