// Tipos Básicos
export type Metadata = Record<string, unknown>;

// Enums
export enum StepType {
  ASSISTANT = 'ASSISTANT',
  AGENT = 'AGENT',
  TOOL = 'TOOL',
  CUSTOM = 'CUSTOM'
}

export enum ErrorType {
  VALIDATION = 'VALIDATION',
  EXECUTION = 'EXECUTION',
  TIMEOUT = 'TIMEOUT',
  LLM = 'LLM',
  SYSTEM = 'SYSTEM',
  PERMISSION = 'PERMISSION',
  CONNECTION = 'CONNECTION'
}

export enum StepStatus {
  PENDING = 'PENDING',
  RUNNING = 'RUNNING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  SKIPPED = 'SKIPPED',
  CANCELLED = 'CANCELLED',
  PAUSED = 'PAUSED',
  TIMEOUT = 'TIMEOUT'
}

export enum LogLevel {
  DEBUG = 'DEBUG',
  INFO = 'INFO',
  WARN = 'WARN',
  ERROR = 'ERROR'
}

export enum PathStatus {
  STARTED = 'STARTED',
  RUNNING = 'RUNNING',
  PAUSED = 'PAUSED',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  MERGED = 'MERGED'
}

export enum FlowStatus {
  INITIALIZED = 'INITIALIZED',
  RUNNING = 'RUNNING',
  PAUSED = 'PAUSED',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  STOPPED = 'STOPPED'
}

// Configurações
export interface RetryPolicy {
  maxAttempts: number;
  delay: number;
  multiplier: number;
  retryableExceptions: string[]; // Nomes das classes de exceção em string
}

export interface LLMConfig {
  model: string;
  temperature: number;
  maxTokens: number;
  timeout: number;
  additionalConfig: Record<string, any>;
}

export interface MonitoringConfig {
  detailedMetrics: boolean;
  fullHistory: boolean;
  logLevel: LogLevel;
  tags: Record<string, string>;
}

export interface FlowConfiguration {
  timeout: number;
  retryPolicy: RetryPolicy;
  llmConfig: LLMConfig;
  monitoringConfig: MonitoringConfig;
}

// Métricas
export interface StepMetrics {
  executionTime: number;
  tokensUsed: number;
  retryCount: number;
  additionalMetrics: Record<string, any>;
}

export interface FlowMetrics {
  startTime: number;
  endTime: number;
  stepMetrics: Record<string, StepMetrics>;
  totalSteps: number;
  completedSteps: number;
}

// Execução
export interface ExecutionPath {
  pathId: string;
  status: PathStatus;
  completedSteps: string[];
  parallelBranches?: ExecutionPath[];
}

// Erros
export interface StepError {
  type: ErrorType;
  code: string;
  message: string;
  timestamp: Date;
  context: Record<string, unknown>;
  cause?: Error;
}

export interface ExecutionError {
  code: string;
  message: string;
  type: ErrorType;
  component?: string;
  timestamp: Date;
  cause?: Error;
  details: Record<string, unknown>;
}

// Estado
export interface FlowState {
  flowId: string;
  status: FlowStatus;
  currentStepId?: string;
  variables: Record<string, unknown>;
  executionPaths: ExecutionPath[];
  metrics: FlowMetrics;
  error?: ExecutionError;
}

// Metadata
export interface FlowMetadata {
  name: string;
  description: string;
  version: string;
  author: string;
  category: string;
  tags: string[];
}

// Conexões
export interface StepConnection {
  sourceId: string;
  targetId: string;
  condition?: string;
  isErrorPath: boolean;
  type?: 'success' | 'error' | 'tool';
}

// Interface base para dados dos nós
export interface BaseNodeData {
  label: string;
  operation: string;
  componentId?: string;
}

// Tipos específicos para cada tipo de nó
export interface AgentNodeData extends BaseNodeData {
  type: StepType.AGENT;
}

export interface AssistantNodeData extends BaseNodeData {
  type: StepType.ASSISTANT;
}

export interface ToolNodeData extends BaseNodeData {
  type: StepType.TOOL;
}

// Union type para todos os tipos de nós
export type NodeData = AgentNodeData | AssistantNodeData | ToolNodeData;

// Passo do Fluxo
export interface FlowStep {
  id: string;
  type: StepType;
  componentId: string;
  operation: string;
  connections: StepConnection[];
  configuration?: Record<string, any>;
}

// Interface Principal do Fluxo
export interface Flow {
  id: string;
  metadata: FlowMetadata;
  steps: FlowStep[];
  configuration: FlowConfiguration;
  validate?: () => Promise<void>;
}

// Resultado da Execução
export interface FlowResult {
  status: FlowStatus;
  output?: unknown;
  metrics: FlowMetrics;
  errors: ExecutionError[];
}

export interface StepResult {
  stepId: string;
  status: StepStatus;
  output?: unknown;
  metrics: StepMetrics;
  errors: StepError[];
}

// Type Guards
export const isAgentNode = (data: NodeData): data is AgentNodeData => {
  return data.type === StepType.AGENT;
};

export const isAssistantNode = (data: NodeData): data is AssistantNodeData => {
  return data.type === StepType.ASSISTANT;
};

export const isToolNode = (data: NodeData): data is ToolNodeData => {
  return data.type === StepType.TOOL;
};

// Helpers para Status
export const isStatusFinal = (status: StepStatus): boolean => {
  return [
    StepStatus.COMPLETED,
    StepStatus.FAILED,
    StepStatus.SKIPPED,
    StepStatus.CANCELLED,
    StepStatus.TIMEOUT
  ].includes(status);
};

export const isStatusError = (status: StepStatus): boolean => {
  return [StepStatus.FAILED, StepStatus.TIMEOUT].includes(status);
};

export const isStatusRunning = (status: StepStatus): boolean => {
  return [StepStatus.RUNNING, StepStatus.PAUSED].includes(status);
};

export const isFlowStatusFinal = (status: FlowStatus): boolean => {
  return [
    FlowStatus.COMPLETED,
    FlowStatus.FAILED,
    FlowStatus.STOPPED
  ].includes(status);
};

export const isPathStatusActive = (status: PathStatus): boolean => {
  return [PathStatus.STARTED, PathStatus.RUNNING].includes(status);
};

export const isPathStatusTerminal = (status: PathStatus): boolean => {
  return [
    PathStatus.COMPLETED,
    PathStatus.FAILED,
    PathStatus.MERGED
  ].includes(status);
};