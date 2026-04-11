import { workflowApi, type WorkflowDetail } from './api';
import {
    listTemplates as bundledList,
    getTemplate as bundledGet,
    type WorkflowTemplate,
} from '../data/templates';

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

async function resolveList(): Promise<WorkflowTemplate[]> {
    try {
        const external = await loadFromPublic();
        if (external.length > 0) return external;
    } catch (err) {
        console.warn('[templates] using bundled fallback', err);
    }
    return bundledList();
}

/**
 * Warm the cache. Called once from the TemplatesPage on mount.
 */
export async function preloadTemplates(): Promise<WorkflowTemplate[]> {
    if (!listPromise) {
        listPromise = resolveList().then((templates) => {
            byId.clear();
            for (const t of templates) byId.set(t.id, t);
            return templates;
        });
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
            steps: template.steps as unknown[],
            configuration: {
                connections: template.connections,
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
