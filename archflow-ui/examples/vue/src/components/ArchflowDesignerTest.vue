<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import './ArchflowDesignerTest.css'

// Type definitions for the custom element
interface ArchflowDesignerElement extends HTMLElement {
  workflowId?: string
  apiBase?: string
  theme?: 'light' | 'dark'
  readonly?: boolean
  width?: string
  height?: string
  loadWorkflow: (workflowId: string) => Promise<unknown>
  setWorkflow: (workflow: unknown) => void
  saveWorkflow: () => Promise<unknown>
  executeWorkflow: (input?: Record<string, unknown>) => Promise<unknown>
  selectNodes: (nodeIds: string[]) => void
  workflow: unknown
}

// Event types
interface WorkflowEventDetail {
  timestamp: number
  [key: string]: unknown
}

interface EventLogEntry {
  name: string
  detail: string
  time: string
}

// Refs
const designerRef = ref<ArchflowDesignerElement | null>(null)
const events = ref<EventLogEntry[]>([])
const isConnected = ref(false)
const currentTheme = ref<'light' | 'dark'>('light')

// Computed
const eventCount = computed(() => events.value.length)

// Sample workflow
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
}

// Event logging
const logEvent = (name: string, detail: unknown) => {
  const detailStr = JSON.stringify(detail, null, 2)
  events.value = [{
    name,
    detail: detailStr,
    time: new Date().toLocaleTimeString()
  }, ...events.value].slice(0, 50)
}

// Event handlers
const handleWorkflowSaved = (e: Event) => {
  const customEvent = e as CustomEvent<WorkflowEventDetail>
  logEvent('workflow-saved', customEvent.detail)
}

const handleWorkflowLoaded = (e: Event) => {
  const customEvent = e as CustomEvent<WorkflowEventDetail>
  logEvent('workflow-loaded', customEvent.detail)
}

const handleWorkflowExecuted = (e: Event) => {
  const customEvent = e as CustomEvent<WorkflowEventDetail>
  logEvent('workflow-executed', customEvent.detail)
}

const handleNodeSelected = (e: Event) => {
  const customEvent = e as CustomEvent<WorkflowEventDetail>
  logEvent('node-selected', customEvent.detail)
}

const handleConnected = (e: Event) => {
  isConnected.value = true
  const customEvent = e as CustomEvent<WorkflowEventDetail>
  logEvent('connected', customEvent.detail)
}

const handleError = (e: Event) => {
  const customEvent = e as CustomEvent<WorkflowEventDetail>
  logEvent('error', customEvent.detail)
}

// Actions
const handleLoadSample = () => {
  const designer = designerRef.value
  if (designer && designer.setWorkflow) {
    designer.setWorkflow(sampleWorkflow)
    logEvent('workflow-loaded', { workflow: sampleWorkflow })
  }
}

const handleSave = async () => {
  const designer = designerRef.value
  if (designer && designer.saveWorkflow) {
    try {
      const workflow = await designer.saveWorkflow()
      console.log('Saved workflow:', workflow)
      logEvent('save-success', { workflow })
    } catch (error) {
      console.error('Save error:', error)
      logEvent('save-error', { error })
    }
  }
}

const handleExecute = async () => {
  const designer = designerRef.value
  if (designer && designer.executeWorkflow) {
    try {
      const result = await designer.executeWorkflow({
        message: 'Hello, I need help with my order.'
      })
      console.log('Execution result:', result)
      logEvent('execute-success', { result })
    } catch (error) {
      console.error('Execution error:', error)
      logEvent('execute-error', { error })
    }
  }
}

const handleThemeToggle = () => {
  const designer = designerRef.value
  if (designer) {
    const newTheme = designer.theme === 'light' ? 'dark' : 'light'
    designer.theme = newTheme
    currentTheme.value = newTheme
    logEvent('theme-changed', { theme: newTheme })
  }
}

const handleClearEvents = () => {
  events.value = []
}

// Lifecycle
onMounted(() => {
  const designer = designerRef.value
  if (designer) {
    designer.addEventListener('workflow-saved', handleWorkflowSaved)
    designer.addEventListener('workflow-loaded', handleWorkflowLoaded)
    designer.addEventListener('workflow-executed', handleWorkflowExecuted)
    designer.addEventListener('node-selected', handleNodeSelected)
    designer.addEventListener('connected', handleConnected)
    designer.addEventListener('error', handleError)
  }
})

onUnmounted(() => {
  const designer = designerRef.value
  if (designer) {
    designer.removeEventListener('workflow-saved', handleWorkflowSaved)
    designer.removeEventListener('workflow-loaded', handleWorkflowLoaded)
    designer.removeEventListener('workflow-executed', handleWorkflowExecuted)
    designer.removeEventListener('node-selected', handleNodeSelected)
    designer.removeEventListener('connected', handleConnected)
    designer.removeEventListener('error', handleError)
  }
})

// Helper for status icon
const getStatusClass = () => ({
  'archflow-test__status--connected': isConnected.value
})
</script>

<template>
  <div class="archflow-test">
    <div class="archflow-test__sidebar">
      <div class="archflow-test__header">
        <h2>ArchflowDesigner</h2>
        <span :class="['archflow-test__status', getStatusClass()]">
          {{ isConnected ? '● Connected' : '○ Connecting...' }}
        </span>
      </div>

      <div class="archflow-test__section">
        <h3>Actions</h3>
        <button @click="handleLoadSample" class="archflow-test__button">
          Load Sample Workflow
        </button>
        <button @click="handleSave" class="archflow-test__button">
          Save Workflow
        </button>
        <button @click="handleExecute" class="archflow-test__button archflow-test__button--primary">
          Execute Workflow
        </button>
        <button @click="handleThemeToggle" class="archflow-test__button">
          Toggle Theme ({{ currentTheme }})
        </button>
        <button @click="handleClearEvents" class="archflow-test__button archflow-test__button--secondary">
          Clear Events
        </button>
      </div>

      <div class="archflow-test__section">
        <h3>Event Log ({{ eventCount }})</h3>
        <div class="archflow-test__events">
          <div v-if="events.length === 0" class="archflow-test__empty">
            No events yet
          </div>
          <div
            v-for="(event, index) in events"
            :key="index"
            class="archflow-test__event"
          >
            <div class="archflow-test__event-header">
              <span class="archflow-test__event-name">{{ event.name }}</span>
              <span class="archflow-test__event-time">{{ event.time }}</span>
            </div>
            <pre class="archflow-test__event-detail">{{ event.detail }}</pre>
          </div>
        </div>
      </div>
    </div>

    <div class="archflow-test__main">
      <archflow-designer
        ref="designerRef"
        workflow-id="test-workflow"
        api-base="http://localhost:8080/api"
        theme="light"
        width="100%"
        height="100%"
      />
    </div>
  </div>
</template>
