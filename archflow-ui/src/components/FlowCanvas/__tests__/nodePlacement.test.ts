/**
 * RCHFLW-77 — id e posição de nós criados em lote (fila do copiloto): o lote inteiro roda no mesmo
 * milissegundo e antes de qualquer render.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { freePosition, isInView, newNodeId } from '../nodePlacement'

afterEach(() => vi.restoreAllMocks())

describe('newNodeId', () => {
  it('não repete no mesmo milissegundo', () => {
    vi.spyOn(Date, 'now').mockReturnValue(42)
    const ids = Array.from({ length: 50 }, () => newNodeId())
    expect(new Set(ids).size).toBe(50)
  })

  it('pula ids já ocupados no canvas', () => {
    vi.spyOn(Date, 'now').mockReturnValue(7)
    const probe = newNodeId()
    const next = probe.replace(/-(\d+)$/, (_, n) => `-${Number(n) + 1}`)
    expect(newNodeId(new Set([next]))).not.toBe(next)
  })
})

describe('freePosition', () => {
  const at = (x: number, y: number) => ({ position: { x, y } })
  // O nó tem 240 x ~90: a vaga do centro tem o canto superior esquerdo meio nó para cima e para a esquerda.
  const CENTRE = { x: 1000, y: 500 }
  const ORIGIN = { x: 880, y: 455 }

  function place(n: number, centre = CENTRE, existing: { position: { x: number; y: number } }[] = []) {
    const placed = [...existing]
    for (let i = 0; i < n; i++) {
      const p = freePosition(centre, placed)
      placed.push(at(p.x, p.y))
    }
    return placed.slice(existing.length).map(p => p.position)
  }

  it('canvas vazio: o nó fica centrado no ponto pedido (o centro da viewport)', () => {
    expect(freePosition(CENTRE, [])).toEqual(ORIGIN)
  })

  it('nós em sequência não se sobrepõem e seguem a ordem de leitura: direita, 5 por linha, depois abaixo', () => {
    const ps = place(7)
    const cols = ps.map(p => (p.x - ORIGIN.x) / 280)
    const rows = ps.map(p => (p.y - ORIGIN.y) / 140)
    expect(cols).toEqual([0, 1, 2, 3, 4, 0, 1])
    expect(rows).toEqual([0, 0, 0, 0, 0, 1, 1])
  })

  it('canvas ocupado no começo da grade: a próxima vaga livre em ordem de leitura', () => {
    // Um fluxo enquadrado: o centro e a vaga da direita ocupados.
    const busy = [at(ORIGIN.x, ORIGIN.y), at(ORIGIN.x + 280, ORIGIN.y)]
    expect(place(1, CENTRE, busy)).toEqual([{ x: ORIGIN.x + 560, y: ORIGIN.y }])
  })

  it('respeita o tamanho medido de um nó maior (ex.: moldura de grupo)', () => {
    const frame = { position: { x: ORIGIN.x, y: ORIGIN.y }, measured: { width: 600, height: 300 } }
    const pos = freePosition(CENTRE, [frame])
    // A moldura (600 + respiro) ocupa as colunas 0..2 das linhas 0..2: a primeira vaga é a coluna 3.
    expect(pos).toEqual({ x: ORIGIN.x + 840, y: ORIGIN.y })
  })
})

describe('isInView', () => {
  const view = { x: 0, y: 0, width: 900, height: 600 }

  it('o nó inteiro dentro da parte visível', () => {
    expect(isInView({ x: 330, y: 255 }, view)).toBe(true)
  })

  it('um nó que passa da borda direita ou de baixo não está visível', () => {
    expect(isInView({ x: 700, y: 255 }, view)).toBe(false)
    expect(isInView({ x: 330, y: 560 }, view)).toBe(false)
    expect(isInView({ x: -10, y: 0 }, view)).toBe(false)
  })
})
