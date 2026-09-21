# ArchFlow — resposta sobre "CS não devolveu um sentimento em JSON"

Resposta ao pedido do VendaX Core de 21/09/2026 (bancada `28b7298a`). Os dados abaixo saíram do log
do container do archflow em produção.

> **Resumo.** O que deixou rastro aponta para o **provedor**, não para o formato: resposta **vazia**
> — sem texto, sem `finishReason`, sem uso reportado — ou com o teto de tokens gasto em raciocínio.
> O texto das outras falhas **não foi registrado por nós**, e isso foi o primeiro defeito que
> corrigimos. Três das quatro propostas da §4 estão feitas no branch `fix/cs-json-diagnostico`; a
> saída estruturada fica para depois dos dados.

## 0. Um acerto de horário

A bancada está no nosso log às **17:33 no horário de Brasília (20:33 UTC)** — o pedido diz
"20:33–20:34 UTC-3". São as 48 execuções do CS daquele minuto, 24 com `cs@1+def1` e 24 com
`cs@3+def1` — conferido. As demais execuções do CS no log do dia são de produção, a partir das 19:21.

## 1. As respostas da §3

### 3.1 — O texto cru que o modelo devolveu

**Temos parte.** O laço do agente só registrava a resposta final quando ela vinha **vazia**; texto
não vazio sem JSON não deixava rastro em lugar nenhum. Do que ficou:

| quando (Brasília) | o que veio | leitura |
|---|---|---|
| 17:33:55 a 17:34:01, 5 vezes | **resposta vazia**, `finishReason=null`, **nenhum uso reportado** pelo provedor | bancada |
| 17:34:01 | **resposta vazia**, `finishReason=STOP`, **979 de 1.024 tokens gastos em raciocínio** | bancada |
| 19:25:10 | **resposta vazia**, `finishReason=null`, nenhum uso reportado | produção, o `traceId` `2bce1b62-…` |

Seis das 21 falhas da bancada, e a de produção, são **resposta vazia**. Não é cerca de código, nem
aspas tipográficas, nem prosa em volta — não veio nada. As outras 15 não temos.

**Vocês têm dado para fechar as 15.** Todo `ERROR` do CS vai com o `uso` da execução. Olhem, nos 21
erros da rodada:

- `outputTokens` perto de **1.024** → foi cortado no teto (JSON truncado, ou raciocínio que comeu o
  orçamento);
- `outputTokens` **nulo ou zero** com `llmTurns` 1 → resposta vazia, como as seis acima;
- `toolCalls` **maior que zero** → o modelo chamou `obter_eventos_operacionais` antes. Às 17:34:28
  essa tool **falhou duas vezes** do lado de vocês (`Falha na chamada MCP HTTP para
  https://copilot.vendax.ai/mcp`). Com a tool falhando, o modelo tende a responder em prosa.

### 3.2 — A regra de extração

Era: do primeiro `{` ao último `}` do texto final. **Aceita** cerca de código e prosa em volta.
**Não validava** o que extraía — prosa com chaves passava como sentimento e era recusada do lado de
vocês, sem contexto. Não valida os campos (`score`, `trend`…); isso continua com vocês, que
desserializam.

Agora, além disso, **o trecho precisa se ler como um objeto JSON, e só um**. JSON truncado, prosa com
chaves e duas leituras na mesma resposta passam a ser falha aqui — onde ainda dá para tentar de novo.

### 3.3 — O modelo do `LIGHT`

**O tier não é honrado.** O nosso log registra, a cada execução do CS:
`[LLM-TIER] tier=LIGHT pedido para tenant=a1494b5b-… e NAO honrado: nenhum mapa tier->modelo`.
Qualquer tier roda o modelo padrão da plataforma: **`google/gemini-2.5-flash-lite`**, via OpenRouter,
**teto de 1.024 tokens de saída**, temperatura 0,2. É a confirmação do que ficou em aberto desde o
pedido do US (18/09) e do CPA (20/09).

**Não há modo JSON nem saída estruturada** ligados. O JSON vem por instrução de prompt.

**O provedor está gastando tokens em raciocínio.** Uma das falhas gastou 979 dos 1.024 tokens
pensando e não escreveu nada. Com o teto em 1.024, isso vai se repetir.

### 3.4 — Nova tentativa

Não havia. O laço já repetia em dois casos específicos — falha de transporte e chamada de tool
malformada pelo provedor —, mas não quando a resposta final vinha sem o JSON. Não era uma decisão; o
caso não tinha aparecido medido.

## 2. O que está feito — branch `fix/cs-json-diagnostico`

| proposta da §4 | estado |
|---|---|
| Trecho do que veio no `ERROR` | **feito.** `CS não devolveu um sentimento em JSON (2 tentativas); última resposta: "…"`, ou `(vazia)`. Numa linha só, e **cabe nos 255 caracteres de `resultado_bancada.erro`** — cortado aqui, e não lá, porque um JSON truncado se denuncia pelo fim |
| Extração tolerante | **já era** (cerca e prosa); agora **valida** que o trecho é um objeto JSON |
| **Uma** nova tentativa | **feita.** Mesmo prompt, mesma janela, mesmo contador de uso — o custo da segunda chamada aparece no `uso` do result, não fica escondido. Nunca uma terceira |
| Saída estruturada | **não agora** — ver abaixo |

E, do nosso lado, cada falha passa a ser registrada com o trecho e o `traceId`
(`CS sem sentimento em JSON (tentativa 1/2, trace=…)`). A próxima pergunta como esta se responde sem
pedir a vocês.

**Por que a saída estruturada fica para depois:** o laço do CS tem ferramenta no meio, e o suporte a
schema com ferramentas muda de provedor para provedor. É uma mudança grande, e os dados que temos
apontam para resposta **vazia** — schema não conserta resposta vazia. Se, com o trecho no `ERROR`, a
maior parte das falhas restantes for formato, ela volta à mesa.

## 3. O que pedimos, e o que propomos

1. **Olhem o `uso` dos 21 erros** (§3.1). É o que separa "cortado no teto" de "veio vazio" nas 15
   que não temos.
2. **Repitam a bancada depois do deploy.** O trecho no `ERROR` e a nova tentativa mudam o que vocês
   vão ver: a perda deve cair, e a que sobrar vem explicada.
3. **O teto e o raciocínio** — proposta nossa, não feita: o CS não precisa pensar para devolver
   quatro campos. Limitar o raciocínio, ou subir o teto de 1.024, ataca a falha que achamos. Qualquer
   um dos dois muda custo e latência, e queremos medir antes.
4. **O mapa de tiers.** Enquanto ele não existir, "LIGHT" e "STRONG" rodam o mesmo modelo. É decisão
   de configuração da VPS, e é nossa.

## 4. O que esta resposta não diz

- Não sabemos o que veio nas 15 falhas sem rastro.
- A nova tentativa não foi medida contra o provedor; "a segunda costuma acertar" vem de a falha não
  seguir a janela, não de uma medição.
- A tool que falhou às 17:34:28 é do lado de vocês (`copilot.vendax.ai/mcp`), e às 19:21 a mesma
  borda derrubou uma execução do QP (`montar_rascunho_cotacao`). Não investigamos.
