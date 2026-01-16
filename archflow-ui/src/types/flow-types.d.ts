export type Metadata = Record<string, unknown>;
export declare enum StepType {
    ASSISTANT = "ASSISTANT",
    AGENT = "AGENT",
    TOOL = "TOOL",
    CUSTOM = "CUSTOM"
}
export declare enum ErrorType {
    VALIDATION = "VALIDATION",
    EXECUTION = "EXECUTION",
    TIMEOUT = "TIMEOUT",
    LLM = "LLM",
    SYSTEM = "SYSTEM",
    PERMISSION = "PERMISSION",
    CONNECTION = "CONNECTION"
}
export declare enum StepStatus {
    PENDING = "PENDING",
    RUNNING = "RUNNING",
    COMPLETED = "COMPLETED",
    FAILED = "FAILED",
    SKIPPED = "SKIPPED",
    CANCELLED = "CANCELLED",
    PAUSED = "PAUSED",
    TIMEOUT = "TIMEOUT"
}
export declare enum LogLevel {
    DEBUG = "DEBUG",
    INFO = "INFO",
    WARN = "WARN",
    ERROR = "ERROR"
}
export declare enum PathStatus {
    STARTED = "STARTED",
    RUNNING = "RUNNING",
    PAUSED = "PAUSED",
    COMPLETED = "COMPLETED",
    FAILED = "FAILED",
    MERGED = "MERGED"
}
export declare enum FlowStatus {
    INITIALIZED = "INITIALIZED",
    RUNNING = "RUNNING",
    PAUSED = "PAUSED",
    COMPLETED = "COMPLETED",
    FAILED = "FAILED",
    STOPPED = "STOPPED"
}
export interface RetryPolicy {
    maxAttempts: number;
    delay: number;
    multiplier: number;
    retryableExceptions: string[];
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
export interface ExecutionPath {
    pathId: string;
    status: PathStatus;
    completedSteps: string[];
    parallelBranches?: ExecutionPath[];
}
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
export interface FlowState {
    flowId: string;
    status: FlowStatus;
    currentStepId?: string;
    variables: Record<string, unknown>;
    executionPaths: ExecutionPath[];
    metrics: FlowMetrics;
    error?: ExecutionError;
}
export interface FlowMetadata {
    name: string;
    description: string;
    version: string;
    author: string;
    category: string;
    tags: string[];
}
export interface StepConnection {
    sourceId: string;
    targetId: string;
    condition?: string;
    isErrorPath: boolean;
    type?: 'success' | 'error' | 'tool';
}
export interface BaseNodeData {
    label: string;
    operation: string;
    componentId?: string;
}
export interface AgentNodeData extends BaseNodeData {
    type: StepType.AGENT;
}
export interface AssistantNodeData extends BaseNodeData {
    type: StepType.ASSISTANT;
}
export interface ToolNodeData extends BaseNodeData {
    type: StepType.TOOL;
}
export type NodeData = AgentNodeData | AssistantNodeData | ToolNodeData;
export interface FlowStep {
    id: string;
    type: StepType;
    componentId: string;
    operation: string;
    connections: StepConnection[];
    configuration?: Record<string, any>;
}
export interface Flow {
    id: string;
    metadata: FlowMetadata;
    steps: FlowStep[];
    configuration: FlowConfiguration;
    validate?: () => Promise<void>;
}
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
export declare const isAgentNode: (data: NodeData) => data is AgentNodeData;
export declare const isAssistantNode: (data: NodeData) => data is AssistantNodeData;
export declare const isToolNode: (data: NodeData) => data is ToolNodeData;
export declare const isStatusFinal: (status: StepStatus) => boolean;
export declare const isStatusError: (status: StepStatus) => boolean;
export declare const isStatusRunning: (status: StepStatus) => boolean;
export declare const isFlowStatusFinal: (status: FlowStatus) => boolean;
export declare const isPathStatusActive: (status: PathStatus) => boolean;
export declare const isPathStatusTerminal: (status: PathStatus) => boolean;
//# sourceMappingURL=flow-types.d.ts.map