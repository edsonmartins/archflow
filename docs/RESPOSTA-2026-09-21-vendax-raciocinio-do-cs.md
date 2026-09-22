# ArchFlow — resposta à réplica sobre o raciocínio do CS

Resposta à réplica do VendaX Core de 21/09/2026 ("é resposta vazia, a nova tentativa não resolve, e o
raciocínio do CS").

> **Resumo.** Vocês acertaram a causa e erraram o parâmetro — e nós também erraríamos: implementamos
> `effort: "none"` e medimos antes de publicar. **Este modelo ignora os três jeitos documentados de
> desligar o raciocínio.** O que funciona é **`effort: "minimal"`**: 40 de 40 leituras com JSON, contra
> 9 de 20 falhas como está hoje. Subir o teto piora. A mudança está no PR, e a bancada de vocês é a
> medição de produção.

## 1. O que medimos

Chamadas diretas ao OpenRouter, da VPS, com a chave de produção, **no formato do CS**: o prompt de
sistema, as duas tools no catálogo e uma janela em que o cliente reclama de itens faltando.
`google/gemini-2.5-flash-lite`, temperatura 0,2, teto de 1.024 — o que roda hoje.

| variante | chamadas | raciocínio (tokens) | resultado |
|---|---|---|---|
| **sem parâmetro — como está hoje** | 20 | ~980 | **9 falhas**: 3 `MALFORMED_FUNCTION_CALL`, 4 cortadas no teto, 1 vazia, 1 com a chamada da tool escrita como texto (`tool_code`); das outras 11, 10 foram chamar a tool e 1 respondeu |
| `reasoning.effort: "none"` — o pedido de vocês | 20 | ~980 | **ignorado**: 5 cortadas no teto, 15 foram chamar a tool |
| `reasoning.max_tokens: 0` | 10 | ~980 | **ignorado**: 2 cortadas no teto, 8 foram chamar a tool |
| `reasoning.enabled: false` | 10 | ~980 | **ignorado**: 1 vazia, 9 foram chamar a tool |
| **`reasoning.effort: "minimal"`** | **40** | **~350** | **40 JSON legíveis, 0 falhas, ~2,5 s** |
| teto de 4.096, sem parâmetro | 10 | até 2.500 | **pior**: 2 vazias, latência até 20 s |

Uma chamada que sai com tool não é falha — no laço real, a tool roda e o modelo responde no turno
seguinte. As falhas são as que terminam sem JSON no primeiro turno, e **9 em 20 (45 %) é a mesma
proporção da bancada de vocês (21 em 48, 44 %)**.

### O que é a "resposta vazia"

É **`MALFORMED_FUNCTION_CALL`**: o Gemini tenta chamar a tool e a chamada sai corrompida. O OpenRouter
devolve `finish_reason: "error"`, `content: null` e **nenhum `usage`** — exatamente o perfil das 4
falhas de produção da tabela de vocês (saída nula, nenhuma tool, em segundos). O laço já repetia uma
chamada malformada quando ela chegava como **texto** (`call:montar_cotacao{…}`, a forma vista em
06/08); nesta forma, sem texto, ela passava por conclusão vazia.

As outras falhas são o raciocínio comendo o teto: `finish_reason: "length"` com o JSON pela metade, ou
com o próprio raciocínio escrito no lugar da resposta (*"I must respond only with a JSON…"*).

### Por que as duas coisas somem com `minimal`

Com o raciocínio em ~350 tokens sobra teto para a resposta — e, nas 40 chamadas, **o modelo não chamou
a tool nenhuma vez**: foi direto ao JSON. Sem tentativa de chamada, não há chamada malformada.

## 2. O que muda — PR `feat/cs-raciocinio-minimo`

- O passo do CS leva `reasoning: {"effort": "minimal"}`. Só o CS; os outros agentes seguem como estão.
- Vai como patch do **passo**, o nível mais específico: patch de fluxo ou de tenant não o desfaz.
- A nova tentativa do PR anterior continua — agora como cinto, não como a correção.

## 3. O que vocês precisam saber antes da bancada

1. **O CS deixou de chamar `obter_eventos_operacionais`.** Nas 40 chamadas com `minimal`, nenhuma — e
   a janela era uma reclamação de itens faltando, que o prompt manda confirmar com a tool. Para o
   `motivo` não pesa: ele é o que o cliente alega, e quem confere são vocês (§5 do pedido do motivo).
   Mas o `tone` passa a ser escrito sem o dado operacional. Se isso importar, digam: a saída é o
   Core mandar os eventos no payload, em vez de o modelo buscá-los — o que também tira a tool do
   laço, e com ela o risco de chamada malformada.
2. **O `trend` oscila.** A mesma janela — o cliente reclamando — saiu `CAINDO` em 16 das 30 e
   `SUBINDO` em 14. O `score` ficou estável (−7 em 28 de 30), e o `motivo` também (30 de 30). Parece
   ambiguidade de sentido — `SUBINDO` é a insatisfação que sobe, ou o sentimento? Sem o `minimal`,
   as poucas respostas que chegaram a escrever o `trend` também divergiram (`CAINDO` e `ESTAVEL`),
   então não parece efeito dele — mas são poucas para afirmar. Vocês já derivam a
   tendência do histórico; registramos para a comparação que vocês guardam.
3. **Custo.** Por chamada, a saída cai de ~1.000 para ~430 tokens. O CS fica mais barato, não mais caro.
4. **A medição é nossa, com uma janela só.** A de vocês — 30 leituras negativas, `cs@1` × `cs@3`, com
   o `uso` de cada uma — é a que vale. Se uma janela real fizer o modelo voltar a chamar a tool com
   `minimal`, é lá que aparece.

## 4. O que fica

| | de quem |
|---|---|
| Revisar e fazer deploy de `feat/cs-raciocinio-minimo` | ArchFlow |
| Rodar a bancada depois do deploy e devolver a tabela | Core |
| Decidir se o CS deve buscar eventos (tool) ou recebê-los no payload | Core |
| O mapa de tiers — hoje `LIGHT` e `STRONG` rodam o mesmo modelo | ArchFlow |
| Repetir a chamada malformada quando ela vem sem texto, em todos os agentes | ArchFlow — proposto, não feito |

O último item vale para todo agente com tools, não só para o CS: o QP e o NS também podem receber
`MALFORMED_FUNCTION_CALL` sem texto, e hoje isso termina como conclusão vazia. É uma mudança no laço
comum, e a queremos com teste próprio, fora deste PR.
