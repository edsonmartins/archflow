import { useEffect, useRef, DragEvent, FormEvent, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Alert, Box, Button, Group, Modal, Paper, Stack, Textarea, TextInput } from '@mantine/core';
import { useWorkflowStore } from '../stores/workflow-store';
import { useEditorStore } from '../stores/editor-store';
import NodePalette from '../components/NodePalette';
import PropertyEditor from '../components/PropertyEditor';

interface DesignerElement extends HTMLElement {
    setWorkflow?: (workflow: unknown) => void;
    addNode?: (node: unknown) => void;
    updateNode?: (nodeId: string, updates: { label?: string; config?: Record<string, unknown> }) => void;
}

export default function WorkflowEditorPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const { currentWorkflow, fetchWorkflow, createWorkflow, clearCurrent, loading, error } = useWorkflowStore();
    const { isPaletteOpen, isPropertiesOpen, selectNode, selectedNode } = useEditorStore();
    const designerRef = useRef<DesignerElement>(null);
    const [createOpened, setCreateOpened] = useState(false);
    const [workflowName, setWorkflowName] = useState('');
    const [workflowDescription, setWorkflowDescription] = useState('');
    const [componentError, setComponentError] = useState<string | null>(null);

    useEffect(() => {
        if (id) {
            fetchWorkflow(id);
        }
        return () => {
            clearCurrent();
            selectNode(null);
        };
    }, [id, fetchWorkflow, clearCurrent, selectNode]);

    useEffect(() => {
        const el = designerRef.current;
        if (!el) return;

        let cancelled = false;

        if (currentWorkflow && id) {
            customElements.whenDefined('archflow-designer').then(() => {
                if (!cancelled) {
                    designerRef.current?.setWorkflow?.(currentWorkflow);
                }
            });
        }

        const handleNodeSelected = (e: Event) => {
            const detail = (e as CustomEvent).detail;
            if (detail?.nodeId) {
                selectNode(detail.nodeId, {
                    id: detail.nodeId,
                    type: detail.nodeType || 'UNKNOWN',
                    label: detail.nodeLabel || detail.nodeId,
                    position: detail.position || { x: 0, y: 0 },
                    config: detail.config || {},
                });
            }
        };

        const handleSelectionCleared = () => selectNode(null);
        const handleCreateRequested = () => setCreateOpened(true);
        const handleWorkflowSaved = () => setComponentError(null);
        const handleWorkflowExecuted = (e: Event) => {
            setComponentError(null);
            const detail = (e as CustomEvent).detail;
            const executionId = detail?.result?.executionId;
            if (executionId) {
                navigate(`/executions?id=${executionId}`);
            }
        };
        const handleDesignerError = (e: Event) => {
            const detail = (e as CustomEvent).detail;
            if (detail?.message) {
                setComponentError(detail.message);
            }
        };

        el.addEventListener('node-selected', handleNodeSelected);
        el.addEventListener('selection-cleared', handleSelectionCleared);
        el.addEventListener('archflow:create', handleCreateRequested);
        el.addEventListener('workflow-saved', handleWorkflowSaved);
        el.addEventListener('workflow-executed', handleWorkflowExecuted);
        el.addEventListener('error', handleDesignerError);

        return () => {
            cancelled = true;
            el.removeEventListener('node-selected', handleNodeSelected);
            el.removeEventListener('selection-cleared', handleSelectionCleared);
            el.removeEventListener('archflow:create', handleCreateRequested);
            el.removeEventListener('workflow-saved', handleWorkflowSaved);
            el.removeEventListener('workflow-executed', handleWorkflowExecuted);
            el.removeEventListener('error', handleDesignerError);
        };
    }, [currentWorkflow, id, navigate, selectNode]);

    useEffect(() => {
        if (!selectedNode) return;
        customElements.whenDefined('archflow-designer').then(() => {
            designerRef.current?.updateNode?.(selectedNode.id, {
                label: selectedNode.label,
                config: selectedNode.config,
            });
        });
    }, [selectedNode]);

    const handleDrop = (e: DragEvent) => {
        e.preventDefault();
        const data = e.dataTransfer.getData('application/archflow-node');
        if (data) {
            const nodeInfo = JSON.parse(data);
            const rect = (e.target as HTMLElement).getBoundingClientRect();
            const position = {
                x: e.clientX - rect.left,
                y: e.clientY - rect.top,
            };
            // Dispatch to web component
            const el = designerRef.current;
            if (el) {
                el.addNode?.({ ...nodeInfo, position });
            }
        }
    };

    const handleDragOver = (e: DragEvent) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'copy';
    };

    const handleCreateWorkflow = async (e: FormEvent) => {
        e.preventDefault();
        const name = workflowName.trim();
        if (!name) return;

        const created = await createWorkflow({
            metadata: {
                name,
                description: workflowDescription.trim(),
                version: '1.0.0',
                category: 'custom',
                tags: [],
            },
            steps: [],
            configuration: {},
        });

        setCreateOpened(false);
        setWorkflowName('');
        setWorkflowDescription('');
        setComponentError(null);
        navigate(`/editor/${created.id}`);
    };

    return (
        <Stack gap="sm">
            {componentError && <Alert color="red">{componentError}</Alert>}
            {error && !createOpened && <Alert color="red">{error}</Alert>}

            <Group justify="space-between">
                <Button onClick={() => setCreateOpened(true)}>
                    Create Workflow
                </Button>
            </Group>

            <Box style={{ display: 'flex', height: 'calc(100vh - 140px)', gap: 8 }}>
                {isPaletteOpen && (
                    <Paper withBorder p="xs" w={220} style={{ flexShrink: 0, overflow: 'hidden' }}>
                        <NodePalette />
                    </Paper>
                )}

                <Box
                    style={{ flex: 1, position: 'relative' }}
                    data-testid="workflow-dropzone"
                    onDrop={handleDrop}
                    onDragOver={handleDragOver}
                >
                    <archflow-designer
                        ref={designerRef}
                        theme="light"
                        width="100%"
                        height="100%"
                    />
                </Box>

                {isPropertiesOpen && (
                    <Paper withBorder p="xs" w={280} style={{ flexShrink: 0, overflow: 'hidden' }}>
                        <PropertyEditor />
                    </Paper>
                )}
            </Box>

            <Modal opened={createOpened} onClose={() => setCreateOpened(false)} title="Create Workflow" centered>
                <form onSubmit={handleCreateWorkflow}>
                    <Stack gap="md">
                        {error && <Alert color="red">{error}</Alert>}
                        <TextInput
                            label="Name"
                            placeholder="Customer onboarding"
                            required
                            value={workflowName}
                            onChange={(e) => setWorkflowName(e.currentTarget.value)}
                        />
                        <Textarea
                            label="Description"
                            placeholder="Describe what this workflow does"
                            value={workflowDescription}
                            onChange={(e) => setWorkflowDescription(e.currentTarget.value)}
                        />
                        <Group justify="flex-end">
                            <Button variant="light" onClick={() => setCreateOpened(false)}>Cancel</Button>
                            <Button type="submit" loading={loading}>Create</Button>
                        </Group>
                    </Stack>
                </form>
            </Modal>
        </Stack>
    );
}
