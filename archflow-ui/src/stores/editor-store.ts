import { create } from 'zustand';

interface EditorNode {
    id: string;
    type: string;
    label: string;
    position: { x: number; y: number };
    config: Record<string, unknown>;
}

interface EditorState {
    selectedNodeId: string | null;
    selectedNode: EditorNode | null;
    isDirty: boolean;
    isPaletteOpen: boolean;
    isPropertiesOpen: boolean;

    selectNode: (id: string | null, node?: EditorNode) => void;
    updateSelectedNode: (updates: Partial<EditorNode>) => void;
    updateSelectedNodeConfig: (name: string, value: unknown) => void;
    setDirty: (dirty: boolean) => void;
    togglePalette: () => void;
    toggleProperties: () => void;
    setPaletteOpen: (open: boolean) => void;
    setPropertiesOpen: (open: boolean) => void;
    reset: () => void;
}

export const useEditorStore = create<EditorState>((set) => ({
    selectedNodeId: null,
    selectedNode: null,
    isDirty: false,
    isPaletteOpen: true,
    isPropertiesOpen: false,

    selectNode: (id, node) =>
        set({
            selectedNodeId: id,
            selectedNode: node || null,
            isPropertiesOpen: !!id,
        }),

    updateSelectedNode: (updates) =>
        set((state) => ({
            selectedNode: state.selectedNode ? { ...state.selectedNode, ...updates } : null,
            isDirty: state.selectedNode ? true : state.isDirty,
        })),

    updateSelectedNodeConfig: (name, value) =>
        set((state) => ({
            selectedNode: state.selectedNode
                ? {
                    ...state.selectedNode,
                    config: {
                        ...state.selectedNode.config,
                        [name]: value,
                    },
                }
                : null,
            isDirty: state.selectedNode ? true : state.isDirty,
        })),

    setDirty: (dirty) => set({ isDirty: dirty }),
    togglePalette: () => set((s) => ({ isPaletteOpen: !s.isPaletteOpen })),
    toggleProperties: () => set((s) => ({ isPropertiesOpen: !s.isPropertiesOpen })),
    setPaletteOpen: (open) => set({ isPaletteOpen: open }),
    setPropertiesOpen: (open) => set({ isPropertiesOpen: open }),
    reset: () => set({ selectedNodeId: null, selectedNode: null, isDirty: false }),
}));
