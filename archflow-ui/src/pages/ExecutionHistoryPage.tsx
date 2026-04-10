import { Stack, LoadingOverlay, Alert } from '@mantine/core';
import { IconAlertCircle } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useWorkflowStore } from '../stores/workflow-store';

const STATUS_BADGE: Record<string, { bg: string; color: string; dot: string; label: string }> = {
    RUNNING:   { bg: 'var(--blue-l)',  color: 'var(--blue)',  dot: 'var(--blue)',  label: 'Running' },
    COMPLETED: { bg: 'var(--green-l)', color: 'var(--green)', dot: 'var(--green)', label: 'Completed' },
    FAILED:    { bg: 'var(--red-l)',   color: 'var(--red)',   dot: 'var(--red)',   label: 'Failed' },
    PAUSED:    { bg: 'var(--amber-l)', color: 'var(--amber)', dot: 'var(--amber)', label: 'Paused' },
    CANCELLED: { bg: 'var(--gray-l)',  color: 'var(--gray)',  dot: 'var(--gray)',  label: 'Cancelled' },
};

function formatDuration(ms: number | null): string {
    if (ms == null) return '—';
    if (ms < 1000) return `${ms}ms`;
    const sec = ms / 1000;
    if (sec < 60) return `${sec.toFixed(1)}s`;
    const min = Math.floor(sec / 60);
    const remSec = Math.round(sec % 60);
    return `${min}m ${remSec}s`;
}

const COL_GRID = '160px 1fr 130px 90px 80px 1fr';

export default function ExecutionHistoryPage() {
    const { executions, workflows, loading, error, fetchExecutions, fetchWorkflows } = useWorkflowStore();
    const [filterWorkflow, setFilterWorkflow] = useState('');

    useEffect(() => { fetchWorkflows(); fetchExecutions(); }, [fetchWorkflows, fetchExecutions]);
    useEffect(() => { fetchExecutions(filterWorkflow || undefined); }, [filterWorkflow, fetchExecutions]);

    return (
        <Stack gap="md" pos="relative">
            <LoadingOverlay visible={loading} />
            {error && <Alert color="red" icon={<IconAlertCircle size={16} />}>{error}</Alert>}

            {/* Header */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.3px', color: 'var(--text)' }}>Execution History</span>
                <select
                    value={filterWorkflow}
                    onChange={e => setFilterWorkflow(e.target.value)}
                    style={{
                        padding: '7px 28px 7px 10px', borderRadius: 7,
                        border: '1px solid var(--border2)', background: 'var(--bg2)',
                        fontSize: 12, color: 'var(--text2)', fontFamily: 'var(--font-sans)',
                        cursor: 'pointer', outline: 'none', appearance: 'none',
                        backgroundImage: `url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%239CA3AF' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E")`,
                        backgroundRepeat: 'no-repeat', backgroundPosition: 'right 10px center',
                    }}
                >
                    <option value="">All workflows</option>
                    {workflows.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}
                </select>
            </div>

            {executions.length === 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, padding: '60px 24px' }}>
                    <svg width="48" height="48" viewBox="0 0 48 48" fill="none" style={{ opacity: 0.25 }}>
                        <circle cx="24" cy="24" r="18" stroke="currentColor" strokeWidth="2" strokeDasharray="4 3"/>
                        <path d="M24 14v10l7 4" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
                    </svg>
                    <span style={{ fontSize: 16, fontWeight: 600, color: 'var(--text2)' }}>No executions yet</span>
                    <span style={{ fontSize: 13, color: 'var(--text3)', maxWidth: 260, textAlign: 'center' }}>Execute a workflow to see results here</span>
                </div>
            ) : (
                <div style={{ border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden', background: 'var(--bg2)' }}>
                    {/* Column headers */}
                    <div style={{
                        display: 'grid', gridTemplateColumns: COL_GRID,
                        padding: '9px 20px', fontSize: 11, fontWeight: 500,
                        color: 'var(--text3)', borderBottom: '1px solid var(--border)',
                        background: 'var(--bg3)',
                    }}>
                        <span>Execution ID</span>
                        <span>Workflow</span>
                        <span>Status</span>
                        <span>Started</span>
                        <span>Duration</span>
                        <span>Error</span>
                    </div>

                    {/* Rows */}
                    {executions.map((exec, i) => {
                        const badge = STATUS_BADGE[exec.status] ?? STATUS_BADGE.CANCELLED;
                        const isRunning = exec.status === 'RUNNING';
                        const isLast = i === executions.length - 1;
                        return (
                            <div
                                key={exec.id}
                                style={{
                                    display: 'grid', gridTemplateColumns: COL_GRID,
                                    padding: '13px 20px', fontSize: 12, alignItems: 'center',
                                    borderBottom: isLast ? 'none' : '1px solid var(--border)',
                                    cursor: 'pointer',
                                }}
                                onMouseEnter={e => e.currentTarget.style.background = 'var(--bg3)'}
                                onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
                            >
                                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text3)' }}>
                                    {exec.id.slice(0, 16)}…
                                </span>
                                <span style={{ fontWeight: 500, color: 'var(--text)' }}>{exec.workflowName}</span>
                                <span>
                                    <span style={{
                                        display: 'inline-flex', alignItems: 'center', gap: 4,
                                        padding: '3px 9px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                                        letterSpacing: '0.02em', background: badge.bg, color: badge.color,
                                        animation: isRunning ? 'pulse 1.5s ease-in-out infinite' : 'none',
                                    }}>
                                        <span style={{ width: 5, height: 5, borderRadius: '50%', background: badge.dot }}></span>
                                        {badge.label}
                                    </span>
                                </span>
                                <span style={{ fontSize: 11, color: 'var(--text3)' }}>
                                    {new Date(exec.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                                </span>
                                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text2)' }}>
                                    {isRunning ? '…' : formatDuration(exec.duration)}
                                </span>
                                <span style={{ fontSize: 11, color: 'var(--red)' }}>
                                    {exec.error ?? ''}
                                </span>
                            </div>
                        );
                    })}
                </div>
            )}
        </Stack>
    );
}
