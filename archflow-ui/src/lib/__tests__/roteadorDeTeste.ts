/**
 * MemoryRouter de DADOS para os testes do editor. O app roda num router de dados (createBrowserRouter) porque o
 * editor bloqueia a saída com alterações não salvas pelo useBlocker (RCHFLW-124), e o useBlocker lança fora de
 * um router de dados — o MemoryRouter comum não serve mais para montar o editor.
 *
 * <p>Mesma forma do App: uma rota '*' com a árvore de Routes do teste por baixo. O router é criado uma vez por
 * montagem (os testes renderizam uma vez); `ultimoRoteador` fica exposto para o teste navegar como o menu.</p>
 */
import { createElement as h, useState, type ReactNode } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'

// O router de dados monta um Request (do Node/undici) com o AbortSignal do jsdom a cada navegação, e o undici o
// recusa ("Expected signal to be an instance of AbortSignal"). Sem loaders nem actions nada aqui é cancelável:
// o Request dos testes só deixa de levar o signal.
const RequestDoNode = globalThis.Request
if (RequestDoNode && !(RequestDoNode as { semSinal?: boolean }).semSinal) {
  class RequestSemSinal extends RequestDoNode {
    static semSinal = true
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      const resto: RequestInit = { ...(init ?? {}) }
      delete resto.signal
      super(input, resto)
    }
  }
  globalThis.Request = RequestSemSinal as typeof Request
}

export let ultimoRoteador: ReturnType<typeof createMemoryRouter> | null = null

export function MemoryDataRouter({ initialEntries, children }: { initialEntries: string[]; children?: ReactNode }) {
  const [router] = useState(() => {
    const criado = createMemoryRouter([{ path: '*', element: children }], { initialEntries })
    ultimoRoteador = criado
    return criado
  })
  return h(RouterProvider, { router })
}
