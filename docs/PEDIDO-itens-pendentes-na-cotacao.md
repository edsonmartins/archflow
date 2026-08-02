# ArchFlow — o QP precisa emitir o item que não resolveu

Trabalhe em `/Users/edsonmartins/desenvolvimento/archflow`. Leia o `CLAUDE.md` do repositório e siga
as convenções dele.

## O que se pede

Quando o `resolver_sku` **não** resolver um item, o QP deve incluí-lo na cotação como **pendência**,
com os candidatos que a tool devolveu — em vez de descartar o item ou concluir sem cotar.

O lado do Core já está pronto: o contrato existe, valida e está em produção.

## Por quê — e não é sobre experiência de uso

`core.entradas_lexico` tem **zero linhas** desde que a integração existe. A causa é esta: o gate
produz candidatos ordenados, com nota e motivo, e **eles morrem na chamada**. Nada os carrega até o
vendedor, então nada captura a escolha dele, e o laço de aprendizado fica desenhado sem ter por onde
fechar.

Dizer *"não achei"* faz o vendedor digitar ou pesquisar. **Mostrar os prováveis faz dele um toque** —
e é o mesmo toque que vira léxico, que é o que faz o sistema melhorar com uso em vez de com modelo
maior.

Medido contra o catálogo real, com 58 itens de pedidos de clientes: **88% resolvem, 7 ficam**. E os 7
não são defeito — são vocabulário: o cliente diz *"mandioca"* onde o catálogo diz *"mesa"*,
*"palheta"* onde diz *"mexedor"*, *"torrado"* onde diz *"TM"*. Nenhuma melhoria de similaridade
resolve isso. **A primeira confirmação do vendedor resolve, para sempre, para aquele cliente.**

## O que a tool já devolve

`resolver_sku` responde com `gate` (`RESOLVE` | `MOSTRA` | `LISTA`), `confianca` e:

```json
{"candidatos":[
  {"skuRef":"38890:1",
   "descricao":"FARINHA MESA CRUA 1KG MANA",
   "score":0.49,
   "origemDoSinal":"CATALOGO",
   "motivo":"catálogo: FARINHA MESA CRUA 1KG MANA"}]}
```

`descricao` é campo próprio de propósito — foi acrescentada hoje justamente para vocês não terem de
recortá-la do texto do `motivo`.

## O formato a emitir

No `payload` da cotação, ao lado de `items`:

```json
"pendentes": [
  {"texto": "farinha de mandioca branca crua",
   "qty": 20,
   "unidade": "kg",
   "candidatos": [
     {"sku":"38890:1","descricao":"FARINHA MESA CRUA 1KG MANA","confianca":0.49,
      "motivo":"catálogo: FARINHA MESA CRUA 1KG MANA"},
     {"sku":"38891:1","descricao":"FARINHA DE ARROZ 1KG URBANO","confianca":0.41,
      "motivo":"catálogo: FARINHA DE ARROZ 1KG URBANO"}]}
],
"flags": ["ITENS_PENDENTES"]
```

**`texto` é obrigatório na prática**, ainda que o schema aceite nulo: é o trecho **como o cliente
escreveu ou falou**. Sem ele o vendedor vê três produtos e não sabe o que foi pedido — e é exatamente
o texto que o léxico precisa guardar para aprender. Mande o trecho que vocês passaram ao
`resolver_sku`, não a mensagem inteira.

**Ordem dos candidatos importa** — é a ordem em que o vendedor vai lê-los. Mantenham a que a tool
devolveu; ela é o ranque.

**Não invente preço nem quantidade zero.** Pendência fica fora dos totais, e é por isso que ela é
lista separada e não um `item` sem preço.

## Quando emitir

| gate | o que fazer |
|---|---|
| `RESOLVE` | item normal em `items`, como hoje |
| `MOSTRA` / `LISTA` | **pendência** — o gate está dizendo que não dá para afirmar |
| sem candidato | pendência com `candidatos: []` — o vendedor ainda precisa saber que o item foi pedido |

O último caso é o que mais some hoje: item sem candidato desaparece, e o cliente pediu.

## O que muda no comportamento atual

Hoje, quando o QP não chega a cotar, ele **retorna `null` e nenhum resultado é enviado** — visto no
log: `QP concluiu sem cotação`. Isso está certo para "a mensagem não era um pedido"; está **errado**
para "era um pedido e eu não resolvi os itens".

A distinção: se houve item mencionado, há cotação a mandar — com `items`, com `pendentes`, ou com os
dois. Cotação **só** com pendências é válida e o Core a aceita.

## Como verificar

Não aceite "os campos foram emitidos" como pronto:

1. Mande uma mensagem com um item que sabidamente não resolve — *"quero palheta mexedor grande"*.
2. Confirme no log do ArchFlow: `Resultado do agente QP entregue ao Core`.
3. **Confirme no Core que a mensagem gravada tem `pendentes` com candidatos e o `texto` original.**

O passo 3 é o único que conta. Foi essa verificação que faltou quando o invoke foi ligado: dezoito
chamadas receberam `202`, nenhuma executou, e ninguém soube por uma semana.

## Fora de escopo

**Não** mexam no HMAC, no envelope de resultado nem no caminho de invoke — os três estão verificados
em produção.

**Não** tentem resolver o item com o LLM quando o `resolver_sku` não resolveu. O casamento é
determinístico por decisão de arquitetura (A-6 e ADR-026 D-4): o agente separa a mensagem em itens,
o `resolver_sku` casa. Um palpite do modelo aqui viraria SKU errado numa cotação — e com a aparência
de certeza.

---

## Anexo — o que o Core já faz, para vocês não duplicarem

**Recusa enviar ao cliente** cotação com pendência, com mensagem dizendo o que falta. Vocês não
precisam bloquear nada.

**Não vaza para o WhatsApp:** o texto de canal percorre `items`, e pendência mora fora — por
construção, não por filtro.

**Aceita cotação sem `items`** quando há pendência.

**Retrocompatível:** cotação sem o campo continua válida.
