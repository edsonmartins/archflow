import './App.css';
import '@mantine/core/styles.css';
import '@mantine/charts/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/spotlight/styles.css';
import '@xyflow/react/dist/style.css';
import { MantineProvider } from '@mantine/core';
import { ModalsProvider } from '@mantine/modals';
import { useLocalStorage } from '@mantine/hooks';
import { Notifications } from '@mantine/notifications';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import { theme } from './theme';
import AppLayout from './components/layout/AppLayout';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import WorkflowListPage from './pages/WorkflowListPage';
import { WorkflowEditor as WorkflowEditorPage } from './pages/WorkflowEditorPage';
import ExecutionHistoryPage from './pages/ExecutionHistoryPage';
import ConversationsListPage from './pages/ConversationsListPage';
import ConversationPage from './pages/ConversationPage';
import ApprovalQueuePage from './pages/ApprovalQueuePage';
import ApprovalDetailPage from './pages/ApprovalDetailPage';
import ExecutionDetailPage from './pages/ExecutionDetailPage';
import ScopedApiKeysPage from './pages/admin/tenant/ScopedApiKeysPage';
import MarketplacePage from './pages/MarketplacePage';
import MarketplaceDetailPage from './pages/MarketplaceDetailPage';
import McpServersPage from './pages/McpServersPage';
import McpServerDetailPage from './pages/McpServerDetailPage';
import LinktorConfigPage from './pages/LinktorConfigPage';
import LinktorInboxPage from './pages/LinktorInboxPage';
import LinktorConversationPage from './pages/LinktorConversationPage';
import ObservabilityLayout from './pages/admin/observability/ObservabilityLayout';
import ObservabilityOverview from './pages/admin/observability/ObservabilityOverview';
import RunningFlowsPage from './pages/admin/observability/RunningFlowsPage';
import TracesPage from './pages/admin/observability/TracesPage';
import TraceDetailPage from './pages/admin/observability/TraceDetailPage';
import MetricsPage from './pages/admin/observability/MetricsPage';
import AuditLogPage from './pages/admin/observability/AuditLogPage';
import LiveEventsPage from './pages/admin/observability/LiveEventsPage';

// Telas secundárias — lazy loaded (templates, marketplace, playgrounds e
// configurações não precisam entrar no bundle inicial do app)
const TemplatesPage           = lazy(() => import('./pages/TemplatesPage'));
const TemplateDetailPage      = lazy(() => import('./pages/TemplateDetailPage'));
const VoicePlaygroundPage     = lazy(() => import('./pages/VoicePlaygroundPage'));
const SkillsPage              = lazy(() => import('./pages/SkillsPage'));
const BrainSentryConfigPage   = lazy(() => import('./pages/BrainSentryConfigPage'));
const ScheduledTriggersPage   = lazy(() => import('./pages/ScheduledTriggersPage'));
const AgentPlaygroundPage     = lazy(() => import('./pages/AgentPlaygroundPage'));
const DynamicWorkflowPage     = lazy(() => import('./pages/DynamicWorkflowPage'));
const AgUiRunnerPage          = lazy(() => import('./pages/AgUiRunnerPage'));
const CopilotAssistantPage    = lazy(() => import('./pages/CopilotAssistantPage'));

// Admin pages — lazy loaded
const AdminLayout       = lazy(() => import('./components/admin/AdminLayout'));
const TenantList        = lazy(() => import('./pages/admin/superadmin/TenantList'));
const TenantNew         = lazy(() => import('./pages/admin/superadmin/TenantNew'));
const TenantDetail      = lazy(() => import('./pages/admin/superadmin/TenantDetail'));
const GlobalConfig      = lazy(() => import('./pages/admin/superadmin/GlobalConfig'));
const UsageBilling      = lazy(() => import('./pages/admin/superadmin/UsageBilling'));
const WorkspaceOverview = lazy(() => import('./pages/admin/tenant/WorkspaceOverview'));
const UserManagement    = lazy(() => import('./pages/admin/tenant/UserManagement'));
const ApiKeys           = lazy(() => import('./pages/admin/tenant/ApiKeys'));

// Observability pages — lazy loaded
export function useColorScheme() {
    return useLocalStorage<'light' | 'dark'>({
        key: 'archflow-color-scheme',
        defaultValue: 'light',
    });
}

function App() {
    const [colorScheme] = useColorScheme();

    return (
        <MantineProvider theme={theme} forceColorScheme={colorScheme}>
            <ModalsProvider>
            <Notifications position="top-right" />
            <BrowserRouter>
                <Suspense fallback={<div style={{ padding: 40, textAlign: 'center' }}>Loading...</div>}>
                    <Routes>
                        <Route path="/login" element={<LoginPage />} />

                        {/* Main app routes */}
                        <Route element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
                            <Route path="/" element={<WorkflowListPage />} />
                            <Route path="/editor" element={<WorkflowEditorPage />} />
                            <Route path="/editor/:id" element={<WorkflowEditorPage />} />
                            <Route path="/executions" element={<ExecutionHistoryPage />} />
                            <Route path="/conversations" element={<ConversationsListPage />} />
                            <Route path="/conversations/:id" element={<ConversationPage />} />
                            <Route path="/templates" element={<TemplatesPage />} />
                            <Route path="/templates/:id" element={<TemplateDetailPage />} />
                            <Route path="/playground/voice" element={<VoicePlaygroundPage />} />
                            <Route path="/approvals" element={<ApprovalQueuePage />} />
                            <Route path="/approvals/:id" element={<ApprovalDetailPage />} />
                            <Route path="/marketplace" element={<MarketplacePage />} />
                            <Route path="/marketplace/:id" element={<MarketplaceDetailPage />} />
                            <Route path="/executions/:id" element={<ExecutionDetailPage />} />
                            <Route path="/playground/agent" element={<AgentPlaygroundPage />} />
                            <Route path="/playground/orchestration" element={<DynamicWorkflowPage />} />
                            <Route path="/playground/ag-ui" element={<AgUiRunnerPage />} />
                            <Route path="/playground/copilot" element={<CopilotAssistantPage />} />
                        </Route>

                        {/* Admin routes */}
                        <Route path="/admin" element={<ProtectedRoute requiredRole="tenant_admin"><AdminLayout /></ProtectedRoute>}>
                            <Route index element={<Navigate to="workspace" replace />} />

                            {/* Superadmin */}
                            <Route path="tenants" element={<ProtectedRoute requiredRole="superadmin"><TenantList /></ProtectedRoute>} />
                            <Route path="tenants/new" element={<ProtectedRoute requiredRole="superadmin"><TenantNew /></ProtectedRoute>} />
                            <Route path="tenants/:id" element={<ProtectedRoute requiredRole="superadmin"><TenantDetail /></ProtectedRoute>} />
                            <Route path="global" element={<ProtectedRoute requiredRole="superadmin"><GlobalConfig /></ProtectedRoute>} />
                            <Route path="billing" element={<ProtectedRoute requiredRole="superadmin"><UsageBilling /></ProtectedRoute>} />

                            {/* Tenant admin */}
                            <Route path="workspace" element={<WorkspaceOverview />} />
                            <Route path="workspace/users" element={<UserManagement />} />
                            <Route path="workspace/keys" element={<ApiKeys />} />
                            <Route path="workspace/api-keys" element={<ScopedApiKeysPage />} />
                            <Route path="skills" element={<SkillsPage />} />
                            <Route path="mcp" element={<McpServersPage />} />
                            <Route path="mcp/:name" element={<McpServerDetailPage />} />
                            <Route path="brainsentry" element={<BrainSentryConfigPage />} />
                            <Route path="linktor" element={<LinktorConfigPage />} />
                            <Route path="linktor/inbox" element={<LinktorInboxPage />} />
                            <Route path="linktor/inbox/:id" element={<LinktorConversationPage />} />
                            <Route path="triggers" element={<ScheduledTriggersPage />} />

                            {/* Observability */}
                            <Route path="observability" element={<ObservabilityLayout />}>
                                <Route index element={<ObservabilityOverview />} />
                                <Route path="running" element={<RunningFlowsPage />} />
                                <Route path="traces" element={<TracesPage />} />
                                <Route path="traces/:id" element={<TraceDetailPage />} />
                                <Route path="metrics" element={<MetricsPage />} />
                                <Route path="audit" element={<AuditLogPage />} />
                                <Route path="live" element={<LiveEventsPage />} />
                            </Route>
                        </Route>
                    </Routes>
                </Suspense>
            </BrowserRouter>
            </ModalsProvider>
        </MantineProvider>
    );
}

export default App;
