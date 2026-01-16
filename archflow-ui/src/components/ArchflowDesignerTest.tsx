/**
 * ArchflowDesigner Web Component Test
 *
 * Tests the framework-agnostic Web Component in React.
 *
 * This demonstrates that the <archflow-designer> custom element works
 * correctly with React 19's native Web Components support.
 */

import { useEffect, useRef, useState } from 'react';
import './ArchflowDesignerTest.css';

// Define the custom element types for TypeScript
declare global {
  namespace JSX {
    interface IntrinsicElements {
      'archflow-designer': ArchflowDesignerAttributes;
    }
  }

  interface HTMLElementTagNameMap {
    'archflow-designer': ArchflowDesignerElement;
  }
}

interface ArchflowDesignerAttributes extends React.HTMLAttributes<HTMLElement> {
  'workflow-id'?: string;
  'api-base'?: string;
  'theme'?: 'light' | 'dark';
  'readonly'?: string;
  'width'?: string;
  'height'?: string;
}

interface ArchflowDesignerElement extends HTMLElement {
  workflowId?: string;
  apiBase?: string;
  theme?: 'light' | 'dark';
  readonly?: boolean;
  width?: string;
  height?: string;
  loadWorkflow: (workflowId: string) => Promise<unknown>;
  setWorkflow: (workflow: unknown) => void;
  saveWorkflow: () => Promise<unknown>;
  executeWorkflow: (input?: Record<string, unknown>) => Promise<unknown>;
  selectNodes: (nodeIds: string[]) => void;
  workflow: unknown;
}

// Custom events
interface ArchflowWorkflowSavedEvent extends Event {
  detail: {
    workflow: unknown;
    timestamp: number;
  };
}

interface ArchflowNodeSelectedEvent extends Event {
  detail: {
    nodeId: string;
    timestamp: number;
  };
}

interface ArchflowWorkflowExecutedEvent extends Event {
  detail: {
    result: unknown;
    timestamp: number;
  };
}

export function ArchflowDesignerTest() {
  const designerRef = useRef<ArchflowDesignerElement>(null);
  const [events, setEvents] = useState<Array<{ name: string; detail: string; time: string }>>([]);
  const [isConnected, setIsConnected] = useState(false);

  useEffect(() => {
    const designer = designerRef.current;
    if (!designer) return;

    const logEvent = (name: string, detail: unknown) => {
      const detailStr = JSON.stringify(detail, null, 2);
      setEvents(prev => [{
        name,
        detail: detailStr,
        time: new Date().toLocaleTimeString()
      }, ...prev].slice(0, 50)); // Keep last 50 events
    };

    const handleWorkflowSaved = (e: Event) => {
      logEvent('workflow-saved', (e as ArchflowWorkflowSavedEvent).detail);
    };

    const handleNodeSelected = (e: Event) => {
      logEvent('node-selected', (e as ArchflowNodeSelectedEvent).detail);
    };

    const handleWorkflowExecuted = (e: Event) => {
      logEvent('workflow-executed', (e as ArchflowWorkflowExecutedEvent).detail);
    };

    const handleConnected = (e: Event) => {
      setIsConnected(true);
      logEvent('connected', { component: 'archflow-designer' });
    };

    designer.addEventListener('workflow-saved', handleWorkflowSaved);
    designer.addEventListener('node-selected', handleNodeSelected);
    designer.addEventListener('workflow-executed', handleWorkflowExecuted);
    designer.addEventListener('connected', handleConnected);

    return () => {
      designer.removeEventListener('workflow-saved', handleWorkflowSaved);
      designer.removeEventListener('node-selected', handleNodeSelected);
      designer.removeEventListener('workflow-executed', handleWorkflowExecuted);
      designer.removeEventListener('connected', handleConnected);
    };
  }, []);

  const handleLoadSample = () => {
    const designer = designerRef.current;
    if (designer && designer.setWorkflow) {
      const sampleWorkflow = {
        id: 'test-workflow-1',
        metadata: {
          name: 'Sample Customer Support Flow',
          description: 'A simple AI workflow for customer support',
          version: '1.0.0'
        },
        steps: [
          {
            id: 'input-1',
            type: 'input',
            name: 'Customer Input',
            config: {
              schema: {
                type: 'object',
                properties: {
                  message: { type: 'string', description: 'Customer message' }
                }
              }
            }
          },
          {
            id: 'llm-1',
            type: 'llm-chat',
            name: 'AI Assistant',
            config: {
              model: 'gpt-4',
              temperature: 0.7,
              maxTokens: 500,
              systemPrompt: 'You are a helpful customer support assistant.'
            }
          },
          {
            id: 'output-1',
            type: 'output',
            name: 'Response',
            config: {}
          }
        ]
      };
      designer.setWorkflow(sampleWorkflow);
      logEvent('workflow-loaded', { workflow: sampleWorkflow });
    }
  };

  const handleSave = async () => {
    const designer = designerRef.current;
    if (designer && designer.saveWorkflow) {
      try {
        const workflow = await designer.saveWorkflow();
        console.log('Saved workflow:', workflow);
        alert('Workflow saved! Check console for details.');
      } catch (error) {
        console.error('Save error:', error);
        alert('Failed to save workflow. Check console for details.');
      }
    }
  };

  const handleExecute = async () => {
    const designer = designerRef.current;
    if (designer && designer.executeWorkflow) {
      try {
        const result = await designer.executeWorkflow({ message: 'Hello, I need help with my order.' });
        console.log('Execution result:', result);
        alert('Workflow executed! Check console for details.');
      } catch (error) {
        console.error('Execution error:', error);
        alert('Failed to execute workflow. Check console for details.');
      }
    }
  };

  const handleClearEvents = () => {
    setEvents([]);
  };

  const handleThemeToggle = () => {
    const designer = designerRef.current;
    if (designer) {
      designer.theme = designer.theme === 'light' ? 'dark' : 'light';
    }
  };

  return (
    <div className="archflow-test">
      <div className="archflow-test__sidebar">
        <div className="archflow-test__header">
          <h2>ArchflowDesigner</h2>
          <span className={`archflow-test__status ${isConnected ? 'archflow-test__status--connected' : ''}`}>
            {isConnected ? '● Connected' : '○ Connecting...'}
          </span>
        </div>

        <div className="archflow-test__section">
          <h3>Actions</h3>
          <button onClick={handleLoadSample} className="archflow-test__button">
            Load Sample Workflow
          </button>
          <button onClick={handleSave} className="archflow-test__button">
            Save Workflow
          </button>
          <button onClick={handleExecute} className="archflow-test__button archflow-test__button--primary">
            Execute Workflow
          </button>
          <button onClick={handleThemeToggle} className="archflow-test__button">
            Toggle Theme
          </button>
          <button onClick={handleClearEvents} className="archflow-test__button archflow-test__button--secondary">
            Clear Events
          </button>
        </div>

        <div className="archflow-test__section">
          <h3>Event Log ({events.length})</h3>
          <div className="archflow-test__events">
            {events.length === 0 ? (
              <div className="archflow-test__empty">No events yet</div>
            ) : (
              events.map((event, index) => (
                <div key={index} className="archflow-test__event">
                  <div className="archflow-test__event-header">
                    <span className="archflow-test__event-name">{event.name}</span>
                    <span className="archflow-test__event-time">{event.time}</span>
                  </div>
                  <pre className="archflow-test__event-detail">{event.detail}</pre>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      <div className="archflow-test__main">
        <archflow-designer
          ref={designerRef}
          workflow-id="test-workflow"
          api-base="http://localhost:8080/api"
          theme="light"
          width="100%"
          height="100%"
        ></archflow-designer>
      </div>
    </div>
  );
}
