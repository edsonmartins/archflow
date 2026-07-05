import type { TFunction } from 'i18next'
import { NODE_CATEGORIES, PALETTE_NODES } from './constants'

export interface PaletteEntry {
    componentId: string
    label: string
    description: string
    category: keyof typeof NODE_CATEGORIES
}

/** Localized label for a palette node, falling back to the baked-in English. */
export function paletteLabel(t: TFunction, componentId: string, fallback: string): string {
    return t(`nodes.${componentId}.label`, { defaultValue: fallback })
}

/** Localized description for a palette node. */
export function paletteDescription(t: TFunction, componentId: string, fallback: string): string {
    return t(`nodes.${componentId}.description`, { defaultValue: fallback })
}

/** Localized category label. */
export function paletteCategoryLabel(t: TFunction, category: keyof typeof NODE_CATEGORIES): string {
    return t(`categories.${category}`, { defaultValue: NODE_CATEGORIES[category].label })
}

/**
 * Shared palette search used by the sidebar NodePalette and the
 * drop-a-connection AddNodeMenu, so both boxes return the same results
 * for the same query: matches localized label/description, the category
 * (key and localized label) and the raw componentId.
 */
export function searchPaletteEntries(
    t: TFunction,
    query: string,
    options?: { includeAnnotations?: boolean },
): PaletteEntry[] {
    const q = query.toLowerCase().trim()
    return PALETTE_NODES
        .filter(n => options?.includeAnnotations !== false || n.category !== 'annotation')
        .map(n => ({
            componentId: n.componentId,
            label: paletteLabel(t, n.componentId, n.label),
            description: paletteDescription(t, n.componentId, n.description),
            category: n.category,
        }))
        .filter(n => !q
            || n.label.toLowerCase().includes(q)
            || n.description.toLowerCase().includes(q)
            || n.componentId.includes(q)
            || n.category.toLowerCase().includes(q)
            || paletteCategoryLabel(t, n.category).toLowerCase().includes(q))
}
