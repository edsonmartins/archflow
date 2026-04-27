import { type ComponentType } from 'react'
import {
    IconRobot, IconBrain, IconMessageCircle, IconBolt,
    IconGitBranch, IconRoute, IconRotate, IconArrowsSplit2,
    IconArrowsShuffle, IconFunction, IconSparkles,
    IconTool, IconCode,
    IconSearch, IconDatabase, IconWaveSine, IconBooks, IconArchive,
    IconFileText, IconAlignLeft, IconLogin2, IconLogout2,
    IconBook2, IconHandStop, IconStackPush, IconPlug,
    IconSend, IconHeadset,
    IconTransform,
    IconFilter, IconFunctionFilled,
    IconArrowsMaximize,
    IconArrowMerge,
} from '@tabler/icons-react'

/**
 * Central mapping from {@code componentId} → Tabler icon. Used by
 * both the canvas node card ({@code ShapedNode}) and the palette
 * badge ({@code PaletteShapeIcon}) so the icon is consistent
 * everywhere. Emojis and ASCII glyphs are banned here: Tabler icons
 * render crisply at every zoom level and inherit the badge colour via
 * {@code currentColor}.
 */
const ICON_MAP: Record<string, ComponentType<{ size?: number; stroke?: number }>> = {
    // AI / agents
    'agent':            IconRobot,
    'assistant':        IconBrain,
    'llm-chat':         IconMessageCircle,
    'llm-streaming':    IconBolt,

    // Control flow
    'condition':        IconGitBranch,
    'switch':           IconRoute,
    'loop':             IconRotate,
    'parallel':         IconArrowsSplit2,
    'approval':         IconHandStop,
    'subflow':          IconStackPush,

    // Data
    'transform':        IconTransform,
    'map':              IconArrowsShuffle,
    'filter':           IconFilter,
    'reduce':           IconFunctionFilled,
    'merge':            IconArrowMerge,

    // Tools
    'tool':             IconTool,
    'function':         IconFunction,
    'custom':           IconSparkles,
    'mcp-tool':         IconPlug,

    // Vector / memory
    'vector-search':    IconSearch,
    'vector-store':     IconDatabase,
    'embedding':        IconWaveSine,
    'rag':              IconBooks,
    'memory':           IconArchive,

    // I/O
    'prompt-template':  IconFileText,
    'prompt-chunk':     IconAlignLeft,
    'input':            IconLogin2,
    'output':           IconLogout2,

    // Knowledge
    'skills':           IconBook2,

    // Integration
    'linktor-send':     IconSend,
    'linktor-escalate': IconHeadset,
}

/**
 * Fallback icon for anything not listed — shown as a sparkle so it's
 * obvious a mapping is missing rather than silently hiding.
 */
const FALLBACK_ICON: ComponentType<{ size?: number; stroke?: number }> = IconSparkles

export function nodeIconFor(componentId: string | undefined): ComponentType<{ size?: number; stroke?: number }> {
    if (!componentId) return FALLBACK_ICON
    return ICON_MAP[componentId] ?? FALLBACK_ICON
}

/**
 * Convenience renderer: renders the icon at a given size and stroke
 * weight, letting the parent control colour via CSS. Most callers
 * will prefer this over reaching for {@link nodeIconFor} directly.
 */
export function NodeIcon({
    componentId, size = 18, stroke = 2,
}: {
    componentId: string | undefined
    size?: number
    stroke?: number
}) {
    const Icon = nodeIconFor(componentId)
    return <Icon size={size} stroke={stroke} />
}
