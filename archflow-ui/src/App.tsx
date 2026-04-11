import './App.css';
import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@xyflow/react/dist/style.css';
import { MantineProvider } from '@mantine/core';
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
import TemplatesPage from './pages/TemplatesPage';
import TemplateDetailPage from './pages/TemplateDetailPage';
import VoicePlaygroundPage from './pages/VoicePlaygroundPage';
import ApprovalQueuePage from './pages/ApprovalQueuePage';
import ApprovalDetailPage from './pages/ApprovalDetailPage';

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
const ObservabilityLayout  = lazy(() => import('./pages/admin/observability/ObservabilityLayout'));
const ObservabilityOverview = lazy(() => import('./pages/admin/observability/ObservabilityOverview'));
const TracesPage           = lazy(() => import('./pages/admin/observability/TracesPage'));
const TraceDetailPage      = lazy(() => import('./pages/admin/observability/TraceDetailPage'));
const MetricsPage          = lazy(() => import('./pages/admin/observability/MetricsPage'));
const AuditLogPage         = lazy(() => import('./pages/admin/observability/AuditLogPage'));
const LiveEventsPage       = lazy(() => import('./pages/admin/observability/LiveEventsPage'));

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

                            {/* Observability */}
                            <Route path="observability" element={<ObservabilityLayout />}>
                                <Route index element={<ObservabilityOverview />} />
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
        </MantineProvider>
    );
}

export default App;
