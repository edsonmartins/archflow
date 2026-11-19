import { describe, it, expect } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { NodeIcon, hasNodeImage } from '../nodeIcons'
import { PALETTE_NODES } from '../constants'

const COM_ARTE = [
    'agent', 'assistant', 'llm-chat', 'llm-streaming', 'orchestrate', 'condition', 'parallel',
    'loop', 'switch', 'approval', 'decision', 'subflow', 'mcp-tool', 'reduce', 'tool', 'custom', 'transform', 'map', 'filter', 'merge', 'function', 'vector-search', 'vector-store',
    'embedding', 'rag', 'memory', 'prompt-template', 'prompt-chunk', 'input', 'output',
    'skills', 'linktor-send', 'linktor-escalate', 'sticky-note', 'group-frame', 'section-divider',
]

describe('ícones dos nós', () => {
    it.each(COM_ARTE)('%s usa a arte própria, com @1x e @2x', (id) => {
        expect(hasNodeImage(id)).toBe(true)
        const html = renderToStaticMarkup(createElement(NodeIcon, { componentId: id, imageSize: 30 }))
        expect(html).toContain('<img')
        expect(html).toMatch(/ 1x, .+ 2x/)
        expect(html).toContain('width="30"')
    })

    it('id sem arte cai no Tabler', () => {
        expect(hasNodeImage('nao-existe')).toBe(false)
        const html = renderToStaticMarkup(createElement(NodeIcon, { componentId: 'nao-existe' }))
        expect(html).toContain('<svg')
        expect(html).not.toContain('<img')
    })

    it('todo nó da paleta tem arte própria', () => {
        expect(PALETTE_NODES.filter((n) => !hasNodeImage(n.componentId)).map((n) => n.componentId)).toEqual([])
    })

    it('todo nó mapeado existe na paleta (nada de arte para id que não existe)', () => {
        const ids = new Set(PALETTE_NODES.map((n) => n.componentId))
        expect(COM_ARTE.filter((id) => !ids.has(id))).toEqual([])
    })
})
