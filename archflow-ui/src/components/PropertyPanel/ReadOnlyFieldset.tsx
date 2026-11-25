import type { ReactNode } from 'react'

/**
 * Trava todos os controles de dentro com o `disabled` nativo do fieldset — inputs, selects, switches
 * e os botões de inserir referência — em vez de repetir a prop em cada campo de cada tipo de nó (um
 * campo novo esquecido viraria um controle morto de novo). `display: contents` porque é só o
 * mecanismo: os campos continuam filhos do flex do painel (o espaçamento não muda) e dá para deixar de
 * fora um controle que não edita, como o copiar do id do nó.
 */
export function ReadOnlyFieldset({ readOnly, children }: { readOnly: boolean; children: ReactNode }) {
  return (
    <fieldset
      disabled={readOnly}
      style={{ display: 'contents' }}
    >
      {children}
    </fieldset>
  )
}
