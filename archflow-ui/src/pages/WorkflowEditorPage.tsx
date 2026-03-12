import { useEffect, useRef, DragEvent } from 'react';
import { useParams } from 'react-router-dom';
import { Box, Paper } from '@mantine/core';
import { useWorkflowStore } from '../stores/workflow-store';
import { useEditorStore } from '../stores/editor-store';
import NodePalette from '../components/NodePalette';
import PropertyEditor from '../components/PropertyEditor';

export default function WorkflowEditorPage() {
    const { id } = useParams<{ id: string }>();
    const { currentWorkflow, fetchWorkflow, clearCurrent } = useWorkflowStore();
    const { isPaletteOpen, isPropertiesOpen, selectNode } = useEditorStore();
    const designerRef = useRef<HTMLElement>(null);

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

        if (currentWorkflow && id) {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            (el as any).setWorkflow?.(currentWorkflow);
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

        el.addEventListener('node-selected', handleNodeSelected);
        el.addEventListener('selection-cleared', handleSelectionCleared);

        return () => {
            el.removeEventListener('node-selected', handleNodeSelected);
            el.removeEventListener('selection-cleared', handleSelectionCleared);
        };
    }, [currentWorkflow, id, selectNode]);

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
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                (el as any).addNode?.({ ...nodeInfo, position });
            }
        }
    };

    const handleDragOver = (e: DragEvent) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'copy';
    };

    return (
        <Box style={{ display: 'flex', height: 'calc(100vh - 100px)', gap: 8 }}>
            {isPaletteOpen && (
                <Paper withBorder p="xs" w={220} style={{ flexShrink: 0, overflow: 'hidden' }}>
                    <NodePalette />
                </Paper>
            )}

            <Box
                style={{ flex: 1, position: 'relative' }}
                onDrop={handleDrop}
                onDragOver={handleDragOver}
            >
                <archflow-designer
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    ref={designerRef as any}
                    workflow-id={id || ''}
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
    );
}
