import React, { useCallback, useEffect } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  Handle,
  Position,
  Node,
  Edge,
  NodeProps,
} from 'reactflow';
import 'reactflow/dist/style.css';

import {
  Paper,
  Text,
  Group,
  Box,
  Stack,
  useMantineTheme,
  ThemeIcon,
} from '@mantine/core';

// Exemplos de ícones (você pode trocar por qualquer biblioteca)
import {
  IconRobot,
  IconUser,
  IconTools,
} from '@tabler/icons-react';

// Ajuste estes imports conforme seu projeto
import {
  Flow,
  NodeData,
  AgentNodeData,
  AssistantNodeData,
  ToolNodeData,
  StepType,
  FlowStep,
  StepConnection,
} from '../types/flow-types';

/**
 * Componente que cria uma barra lateral colorida (à esquerda do nó).
 * Recebe uma cor e retorna um <Box> que será a "faixa" lateral.
 */
function LateralBar({ color }: { color: string }) {
  return (
    <Box
      style={{
        width: 6,
        backgroundColor: color,
        borderTopLeftRadius: 8,
        borderBottomLeftRadius: 8,
      }}
    />
  );
}

/**
 * Componente base para evitar repetição de estilo nos nós.
 * Recebe propriedades como título, descrição, cor, ícone, etc.
 */
interface BaseNodeProps {
  title: string;
  description?: string;
  color: string;          // cor principal do nó
  icon: React.ReactNode;  // ícone do nó
  componentId?: string;
  children?: React.ReactNode;
  handles?: {
    // controle de exibição dos handles (entradas/saídas)
    in?: boolean;
    out?: boolean;
    outTool?: boolean; // se deseja um handle especial para "tool"
  };
}

function BaseNode({
  title,
  description,
  color,
  icon,
  componentId,
  children,
  handles = { in: true, out: true, outTool: false },
}: BaseNodeProps) {
  const theme = useMantineTheme();

  return (
    <Paper
      shadow="md"
      withBorder
      style={{
        position: 'relative',
        display: 'flex',
        borderRadius: 8,
        overflow: 'hidden',
        width: 260,
      }}
    >
      {/* Barra lateral colorida */}
      <LateralBar color={color} />

      {/* Conteúdo do nó */}
      <Box
        style={{
          flex: 1,
          backgroundColor: theme.white,
          padding: theme.spacing.md,
        }}
      >
        <Stack gap={4}>
          <Group gap="xs">
            <ThemeIcon
              variant="light"
              color="gray"
              size={24}
              radius="xl"
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              {icon}
            </ThemeIcon>

            <Text fw={700} size="md">
              {title}
            </Text>
          </Group>

          {description && (
            <Text size="xs" c="dimmed">
              {description}
            </Text>
          )}

          {componentId && (
            <Text size="xs" c="dimmed">
              ID: {componentId}
            </Text>
          )}

          {children}
        </Stack>
      </Box>

      {/* Handles (conexões) */}
      {handles.in && (
        <Handle
          type="target"
          position={Position.Left}
          style={{
            background: theme.white,
            border: `2px solid ${color}`,
          }}
        />
      )}
      {handles.out && (
        <Handle
          type="source"
          position={Position.Right}
          style={{
            background: theme.white,
            border: `2px solid ${color}`,
            top: '50%',
          }}
        />
      )}
      {handles.outTool && (
        <Handle
          type="source"
          position={Position.Right}
          id="tool"
          style={{
            background: theme.white,
            border: `2px solid ${theme.colors.violet[7]}`,
            top: '75%',
          }}
        />
      )}
    </Paper>
  );
}

/**
 * Nó customizado para Agent
 */
const AgentNode: React.FC<NodeProps<AgentNodeData>> = ({ data }) => {
  const theme = useMantineTheme();
  const color = theme.colors.red[6]; // cor principal para "Agent"

  return (
    <BaseNode
      title={data.label || 'Agent'}
      description={`Operation: ${data.operation || ''}`}
      color={color}
      icon={<IconRobot size={16} />}
      componentId={data.componentId}
      handles={{ in: true, out: true, outTool: true }}
    />
  );
};

/**
 * Nó customizado para Assistant
 */
const AssistantNode: React.FC<NodeProps<AssistantNodeData>> = ({ data }) => {
  const theme = useMantineTheme();
  const color = theme.colors.blue[6]; // cor principal para "Assistant"

  return (
    <BaseNode
      title={data.label || 'Assistant'}
      description={`Operation: ${data.operation || ''}`}
      color={color}
      icon={<IconUser size={16} />}
      componentId={data.componentId}
      handles={{ in: true, out: true, outTool: true }}
    />
  );
};

/**
 * Nó customizado para Tool
 */
const ToolNode: React.FC<NodeProps<ToolNodeData>> = ({ data }) => {
  const theme = useMantineTheme();
  const color = theme.colors.teal[6]; // cor principal para "Tool"

  return (
    <BaseNode
      title={data.label || 'Tool'}
      description={`Op: ${data.operation || ''}`}
      color={color}
      icon={<IconTools size={16} />}
      componentId={data.componentId}
      // Se quiser manter um handle específico para tools, ative "outTool"
      handles={{ in: true, out: true, outTool: false }}
    />
  );
};

/**
 * Mapeamento de tipos de nós para os componentes acima.
 */
const nodeTypes = {
  agent: AgentNode,
  assistant: AssistantNode,
  tool: ToolNode,
};

interface FlowViewerProps {
  flowData: Flow;
}

const FlowViewer: React.FC<FlowViewerProps> = ({ flowData }) => {
  const [nodes, setNodes, onNodesChange] = useNodesState<NodeData>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);

  /**
   * Funções auxiliares para mapear StepType -> Node Type
   */
  const getNodeDataType = (stepType: StepType) => {
    switch (stepType) {
      case StepType.AGENT:
        return StepType.AGENT;
      case StepType.ASSISTANT:
        return StepType.ASSISTANT;
      case StepType.TOOL:
        return StepType.TOOL;
      default:
        throw new Error(`Unsupported step type: ${stepType}`);
    }
  };

  const getNodeType = (stepType: StepType) => {
    switch (stepType) {
      case StepType.AGENT:
        return 'agent';
      case StepType.ASSISTANT:
        return 'assistant';
      case StepType.TOOL:
        return 'tool';
      default:
        return 'default';
    }
  };

  /**
   * Constrói os nodes e edges a partir do flowData
   */
  const processFlow = useCallback((flow: Flow) => {
    const nodes: Node<NodeData>[] = [];
    const edges: Edge[] = [];

    flow.steps.forEach((step: FlowStep, index: number) => {
      // Exemplo de layout simples (grade 3 colunas)
      const yPosition = Math.floor(index / 3) * 200;
      const xPosition = (index % 3) * 300;

      const nodeData: NodeData = {
        label: step.id,
        operation: step.operation,
        componentId: step.componentId,
        type: getNodeDataType(step.type),
      };

      nodes.push({
        id: step.id,
        type: getNodeType(step.type),
        position: { x: xPosition, y: yPosition },
        data: nodeData,
      });
    });

    // Cria as conexões
    flow.steps.forEach((step: FlowStep) => {
      if (step.connections) {
        step.connections.forEach((connection: StepConnection) => {
          const edgeType = connection.type === 'tool' ? 'smoothstep' : 'default';
          const sourceHandle = connection.type === 'tool' ? 'tool' : undefined;

          edges.push({
            id: `${connection.sourceId}-${connection.targetId}`,
            source: connection.sourceId,
            target: connection.targetId,
            type: edgeType,
            sourceHandle,
            animated: connection.type === 'tool',
            style: {
              stroke: connection.isErrorPath
                ? 'var(--mantine-color-red-6)'
                : connection.type === 'tool'
                ? 'var(--mantine-color-violet-6)'
                : 'var(--mantine-color-gray-6)',
              strokeWidth: connection.type === 'tool' ? 2 : 1,
            },
          });
        });
      }
    });

    return { nodes, edges };
  }, []);

  /**
   * Quando flowData mudar, recria nós e edges
   */
  useEffect(() => {
    if (flowData) {
      const { nodes: newNodes, edges: newEdges } = processFlow(flowData);
      setNodes(newNodes);
      setEdges(newEdges);
    }
  }, [flowData, processFlow, setNodes, setEdges]);

  return (
    <Box
      style={{
        width: '100%',
        height: '100%',
        position: 'relative',
        display: 'flex',
      }}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        style={{
          width: '100%',
          height: '100%',
          backgroundColor: 'var(--mantine-color-gray-0)',
        }}
      >
        <Background />
        <Controls />
        <MiniMap
          nodeColor={(node) => {
            switch (node.type) {
              case 'agent':
                return 'var(--mantine-color-red-4)';
              case 'assistant':
                return 'var(--mantine-color-blue-4)';
              case 'tool':
                return 'var(--mantine-color-teal-4)';
              default:
                return 'var(--mantine-color-gray-4)';
            }
          }}
          style={{
            backgroundColor: 'var(--mantine-color-gray-1)',
          }}
        />
      </ReactFlow>
    </Box>
  );
};

export default FlowViewer;
