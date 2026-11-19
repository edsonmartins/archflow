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
    IconSitemap, IconScale,
} from '@tabler/icons-react'

/**
 * Central mapping from {@code componentId} → Tabler icon. Used by
 * both the canvas node card ({@code ShapedNode}) and the palette
 * badge ({@code PaletteShapeIcon}) so the icon is consistent
 * everywhere. Emojis and ASCII glyphs are banned here: Tabler icons
 * render crisply at every zoom level and inherit the badge colour via
 * {@code currentColor}. Nodes with their own artwork ({@link IMAGE_MAP})
 * render that instead — see {@link hasNodeImage}.
 */
const ICON_MAP: Record<string, ComponentType<{ size?: number; stroke?: number }>> = {
    // AI / agents
    'agent':            IconRobot,
    'assistant':        IconBrain,
    'llm-chat':         IconMessageCircle,
    'llm-streaming':    IconBolt,
    'orchestrate':      IconSitemap,

    // Control flow
    'condition':        IconGitBranch,
    'switch':           IconRoute,
    'loop':             IconRotate,
    'parallel':         IconArrowsSplit2,
    'approval':         IconHandStop,
    'decision':         IconScale,
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
 * Arte própria dos nós (PNG 64 px + @2x 128 px, fundo transparente), escolhida pelo símbolo e
 * pela paleta do manifesto do design system de ícones. Todos vêm de arquivos avulsos (fontes de
 * 300–600 px, só reduzidas — nunca ampliadas).
 *
 * Tem precedência sobre o Tabler: quem está aqui é desenhado com a imagem colorida e **sem** o
 * badge com degradê da categoria (a arte já traz forma e cor próprias — pô-la sobre o degradê dá
 * um ícone dentro de outro). Quem não está continua no Tabler acima, como antes.
 */
const IMAGE_FILES = import.meta.glob<string>('../../assets/node-icons/*.png', {
    eager: true, query: '?url', import: 'default',
})

function imageUrl(file: string, retina = false): string | undefined {
    return IMAGE_FILES[`../../assets/node-icons/${file}${retina ? '@2x' : ''}.png`]
}

const IMAGE_MAP: Record<string, string> = {
    // AI / agents
    'agent':            'agent',
    'assistant':        'assistant',
    'llm-chat':         'llm-chat',
    'llm-streaming':    'llm-streaming',
    'orchestrate':      'orchestrate',

    // Control flow
    'condition':        'condition',
    'parallel':         'parallel-flow',
    'loop':             'loop',
    'switch':           'switch',
    'approval':         'approval',
    'decision':         'decision',
    'subflow':          'subflow',

    // Data
    'transform':        'transform',
    'map':              'map',
    'filter':           'filter',
    'reduce':           'reduce',
    'merge':            'merge',

    // Tools
    'tool':             'tool',
    'function':         'function-f',
    'custom':           'custom',
    'mcp-tool':         'mcp-tool',

    // Vector / memory
    'vector-search':    'vector-search',
    'vector-store':     'vector-store',
    'embedding':        'embedding',
    'rag':              'rag',
    'memory':           'memory',

    // I/O
    'prompt-template':  'prompt-template',
    'prompt-chunk':     'prompt-chunk',
    'input':            'input',
    'output':           'output',

    // Knowledge / integration / annotations
    'skills':           'skills',
    'linktor-send':     'linktor-send',
    'linktor-escalate': 'linktor-escalate',
    'sticky-note':      'sticky-note',
    'group-frame':      'group-frame',
    'section-divider':  'section-divider',
}

/** O nó tem arte própria — quem desenha o badge deve dispensar o degradê da categoria. */
export function hasNodeImage(componentId: string | undefined): boolean {
    return !!componentId && !!IMAGE_MAP[componentId] && !!imageUrl(IMAGE_MAP[componentId])
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
    componentId, size = 18, stroke = 2, imageSize,
}: {
    componentId: string | undefined
    size?: number
    stroke?: number
    /** Tamanho da arte própria, quando houver (ela preenche o badge; o Tabler usa {@code size}). */
    imageSize?: number
}) {
    const file = componentId ? IMAGE_MAP[componentId] : undefined
    const src = file ? imageUrl(file) : undefined
    if (file && src) {
        const px = imageSize ?? size
        return (
            <img
                src={src}
                srcSet={`${src} 1x, ${imageUrl(file, true) ?? src} 2x`}
                width={px}
                height={px}
                alt=""
                aria-hidden="true"
                draggable={false}
                data-node-icon={file}
                style={{ display: 'block', pointerEvents: 'none' }}
            />
        )
    }
    const Icon = nodeIconFor(componentId)
    return <Icon size={size} stroke={stroke} />
}
