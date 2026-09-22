# ArchFlow — resposta ao pedido de reentrega do `result`

Resposta ao pedido do VendaX Core de 22/09/2026 ("reentregar o `result` ao Core em 5xx e falha de
rede"). Feito como pedido, no branch `fix/result-reentrega`.

## O que muda

O `VendaxResultSender.send` passa a usar o **mesmo laço de entrega** do relato de uso — eram dois, e
só o do relato reentregava. Vale para todo `result`, de todos os agentes, pelo caminho de fluxo e pelo
`case`: os dois terminam no mesmo `send`.

| resposta do Core | o que o ArchFlow faz |
|---|---|
| 2xx | entregue |
| **5xx ou falha de rede** | **reentrega**: a primeira tentativa e mais até três, com espera de **1 s, 3 s e 9 s** — no máximo 4 POSTs e 13 s de espera |
| **4xx** (401 de assinatura, 400 de corpo) | **desiste na hora**, sem nova tentativa |

- **Cada tentativa sai assinada de novo**, com timestamp novo: um reenvio 13 s depois não cai fora da
  janela de assinatura de vocês.
- **O corpo é o mesmo** em todas as tentativas — mesma `idempotencyKey`, mesmo `uso` com o mesmo
  `execucaoId`. A deduplicação de vocês (§"Por que repetir é seguro") é o que torna isso seguro.
- **A desistência final é registrada com o `traceId` e a `idempotencyKey`**, e com o status do result:
  `Resultado do agente CS PERDIDO (Core indisponível em todas as tentativas; trace=…, chave=…,
  status=OK)`. O 4xx sai com a mesma linha e "recusado pelo Core, sem nova tentativa".

## Onde as esperas rodam (o item 3 do pedido)

Na thread do executor do agente, depois de a execução terminar. Duas consequências:

- **O prazo da classe interativa não é afetado**: ele vale para a execução do agente, que a esta altura
  já acabou. Mas um `result` interativo pode chegar até ~13 s depois do que chegaria — se a tela já
  desistiu, o que fazer com ele é decisão de vocês, como já é hoje com o `OK` atrasado.
- **O executor é limitado** (`max-concurrency` 32, fila de 256). Com o Core **em pico**, as esperas dão
  a ele os segundos que faltam. Com o Core **fora**, cada result segura uma thread por até 13 s; a fila
  enche e novos invokes passam a ser recusados na entrada com **503** ("executor de agentes
  temporariamente saturado", o que o controller já devolve hoje) — e aí é o outbox de vocês que os guarda, em
  vez de o ArchFlow executar agentes cujo resultado não teria para onde ir. Preferimos assim a mandar
  as esperas para fora da thread: um result que só existisse em memória, esperando, se perderia num
  reinício do ArchFlow sem rastro nenhum.

## Como foi verificado

`EntregaDoResultTest`, contra um servidor HTTP local:

- 503, 503, 200 → **três POSTs**, o mesmo corpo, entregue;
- 400 → **um POST**; 401 → **um POST**;
- 5xx em todas → **quatro POSTs** e desiste, sem lançar;
- falha de rede → desiste sem lançar;
- cada tentativa com o seu timestamp de assinatura.

Os testes do relato de uso seguem passando — o comportamento dele não mudou (3 tentativas, 0,5 s e
1 s).

## O que não muda

- O contrato: nenhum campo novo no `result`.
- O relato de uso (`/api/v1/public/archflow/uso`): mesmas tentativas e esperas de antes.
