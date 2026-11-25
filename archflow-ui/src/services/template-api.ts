import { api, workflowApi, type WorkflowDetail } from './api';
import {
    listTemplates as bundledList,
    getTemplate as bundledGet,
    type WorkflowTemplate,
} from '../data/templates';
import type { WorkflowStep } from '../components/FlowCanvas/types';
import { NODE_TYPE_TO_CATEGORY } from '../components/FlowCanvas/constants';
import { canvasToDocumentSteps, connectionsFromSteps } from '../components/FlowCanvas/workflowSerde';

/** Um passo do documento canônico, como o backend o manda (arestas em `connections`). */
interface BackendStep {
    id: string;
    type?: string;
    componentId?: string;
    label?: string;
    position?: { x: number; y: number };
    configuration?: Record<string, unknown>;
    config?: Record<string, unknown>;
    connections?: {
        sourceId?: string;
        targetId?: string;
        isErrorPath?: boolean;
        condition?: string;
        branch?: string;
    }[];
}

/**
 * Shape returned by {@code GET /api/templates}. We only consume the
 * fields needed for the gallery; the backend may add others over time.
 */
export interface BackendTemplate {
    id: string;
    displayName: string;
    description: string;
    category: string;
    version?: string;
    author?: string;
    icon?: string;
    complexity?: string;
    tags?: string[];
    stepCount?: number;
    /** O grafo real do template (RCHFLW-18). Antes não vinha, e o clone nascia vazio. */
    steps?: BackendStep[];
    /** Tipos de nó sem executor no motor; `null`/ausente = não verificado. */
    unsupportedNodeTypes?: string[] | null;
}

/** Do documento canônico para o formato do designer, que é o que a galeria e o canvas usam. */
function toDesignerSteps(steps: BackendStep[]): WorkflowStep[] {
    return steps.map((s, i) => {
        // O type cru é preservado (o roteamento de adapter casa por ele); só a caixa muda, como no
        // carregamento do editor.
        const type = (s.type ?? s.componentId ?? 'custom').toLowerCase();
        return {
            id: s.id,
            type,
            componentId: s.componentId ?? type,
            label: s.label ?? s.id,
            category: NODE_TYPE_TO_CATEGORY[type] ?? 'tool',
            position: s.position ?? { x: 80 + i * 260, y: 200 },
            config: { ...(s.config ?? {}), ...(s.configuration ?? {}) },
        };
    });
}

export function toWorkflowTemplate(bt: BackendTemplate): WorkflowTemplate {
    const fallback = bundledGet(bt.id);
    const backendSteps = bt.steps ?? [];
    const graph = backendSteps.length > 0
        ? { steps: toDesignerSteps(backendSteps), connections: connectionsFromSteps(backendSteps) }
        : { steps: fallback?.steps ?? [], connections: fallback?.connections ?? [] };
    // A marcação do backend vale para os passos DELE; com o grafo local ela é refeita depois.
    const unsupported = backendSteps.length > 0 && Array.isArray(bt.unsupportedNodeTypes)
        ? { unsupportedNodeTypes: bt.unsupportedNodeTypes }
        : {};
    return fallback
        ? {
            ...fallback,
            ...graph,
            ...unsupported,
            name: bt.displayName ?? fallback.name,
            summary: bt.description ?? fallback.summary,
        }
        : {
            id: bt.id,
            name: bt.displayName,
            icon: bt.icon ?? '📄',
            summary: bt.description,
            description: bt.description,
            category: (bt.category as WorkflowTemplate['category']) ?? 'productivity',
            tags: bt.tags ?? [],
            complexity: (bt.complexity as WorkflowTemplate['complexity']) ?? 'starter',
            ...graph,
            ...unsupported,
        };
}

/**
 * Junta o catálogo do backend com o local (public/ ou o pacote), sem repetir id: o backend vence
 * no que traz, o local mantém o resto. Antes a lista do backend SUBSTITUÍA a local, e os 6
 * templates com passos sumiam da galeria sempre que o servidor respondia (RCHFLW-46).
 */
export function mergeCatalogs(local: WorkflowTemplate[], backend: WorkflowTemplate[]): WorkflowTemplate[] {
    const merged = new Map<string, WorkflowTemplate>();
    for (const t of local) merged.set(t.id, t);
    for (const t of backend) merged.set(t.id, t);
    return Array.from(merged.values());
}

/** O documento mínimo que o motor lê para decidir se um nó tem executor. */
function documentStepsForCheck(template: WorkflowTemplate): BackendStep[] {
    return (template.steps ?? []).map((s) => ({
        id: s.id,
        type: s.type,
        componentId: s.componentId,
        configuration: s.config ?? {},
    }));
}

/**
 * Pergunta ao backend quais tipos de nó dos templates locais não têm executor — a mesma regra
 * que ele aplica aos dele (constrói cada nó com o factory do motor). Sem backend a marcação
 * fica desconhecida (`undefined`): melhor não afirmar nada do que afirmar que roda.
 */
async function markUnsupported(templates: WorkflowTemplate[]): Promise<WorkflowTemplate[]> {
    const pending = templates.filter((t) => t.unsupportedNodeTypes === undefined && (t.steps ?? []).length > 0);
    if (pending.length === 0) return templates;
    try {
        const body: Record<string, BackendStep[]> = {};
        for (const t of pending) body[t.id] = documentStepsForCheck(t);
        const result = await api.post<Record<string, string[]>>('/templates/unsupported-node-types', body);
        return templates.map((t) =>
            Array.isArray(result?.[t.id]) ? { ...t, unsupportedNodeTypes: result[t.id] } : t,
        );
    } catch (err) {
        console.warn('[templates] could not check node support', err);
        return templates;
    }
}

async function loadFromBackend(): Promise<WorkflowTemplate[]> {
    const items = await api.get<BackendTemplate[]>('/templates');
    return items.map(toWorkflowTemplate);
}

/**
 * Templates are served as static JSON files from `/templates/index.json`
 * plus one file per template. This lets operators drop new receitas into
 * the deploy without rebuilding the bundle — just update the `public/`
 * directory (or mount it as a volume).
 *
 * The TS bundle in `src/data/templates.ts` is kept as an offline fallback:
 * if the network fetch fails (dev without vite server, offline demo, etc)
 * the client falls back to the bundled catalog so the gallery still
 * renders. The bundled list is also the source of truth for the static
 * JSON files — both are kept in sync by hand and the unit test in
 * `data/templates.ts` validates that `getTemplate` is stable.
 *
 * All results are memoized in-memory. A single `list()` call triggers
 * the index fetch; subsequent calls reuse the cached list.
 */

const TEMPLATES_BASE = '/templates';

let listPromise: Promise<WorkflowTemplate[]> | null = null;
const byId = new Map<string, WorkflowTemplate>();

async function fetchJson<T>(path: string): Promise<T> {
    const response = await fetch(path, { headers: { Accept: 'application/json' } });
    if (!response.ok) {
        throw new Error(`Failed to fetch ${path}: ${response.status}`);
    }
    return response.json() as Promise<T>;
}

async function loadFromPublic(): Promise<WorkflowTemplate[]> {
    const index = await fetchJson<{
        version: number;
        templates: { id: string; file: string }[];
    }>(`${TEMPLATES_BASE}/index.json`);

    const loaded = await Promise.all(
        index.templates.map(async (entry) => {
            try {
                const template = await fetchJson<WorkflowTemplate>(
                    `${TEMPLATES_BASE}/${entry.file}`,
                );
                return template;
            } catch (err) {
                console.warn(`[templates] failed to load ${entry.file}`, err);
                return bundledGet(entry.id) ?? null;
            }
        }),
    );

    return loaded.filter((t): t is WorkflowTemplate => t !== null);
}

async function loadLocal(): Promise<WorkflowTemplate[]> {
    // public/ JSON → pacote. Cada fonte pode falhar sozinha.
    try {
        const external = await loadFromPublic();
        if (external.length > 0) return external;
    } catch (err) {
        console.warn('[templates] using bundled fallback', err);
    }
    return bundledList();
}

async function resolveList(): Promise<WorkflowTemplate[]> {
    // Backend e local MESCLADOS (backend vence por id): um não esconde o outro.
    const [backend, local] = await Promise.all([
        loadFromBackend().catch((err) => {
            console.warn('[templates] backend unavailable, using the local catalog only', err);
            return [] as WorkflowTemplate[];
        }),
        loadLocal(),
    ]);
    return markUnsupported(mergeCatalogs(local, backend));
}

/**
 * Warm the cache. Called once from the TemplatesPage on mount.
 */
export async function preloadTemplates(): Promise<WorkflowTemplate[]> {
    if (!listPromise) {
        const pending = resolveList().then((templates) => {
            byId.clear();
            for (const t of templates) byId.set(t.id, t);
            // Verificação de nós que falhou não fica no cache da sessão: a próxima chamada tenta de
            // novo (a falha pode ter sido passageira), em vez de manter o "não verificado" até o reload.
            if (templates.some((t) => t.unsupportedNodeTypes === undefined && (t.steps ?? []).length > 0)
                && listPromise === pending) {
                listPromise = null;
            }
            return templates;
        });
        listPromise = pending;
    }
    return listPromise;
}

/**
 * Returns all templates. Uses the cache when possible; falls back to
 * the bundled list for the first synchronous access.
 */
export function listTemplatesSync(): WorkflowTemplate[] {
    if (byId.size > 0) return Array.from(byId.values());
    return bundledList();
}

export function getTemplateSync(id: string): WorkflowTemplate | undefined {
    if (byId.has(id)) return byId.get(id);
    return bundledGet(id);
}

export const templateApi = {
    /** Async list from public/ with bundled fallback + in-memory cache. */
    list: preloadTemplates,

    /** Sync accessor that uses the cache if warmed, else the bundled list. */
    listSync: listTemplatesSync,

    /** Sync getter; works the same way as listSync. */
    get: getTemplateSync,

    /**
     * Clone a template into the user's workspace. Returns the persisted
     * workflow id so the caller can navigate straight to the editor.
     */
    async clone(templateId: string, options?: { name?: string }): Promise<WorkflowDetail> {
        const template = getTemplateSync(templateId);
        if (!template) {
            throw new Error(`Template not found: ${templateId}`);
        }

        const payload: Partial<WorkflowDetail> = {
            metadata: {
                name: options?.name ?? `${template.name} (copy)`,
                description: template.summary,
                version: '1.0.0',
                category: template.category,
                tags: [...template.tags, 'from-template', `template:${template.id}`],
            },
            // Documento canônico: config em `configuration` e arestas dentro de cada passo — o que o
            // editor e o motor leem. Gravar o formato do designer (config em `config`, arestas em
            // `configuration.connections`) abria o clone sem arestas e o primeiro Salvar apagava a
            // config (RCHFLW-45).
            steps: canvasToDocumentSteps(template.steps ?? [], template.connections ?? []),
            configuration: {
                variables: template.variables ?? {},
                templateId: template.id,
            },
        };

        return workflowApi.create(payload);
    },

    /** Test helper — wipe the in-memory cache. */
    _resetCache(): void {
        listPromise = null;
        byId.clear();
    },
};
