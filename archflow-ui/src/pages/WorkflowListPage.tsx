import {
    Title, Button, Group, Text, TextInput,
    Stack, LoadingOverlay, Modal, Alert, SimpleGrid,
} from '@mantine/core';
import {
    IconPlus, IconSearch, IconPlayerPlay, IconPencil, IconTrash,
    IconAlertCircle,
} from '@tabler/icons-react';
import { useEffect, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useWorkflowStore } from '../stores/workflow-store';

const STATUS_BADGE: Record<string, { bg: string; color: string; dot: string }> = {
    active:  { bg: 'var(--green-l)', color: 'var(--green)', dot: 'var(--green)' },
    draft:   { bg: 'var(--gray-l)',  color: 'var(--gray)',  dot: 'var(--gray)' },
    paused:  { bg: 'var(--amber-l)', color: 'var(--amber)', dot: 'var(--amber)' },
    failed:  { bg: 'var(--red-l)',   color: 'var(--red)',   dot: 'var(--red)' },
};

const CAT_ICONS: Record<string, { emoji: string; bg: string }> = {
    active:  { emoji: '🤖', bg: 'var(--cat-agent-l)' },
    draft:   { emoji: '📄', bg: 'var(--cat-tool-l)' },
    paused:  { emoji: '⏸', bg: 'var(--cat-io-l)' },
    failed:  { emoji: '⚠', bg: 'var(--red-l)' },
};

export default function WorkflowListPage() {
    const navigate = useNavigate();
    const { workflows, loading, error, fetchWorkflows, deleteWorkflow, executeWorkflow } = useWorkflowStore();
    const [search, setSearch] = useState('');
    const [deleteId, setDeleteId] = useState<string | null>(null);

    useEffect(() => { fetchWorkflows() }, [fetchWorkflows]);

    const filtered = useMemo(() => {
        const q = search.toLowerCase().trim();
        if (!q) return workflows;
        return workflows.filter(w => w.name.toLowerCase().includes(q) || w.description.toLowerCase().includes(q));
    }, [search, workflows]);

    const handleDelete = async () => { if (deleteId) { await deleteWorkflow(deleteId); setDeleteId(null); } };
    const handleExecute = async (id: string) => { try { const eid = await executeWorkflow(id); navigate(`/executions?id=${eid}`); } catch {} };

    return (
        <Stack gap="md" pos="relative">
            <LoadingOverlay visible={loading} />
            {error && <Alert color="red" icon={<IconAlertCircle size={16} />}>{error}</Alert>}

            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 2 }}>
                <span style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.3px', color: 'var(--text)' }}>Workflows</span>
                <button
                    onClick={() => navigate('/editor')}
                    style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6,
                        padding: '7px 16px', borderRadius: 7, fontSize: 13, fontWeight: 500,
                        cursor: 'pointer', fontFamily: 'var(--font-sans)',
                        background: 'var(--blue)', border: '1px solid var(--blue-d)', color: '#fff',
                    }}
                >
                    <svg width="13" height="13" viewBox="0 0 13 13" fill="none"><path d="M6.5 1v11M1 6.5h11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/></svg>
                    New Workflow
                </button>
            </div>

            {/* Search */}
            <div style={{ position: 'relative', marginBottom: 4 }}>
                <svg style={{ position: 'absolute', left: 11, top: '50%', transform: 'translateY(-50%)', color: 'var(--text4)', pointerEvents: 'none' }} width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4.5" stroke="currentColor" strokeWidth="1.3"/><path d="M9.5 9.5L13 13" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round"/></svg>
                <input
                    value={search}
                    onChange={e => setSearch(e.target.value)}
                    placeholder="Search workflows…"
                    style={{
                        width: '100%', padding: '9px 14px 9px 36px', borderRadius: 8,
                        border: '1px solid var(--border)', background: 'var(--bg3)',
                        fontSize: 13, color: 'var(--text)', fontFamily: 'var(--font-sans)', outline: 'none',
                    }}
                />
            </div>

            {filtered.length === 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, padding: '60px 24px' }}>
                    <svg width="64" height="64" viewBox="0 0 64 64" fill="none" style={{ opacity: 0.25 }}>
                        <circle cx="16" cy="32" r="7" stroke="currentColor" strokeWidth="2" strokeDasharray="4 3"/>
                        <circle cx="48" cy="18" r="7" stroke="currentColor" strokeWidth="2" strokeDasharray="4 3"/>
                        <circle cx="48" cy="46" r="7" stroke="currentColor" strokeWidth="2" strokeDasharray="4 3"/>
                        <line x1="23" y1="29" x2="41" y2="20" stroke="currentColor" strokeWidth="2" strokeDasharray="4 3"/>
                        <line x1="23" y1="35" x2="41" y2="44" stroke="currentColor" strokeWidth="2" strokeDasharray="4 3"/>
                    </svg>
                    <span style={{ fontSize: 16, fontWeight: 600, color: 'var(--text2)' }}>
                        {workflows.length === 0 ? 'No workflows yet' : 'No matching workflows'}
                    </span>
                    <span style={{ fontSize: 13, color: 'var(--text3)', maxWidth: 260, textAlign: 'center', lineHeight: 1.5 }}>
                        {workflows.length === 0 ? 'Create your first AI workflow to get started' : 'Try adjusting your search terms'}
                    </span>
                    {workflows.length === 0 && (
                        <button onClick={() => navigate('/editor')} style={{
                            padding: '7px 16px', borderRadius: 7, fontSize: 13, fontWeight: 500, cursor: 'pointer',
                            fontFamily: 'var(--font-sans)', background: 'var(--blue)', border: '1px solid var(--blue-d)', color: '#fff',
                            display: 'inline-flex', alignItems: 'center', gap: 6, marginTop: 4,
                        }}>
                            <svg width="13" height="13" viewBox="0 0 13 13" fill="none"><path d="M6.5 1v11M1 6.5h11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/></svg>
                            Create Workflow
                        </button>
                    )}
                </div>
            ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
                    {filtered.map(w => {
                        const badge = STATUS_BADGE[w.status] ?? STATUS_BADGE.draft;
                        const cat = CAT_ICONS[w.status] ?? CAT_ICONS.draft;
                        return (
                            <div
                                key={w.id}
                                onClick={() => navigate(`/editor/${w.id}`)}
                                style={{
                                    background: 'var(--bg2)', border: '1px solid var(--border)',
                                    borderRadius: 10, padding: 16, cursor: 'pointer',
                                    transition: 'border-color 0.1s, box-shadow 0.1s',
                                }}
                                onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--border2)'; e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.05)'; }}
                                onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.boxShadow = 'none'; }}
                            >
                                {/* Card top: icon + name */}
                                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
                                    <div style={{
                                        width: 38, height: 38, borderRadius: 9,
                                        background: cat.bg, display: 'flex', alignItems: 'center', justifyContent: 'center',
                                        fontSize: 17, flexShrink: 0,
                                    }}>{cat.emoji}</div>
                                    <div>
                                        <div style={{ fontSize: 14, fontWeight: 600, lineHeight: 1.3, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{w.name}</div>
                                        <div style={{ fontSize: 12, color: 'var(--text3)', marginTop: 2, lineHeight: 1.4 }}>{w.description}</div>
                                    </div>
                                </div>

                                {/* Meta: badge + version + steps */}
                                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
                                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '3px 9px', borderRadius: 4, fontSize: 11, fontWeight: 600, letterSpacing: '0.02em', background: badge.bg, color: badge.color }}>
                                        <span style={{ width: 5, height: 5, borderRadius: '50%', background: badge.dot, flexShrink: 0 }}></span>
                                        {w.status.charAt(0).toUpperCase() + w.status.slice(1)}
                                    </span>
                                    <span style={{ fontSize: 10, color: 'var(--text4)', fontFamily: 'var(--font-mono)', background: 'var(--bg3)', padding: '2px 7px', borderRadius: 5 }}>{w.version}</span>
                                    <span style={{ fontSize: 11, color: 'var(--text3)' }}>{w.stepCount} steps</span>
                                </div>

                                <div style={{ fontSize: 11, color: 'var(--text4)', marginBottom: 10 }}>{new Date(w.updatedAt).toLocaleDateString()}</div>

                                {/* Actions with border-top */}
                                <div style={{ display: 'flex', gap: 4, borderTop: '1px solid var(--border)', paddingTop: 10 }} onClick={e => e.stopPropagation()}>
                                    <button title="Execute" className="wf-act-btn" onClick={() => handleExecute(w.id)}
                                        style={actBtnStyle}>
                                        <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M2.5 1.5l7 4.5-7 4.5V1.5z" fill="#6B7280"/></svg>
                                    </button>
                                    <button title="Edit" className="wf-act-btn" onClick={() => navigate(`/editor/${w.id}`)}
                                        style={actBtnStyle}>
                                        <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M8.5 1.5l2 2L3.5 11H1.5v-2L8.5 1.5z" stroke="#6B7280" strokeWidth="1.2" strokeLinejoin="round"/></svg>
                                    </button>
                                    <button title="Delete" className="wf-act-btn-danger" onClick={() => setDeleteId(w.id)}
                                        style={actBtnStyle}
                                        onMouseEnter={e => { e.currentTarget.style.background = '#FEF2F2'; }}
                                        onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; }}>
                                        <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M1.5 3h9M4.5 3V2a.5.5 0 01.5-.5h2a.5.5 0 01.5.5v1M3.5 3l.5 6.5h4l.5-6.5" stroke="#DC2626" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/></svg>
                                    </button>
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            <Modal opened={!!deleteId} onClose={() => setDeleteId(null)} title="Delete Workflow" centered>
                <Text size="sm">Are you sure? This action cannot be undone.</Text>
                <Group justify="flex-end" mt="md">
                    <Button variant="light" onClick={() => setDeleteId(null)}>Cancel</Button>
                    <Button color="red" onClick={handleDelete}>Delete</Button>
                </Group>
            </Modal>
        </Stack>
    );
}

const actBtnStyle: React.CSSProperties = {
    width: 28, height: 28, borderRadius: 6,
    border: '1px solid rgba(0,0,0,0.07)', background: 'transparent',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    cursor: 'pointer', padding: 0,
};
