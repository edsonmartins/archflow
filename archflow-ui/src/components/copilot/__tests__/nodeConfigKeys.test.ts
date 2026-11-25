/**
 * RCHFLW-78 — a tabela de chaves que o copiloto pode gravar. Ela repete o que o PropertyPanel grava, e nada
 * no compilador obriga as duas a concordar: este teste falha se a tabela citar uma chave que o painel (ou o
 * nó de anotação) não usa mais, ou se um tipo da paleta ficar sem entrada.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  NODE_CONFIG_KEYS, COPILOT_NODE_TYPES, JSON_SHAPES, REDACTED, validateNodeConfig, parseConfigArg, visibleConfig,
  applyConfig, configKeysSummary,
} from '../nodeConfigKeys'
import { NODE_TYPE_TO_CATEGORY, NOT_EXECUTABLE_NODE_TYPES } from '../../FlowCanvas/constants'

const components = resolve(__dirname, '..', '..')
const read = (...p: string[]) => readFileSync(resolve(components, ...p), 'utf-8')
const panelSources = [
  read('PropertyPanel.tsx'),
  ...readdirSync(resolve(components, 'PropertyPanel', 'fields'))
    .filter(f => f.endsWith('.tsx'))
    .map(f => read('PropertyPanel', 'fields', f)),
  // As chaves que o painel grava por constante (base de conhecimento, conexão do servidor por backend) moram aqui.
  read('FlowCanvas', 'dataNodeKeys.ts'),
].join('\n')
const annotationSources = ['StickyNote.tsx', 'GroupFrame.tsx', 'SectionDivider.tsx']
  .map(f => read('FlowCanvas', 'nodes', f)).join('\n')

describe('nodeConfigKeys — concorda com o painel de propriedades', () => {
  // A tabela cobre todo tipo que o designer conhece, não só os que o copiloto cria: ele ainda configura um nó
  // sem execução (RCHFLW-11) ou um llm-streaming (RCHFLW-57) que já esteja no canvas.
  it('todo tipo do designer tem entrada, e nenhuma entrada é de tipo fora dele', () => {
    expect(Object.keys(NODE_CONFIG_KEYS).sort()).toEqual(Object.keys(NODE_TYPE_TO_CATEGORY).sort())
  })

  it('o copiloto só cria tipos da paleta que executam (RCHFLW-11, RCHFLW-57)', () => {
    expect(COPILOT_NODE_TYPES).toEqual(expect.arrayContaining(['input', 'output', 'llm-chat', 'condition', 'decision']))
    expect(COPILOT_NODE_TYPES.filter(t => NOT_EXECUTABLE_NODE_TYPES.has(t))).toEqual([])
    expect(COPILOT_NODE_TYPES).not.toContain('llm-streaming')
    // Sem 'operation' (que o copiloto não tem como gravar) eles nunca rodam.
    expect(COPILOT_NODE_TYPES).not.toContain('memory')
    expect(COPILOT_NODE_TYPES).not.toContain('vector-store')
    expect(COPILOT_NODE_TYPES).toEqual(expect.arrayContaining(['vector-search', 'embedding', 'rag']))
  })

  it('toda chave da tabela é gravada pelo painel (ou lida pelo próprio nó, nas anotações)', () => {
    const missing: string[] = []
    for (const [type, keys] of Object.entries(NODE_CONFIG_KEYS)) {
      const annotation = ['sticky-note', 'group-frame', 'section-divider'].includes(type)
      for (const key of Object.keys(keys)) {
        // A cor da anotação não tem campo de edição, mas o nó a desenha (data.config?.color).
        const used = annotation
          ? annotationSources.includes(`'${key}'`) || annotationSources.includes(`config?.${key}`)
          : panelSources.includes(`'${key}'`)
        if (!used) missing.push(`${type}.${key}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('segredo não está na tabela', () => {
    for (const keys of Object.values(NODE_CONFIG_KEYS)) {
      expect(Object.keys(keys).filter(k => /api[-_]?key|secret|password|token$/i.test(k))).toEqual([])
    }
  })
})

describe('nodeConfigKeys — validação', () => {
  it('converte número, booleano e lista; null vira unset', () => {
    expect(validateNodeConfig('loop', { loopMaxIterations: '5', loopParallel: 'true', loopItemVar: 1 })).toEqual({
      ok: true, config: { set: { loopMaxIterations: 5, loopParallel: true, loopItemVar: '1' }, unset: [] },
    })
    // questions é um MAPA id → pergunta (DecisionComponent.parseQuestions), não uma lista.
    expect(validateNodeConfig('decision', {
      gate: 'a, b', questions: '{"a":{"type":"noul"},"b":{"type":"score","criteria":["baixa","alta"]}}', primary: null,
    })).toEqual({
      ok: true,
      config: {
        set: {
          gate: ['a', 'b'],
          questions: { a: { type: 'noul' }, b: { type: 'score', criteria: ['baixa', 'alta'] } },
        },
        unset: ['primary'],
      },
    })
  })

  it('recusa chave desconhecida, tipo errado e tipo de nó sem tabela', () => {
    expect(validateNodeConfig('llm-chat', { prompt: 'x' })).toEqual(expect.objectContaining({ ok: false }))
    expect(validateNodeConfig('llm-chat', { temperature: {} })).toEqual(expect.objectContaining({
      ok: false, error: '"temperature" must be number',
    }))
    // "constructor"/"toString" não podem passar por chave conhecida via protótipo.
    expect(validateNodeConfig('condition', { toString: 'x' })).toEqual(expect.objectContaining({ ok: false }))
    expect(validateNodeConfig('mcp-agent', {})).toEqual(expect.objectContaining({ ok: false, allowedKeys: {} }))
  })

  it('parseConfigArg aceita objeto ou texto JSON de objeto', () => {
    expect(parseConfigArg('{"a":1}')).toEqual({ ok: true, value: { a: 1 } })
    expect(parseConfigArg({ a: 1 })).toEqual({ ok: true, value: { a: 1 } })
    expect(parseConfigArg(undefined)).toEqual({ ok: true, value: undefined })
    expect(parseConfigArg('não json')).toEqual(expect.objectContaining({ ok: false }))
    expect(parseConfigArg([1])).toEqual(expect.objectContaining({ ok: false }))
  })

  it('visibleConfig só mostra chaves da tabela; applyConfig não muta a config original', () => {
    expect(visibleConfig('vector-search', { topK: 3, pineconeApiKey: 'sk' })).toEqual({ topK: 3 })
    const original = { a: 1, b: 2 }
    expect(applyConfig(original, { set: { c: 3 }, unset: ['a'] })).toEqual({ b: 2, c: 3 })
    expect(original).toEqual({ a: 1, b: 2 })
  })
})

const refused = (type: string, patch: Record<string, unknown>) => {
  const r = validateNodeConfig(type, patch)
  return r.ok ? undefined : r.error
}

describe('nodeConfigKeys — forma das chaves json da decisão (o motor as recusaria só ao executar)', () => {
  it('toda chave json da tabela tem uma forma conferida', () => {
    const semForma = Object.entries(NODE_CONFIG_KEYS).flatMap(([type, keys]) => Object.entries(keys)
      .filter(([k, v]) => v === 'json' && !JSON_SHAPES[type]?.[k]).map(([k]) => `${type}.${k}`))
    expect(semForma).toEqual([])
  })

  it('questions: lista no lugar do mapa, sem tipo, choice sem opções e score de um nível são recusados', () => {
    expect(refused('decision', { questions: [{ id: 'q', type: 'choice' }] })).toContain('not a list')
    expect(refused('decision', { questions: { q: 'é urgente?' } })).toContain('questions.q must be an object')
    expect(refused('decision', { questions: { q: { instructions: 'é urgente?' } } }))
      .toContain('choice, score or noul')
    expect(refused('decision', { questions: { q: { type: 'choice', criteria: ['a', 'b'] } } }))
      .toContain('questions.q.criteria must map each option')
    expect(refused('decision', { questions: { q: { type: 'score', criteria: ['só um'] } } }))
      .toContain('at least 2 levels')
    expect(refused('decision', { questions: {} })).toContain('at least one question')
    expect(refused('decision', {
      questions: { time: { type: 'choice', instructions: 'Quem cuida?', criteria: { pagamento: 'cobrança' } } },
    })).toBeUndefined()
  })

  it('thresholds: lista, fora de 0..1 ou review > auto são recusados', () => {
    expect(refused('decision', { thresholds: [0.9, 0.5] })).toContain('thresholds must be an object')
    expect(refused('decision', { thresholds: { auto: 90 } })).toContain('between 0 and 1')
    expect(refused('decision', { thresholds: { auto: 0.4, review: 0.6 } })).toContain('review must be <=')
    expect(refused('decision', { thresholds: { auto: 0.8, review: 0.4 } })).toBeUndefined()
  })

  it('o copiloto não desvia para onde vai a chave da plataforma: endpoint/keyRef/baseUrl/headers recusados', () => {
    expect(refused('decision', { providerOptions: { 'http-decisions': { endpoint: 'https://atacante.example' } } }))
      .toContain('can only be set by the user')
    expect(refused('decision', { cascade: [{ provider: 'http-decisions', options: { keyRef: 'outra' } }] }))
      .toContain('cascade[0].options may only set rules')
    expect(refused('decision', {
      fallbacks: [{ provider: 'langchain4j-decisions', options: { baseUrl: 'http://x' } }],
    }))
      .toContain('fallbacks[0].options')
    expect(refused('decision', { consensus: { models: [{ model: 'm', options: { headers: { X: '1' } } }] } }))
      .toContain('consensus.models[0].options')
    expect(refused('decision', { fallbacks: [{ provider: 'llm', endpoint: 'https://atacante.example' }] }))
      .toContain('unknown keys endpoint')
    // O que o painel grava continua gravável: as regras do provedor rules, degraus com provider/model.
    expect(refused('decision', {
      providerOptions: { rules: { rules: { q: [] } } },
      fallbacks: [{ provider: 'rules', options: { rules: { q: [] } } }],
      cascade: [{ provider: 'http-decisions', model: 'outro' }],
      consensus: { models: [{ model: 'outro' }], onDisagree: 'escalate' },
    })).toBeUndefined()
  })

  it('a forma esperada vai no contexto do agente, ao lado da chave', () => {
    expect(configKeysSummary().decision).toContain('questions:json {"<id>":{type:choice|score|noul')
  })
})

describe('nodeConfigKeys — credenciais não saem do canvas para o provedor do LLM', () => {
  it('esconde o userinfo de URL/DSN e os parâmetros com nome de segredo, em texto e dentro de JSON', () => {
    expect(visibleConfig('agent', {
      redisUrl: 'redis://:s3cret@cache:6379',
      mcpUrl: 'https://mcp.example/sse?token=abc&region=us',
      jdbcDatasource: 'jdbc:postgresql://db:5432/app?user=app&password=pw',
      model: 'gpt-4o-mini',
    })).toEqual({
      redisUrl: `redis://${REDACTED}@cache:6379`,
      mcpUrl: `https://mcp.example/sse?token=${REDACTED}&region=us`,
      jdbcDatasource: `jdbc:postgresql://db:5432/app?user=app&password=${REDACTED}`,
      model: 'gpt-4o-mini',
    })
    // vectorRedisUrl de um fluxo antigo nem é mais chave do nó (RCHFLW-56: a conexão é do servidor, por nome) —
    // não chega ao agente; o nome da conexão chega, porque não é credencial.
    expect(visibleConfig('vector-search', { vectorRedisUrl: 'rediss://default:pw@r:6380/0', redisConnection: 'cache' }))
      .toEqual({ redisConnection: 'cache' })
    // Gravado pelo usuário no JSON do nó (o copiloto não pode gravar isto, mas pode ver).
    expect(visibleConfig('decision', {
      providerOptions: { 'http-decisions': { endpoint: 'https://u:p@h/api', headers: { Authorization: 'Bearer x' } } },
    })).toEqual({
      providerOptions: {
        'http-decisions': { endpoint: `https://${REDACTED}@h/api`, headers: { Authorization: REDACTED } },
      },
    })
  })

  it('um valor com a marca não volta ao nó: o agente gravaria a marca no lugar da senha', () => {
    expect(refused('agent', { redisUrl: `redis://${REDACTED}@cache:6379` })).toContain('leave "redisUrl" out')
    expect(validateNodeConfig('agent', { redisUrl: 'redis://cache:6379' }).ok).toBe(true)
  })
})
