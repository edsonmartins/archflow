import './App.css';
import '@mantine/core/styles.css';
import { MantineProvider } from '@mantine/core';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import AppLayout from './components/layout/AppLayout';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import WorkflowListPage from './pages/WorkflowListPage';
import WorkflowEditorPage from './pages/WorkflowEditorPage';
import ExecutionHistoryPage from './pages/ExecutionHistoryPage';

function App() {
    return (
        <MantineProvider>
            <BrowserRouter>
                <Routes>
                    <Route path="/login" element={<LoginPage />} />
                    <Route
                        element={
                            <ProtectedRoute>
                                <AppLayout />
                            </ProtectedRoute>
                        }
                    >
                        <Route path="/" element={<WorkflowListPage />} />
                        <Route path="/editor" element={<WorkflowEditorPage />} />
                        <Route path="/editor/:id" element={<WorkflowEditorPage />} />
                        <Route path="/executions" element={<ExecutionHistoryPage />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </MantineProvider>
    );
}

export default App;
