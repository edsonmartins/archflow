/**
 * O erro do upload de arquivo da base de conhecimento (RCHFLW-194): a frase do corpo de erro, como
 * nas outras chamadas da API, e não o JSON cru ({"status":400,"error":"File cannot be empty"}) no toast.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { knowledgeApi } from '../knowledge-api'
import { ApiError } from '../api'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('knowledgeApi.upload — erro do servidor', () => {
  it('vira ApiError com a mensagem do corpo e o status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ status: 400, error: 'File cannot be empty' }),
      { status: 400, headers: { 'Content-Type': 'application/json' } })))

    const falha = await knowledgeApi.upload(new File(['x'], 'a.txt')).catch((e: unknown) => e)

    expect(falha).toBeInstanceOf(ApiError)
    expect((falha as ApiError).status).toBe(400)
    expect((falha as ApiError).message).toBe('File cannot be empty')
  })
})
