import type { Node, XYPosition } from '@xyflow/react'

/**
 * Id de um nó criado pela API imperativa do canvas e a posição dos nós que o copiloto adiciona.
 *
 * <p>Ficam fora do FlowCanvas porque o copiloto também precisa de um id ANTES de o canvas existir: um
 * addNode enfileirado (createFlow → addNode, sem editor aberto) leva o id já escolhido, para que um
 * connectNodes enfileirado logo depois cite o nó certo quando a fila for drenada.</p>
 */

// Medidas do ShapedNode (WIDTH = 240; a altura varia com o conteúdo, ~90) mais um respiro: é o passo
// da grade de colocação automática, largo o bastante para os nós não se encostarem.
const NODE_WIDTH = 240
const NODE_HEIGHT = 90
const GAP_X = 40
const GAP_Y = 50
const STEP_X = NODE_WIDTH + GAP_X
const STEP_Y = NODE_HEIGHT + GAP_Y

let seq = 0

/**
 * Um id de nó que não está em {@code taken}.
 *
 * <p>Era {@code node-<Date.now()>}: a fila do copiloto é drenada num laço síncrono, todos os addNode
 * caem no mesmo milissegundo e saíam com o MESMO id — o React Flow passava a tratar N nós como um só
 * (seleção e edição misturadas) e o documento salvo levava steps com id repetido. O contador da sessão
 * torna o id único mesmo dentro do mesmo milissegundo; o conjunto cobre ids de um fluxo carregado.</p>
 */
export function newNodeId(taken: ReadonlySet<string> = new Set()): string {
  let id: string
  do {
    seq += 1
    id = `node-${Date.now()}-${seq}`
  } while (taken.has(id))
  return id
}

type Placed = Pick<Node, 'position' | 'width' | 'height' | 'measured'>

/** Retângulo em coordenadas do fluxo (ex.: a parte do canvas que está visível). */
export interface FlowRect {
  x: number
  y: number
  width: number
  height: number
}

function overlaps(candidate: XYPosition, node: Placed): boolean {
  const w = node.measured?.width ?? node.width ?? NODE_WIDTH
  const h = node.measured?.height ?? node.height ?? NODE_HEIGHT
  return candidate.x < node.position.x + w + GAP_X
    && node.position.x < candidate.x + NODE_WIDTH + GAP_X
    && candidate.y < node.position.y + h + GAP_Y
    && node.position.y < candidate.y + NODE_HEIGHT + GAP_Y
}

// Colunas por linha da grade: as mesmas 5 da colocação por índice que o canvas já usava sem viewport
// ({@code 120 + (count % 5) * 220}).
const COLS = 5
const ROWS = 20

/**
 * A posição (canto superior esquerdo) da primeira vaga livre de uma grade que começa em {@code centre},
 * em ordem de leitura: da esquerda para a direita, {@link COLS} por linha, e então a linha de baixo — o
 * fluxo se lê da esquerda para a direita, e um nó adicionado depois não aparece antes dos anteriores.
 *
 * <p>Usada pelo copiloto (RCHFLW-77). Antes o deslocamento era {@code (count % 3) * 28} px a partir do
 * centro, com {@code count} lido de um ref que só muda no próximo render: os nós de um mesmo lote (a fila
 * do copiloto) saíam todos no mesmo ponto. Um nó que caia fora da tela é trazido para ela pelo canvas
 * (centraliza nele; na drenagem da fila, um fitView no fim).</p>
 */
export function freePosition(centre: XYPosition, nodes: readonly Placed[]): XYPosition {
  const origin = { x: centre.x - NODE_WIDTH / 2, y: centre.y - NODE_HEIGHT / 2 }
  for (let row = 0; row < ROWS; row++) {
    for (let col = 0; col < COLS; col++) {
      const candidate = { x: origin.x + col * STEP_X, y: origin.y + row * STEP_Y }
      if (!nodes.some(n => overlaps(candidate, n))) return candidate
    }
  }
  // Grade inteira ocupada: logo abaixo dela.
  return { x: origin.x, y: origin.y + ROWS * STEP_Y }
}

export function isInView(position: XYPosition, view: FlowRect): boolean {
  return position.x >= view.x
    && position.y >= view.y
    && position.x + NODE_WIDTH <= view.x + view.width
    && position.y + NODE_HEIGHT <= view.y + view.height
}

/** O centro de um nó recém-criado em {@code position} — o ponto para centralizar a viewport nele. */
export function nodeCentre(position: XYPosition): XYPosition {
  return { x: position.x + NODE_WIDTH / 2, y: position.y + NODE_HEIGHT / 2 }
}
