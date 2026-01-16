import React from 'react';
import { ReactFlowProvider } from 'reactflow';
import { 
  AppShell,
  Title, 
  Text, 
  Group,
  Stack,
  Badge,
  Box
} from '@mantine/core';
import FlowViewer from './FlowViewer';
import { Flow, StepType, LogLevel } from '../types/flow-types';

// Fluxo correto baseado no modelo Java
const refrigeratorSupportFlow: Flow = {
  id: "refrigerator-support-flow",
  metadata: {
    name: "Refrigerator Support Flow",
    description: "Flow for handling refrigerator technical support requests",
    version: "1.0.0",
    author: "Archflow Team",
    category: "Technical Support",
    tags: ["support", "refrigerator", "technical"]
  },
  configuration: {
    timeout: 3600000,
    retryPolicy: {
      maxAttempts: 3,
      delay: 1000,
      multiplier: 2.0,
      retryableExceptions: [
        "br.com.archflow.engine.exceptions.FlowException",
        "java.net.SocketTimeoutException",
        "java.io.IOException"
      ]
    },
    llmConfig: {
      model: "gpt-4",
      temperature: 0.7,
      maxTokens: 1000,
      timeout: 30000,
      additionalConfig: {
        topP: 0.9,
        frequencyPenalty: 0.0,
        presencePenalty: 0.0
      }
    },
    monitoringConfig: {
      detailedMetrics: true,
      fullHistory: true,
      logLevel: LogLevel.INFO,
      tags: {
        env: "production",
        service: "support"
      }
    }
  },
  steps: [
    {
      id: "initial-query",
      type: StepType.ASSISTANT,
      componentId: "refrigerator-support-assistant",
      operation: "handleInitialQuery",
      configuration: {
        escalationThreshold: 0.7,
        maxResponseTokens: 1000
      },
      connections: [
        {
          sourceId: "initial-query",
          targetId: "check-confidence",
          type: "success",
          isErrorPath: false
        },
        {
          sourceId: "initial-query",
          targetId: "escalate-to-human",
          type: "error",
          isErrorPath: true
        }
      ]
    },
    {
      id: "check-confidence",
      type: StepType.TOOL,
      componentId: "confidence-checker",
      operation: "evaluate",
      connections: [
        {
          sourceId: "check-confidence",
          targetId: "perform-diagnosis",
          type: "success",
          isErrorPath: false
        },
        {
          sourceId: "check-confidence",
          targetId: "escalate-to-human",
          type: "error",
          isErrorPath: true
        }
      ]
    },
    {
      id: "perform-diagnosis",
      type: StepType.ASSISTANT,
      componentId: "refrigerator-support-assistant",
      operation: "performDiagnosis",
      connections: [
        {
          sourceId: "perform-diagnosis",
          targetId: "check-technician-needed",
          type: "success",
          isErrorPath: false
        },
        {
          sourceId: "perform-diagnosis",
          targetId: "escalate-to-human",
          type: "error",
          isErrorPath: true
        }
      ]
    },
    {
      id: "check-technician-needed",
      type: StepType.TOOL,
      componentId: "decision-tool",
      operation: "evaluate",
      connections: [
        {
          sourceId: "check-technician-needed",
          targetId: "generate-self-service",
          type: "success",
          isErrorPath: false
        },
        {
          sourceId: "check-technician-needed",
          targetId: "check-warranty",
          type: "success",
          isErrorPath: false
        }
      ]
    },
    {
      id: "check-warranty",
      type: StepType.ASSISTANT,
      componentId: "refrigerator-support-assistant",
      operation: "checkWarrantyAndSchedule",
      connections: [
        {
          sourceId: "check-warranty",
          targetId: "schedule-visit",
          type: "success",
          isErrorPath: false
        },
        {
          sourceId: "check-warranty",
          targetId: "generate-quote",
          type: "success",
          isErrorPath: false
        }
      ]
    },
    {
      id: "generate-self-service",
      type: StepType.ASSISTANT,
      componentId: "refrigerator-support-assistant",
      operation: "generateSolution",
      connections: [
        {
          sourceId: "generate-self-service",
          targetId: "send-response",
          type: "success",
          isErrorPath: false
        }
      ]
    },
    {
      id: "schedule-visit",
      type: StepType.TOOL,
      componentId: "scheduling-tool",
      operation: "schedule",
      connections: [
        {
          sourceId: "schedule-visit",
          targetId: "send-response",
          type: "success",
          isErrorPath: false
        }
      ]
    },
    {
      id: "generate-quote",
      type: StepType.TOOL,
      componentId: "quote-generator",
      operation: "generate",
      connections: [
        {
          sourceId: "generate-quote",
          targetId: "send-response",
          type: "success",
          isErrorPath: false
        }
      ]
    },
    {
      id: "escalate-to-human",
      type: StepType.TOOL,
      componentId: "escalation-tool",
      operation: "createTicket",
      connections: [
        {
          sourceId: "escalate-to-human",
          targetId: "send-response",
          type: "success",
          isErrorPath: false
        }
      ]
    },
    {
      id: "send-response",
      type: StepType.TOOL,
      componentId: "response-formatter",
      operation: "format",
      connections: []
    }
  ]
};

const FlowEditorPage = () => {
  return (
    <AppShell>
      <AppShell.Header p="md">
        <Group justify="space-between" h="100%">
          <Stack gap={5}>
            <Title order={2}>{refrigeratorSupportFlow.metadata.name}</Title>
            <Text c="dimmed" size="sm">{refrigeratorSupportFlow.metadata.description}</Text>
          </Stack>
          <Group>
            <Badge size="lg" variant="light">v{refrigeratorSupportFlow.metadata.version}</Badge>
            <Badge size="lg" color="blue">{refrigeratorSupportFlow.metadata.category}</Badge>
            {refrigeratorSupportFlow.metadata.tags.map(tag => (
              <Badge key={tag} size="sm">{tag}</Badge>
            ))}
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Main style={{ border:'2px solid red', width:'1080px', height: 'calc(100vh - 60px)' }}>
        <Box style={{ 
          width: '100%', 
          height: '100%',
          display: 'flex',
          flexDirection: 'column'
        }}>
          <ReactFlowProvider>
            <FlowViewer flowData={refrigeratorSupportFlow} />
          </ReactFlowProvider>
        </Box>
      </AppShell.Main>
    </AppShell>
  );
};

export default FlowEditorPage;