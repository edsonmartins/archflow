# Invocação com resposta em fluxo (SSE) e cancelamento

Uma segunda forma de invocar qualquer fluxo: o motor devolve a saída de texto do modelo **à medida
que ela é gerada**, na mesma conexão, e aceita ser cancelado no meio. A invocação assíncrona
(`POST /api/agents/invoke`, aceite 202 e resultado por webhook) continua existindo e continua sendo
o padrão. Nada nela mudou.

Serve ao uso interativo — chat, assistente em tela, voz — em que a pessoa lê ou ouve enquanto o
modelo escreve. O motor **não interpreta o texto**: não corta em frases, não filtra, não confere.

## Rotas

| Rota | O que faz |
|---|---|
| `POST /api/agents/invoke/stream` | Mesmo corpo do `invoke` assíncrono, sem campo novo. Resposta `200 text/event-stream`. |
| `POST /api/agents/invoke/{idempotencyKey}/cancel` | Cancela a execução da chave, em fluxo **ou** assíncrona. `202` se a chave está em andamento nesta instância, `404` se não. |

Autenticação: a mesma do `invoke` assíncrono — `Authorization: Bearer <archflow.vendax.invoke-key>`.
Sem a chave configurada a rota recusa tudo (`503`). A definição do corpo precisa ser do tipo `FLUXO`
(`400` caso contrário).

## Eventos

Cada evento traz `seq`, crescente a partir de 0, dentro do JSON de `data`.

| evento | dados | quando |
|---|---|---|
| `inicio` | `idempotencyKey`, `traceId` | o motor aceitou e começou |
| `tool` | `nome`, `fase` (`INICIO`/`FIM`), `duracaoMs` no `FIM` | um nó chamou uma tool |
| `delta` | `texto` | cada pedaço de texto da resposta final |
| `fim` | o resultado completo, **igual ao do webhook**, mais `seq` | terminou |
| `erro` | `error`, `recuperavel` | falhou; encerra a conexão |

```
event: inicio
data: {"seq":0,"idempotencyKey":"k-123","traceId":"t-9"}

event: delta
data: {"seq":1,"texto":"Olá"}

event: delta
data: {"seq":2,"texto":", mundo"}

event: fim
data: {"seq":3,"schemaVersion":"1.0","status":"OK","richObjectType":"text","richObject":"Olá, mundo", ...}
```

- **O `fim` é o contrato.** Os `delta` são adiantamento; o texto concatenado deles é igual a
  `richObject` do `fim`.
- **Só texto vai em `delta`.** Fluxo com `saidaSchema` (saída estruturada) emite `inicio`, os `tool` e
  o `fim`, sem nenhum `delta`. Não se transmite JSON parcial.
- **Provedor sem streaming** (ou nó final que não é agente): um único `delta` com o texto inteiro, e
  depois o `fim`. O cliente não precisa saber.
- **Batimento:** um comentário SSE (`: batimento`) a cada 15 s sem evento
  (`archflow.agent.stream.heartbeat`).
- Todas as validações da invocação assíncrona valem igual: `validoAte`, tier, `maxIterations`, lista
  de tools, autenticação.

### Fluxo de texto: uma assimetria deliberada

Na rota em fluxo, **ausência de `saidaSchema` significa saída de texto**: `richObjectType = "text"` e
`richObject` com o texto. Na rota assíncrona o mesmo fluxo devolve `ERROR` ("definição sem
`saidaSchema`"), porque o Core espera um rich object tipado. A rota assíncrona não foi alterada.

### Com tools: o texto fica retido até o turno terminar

Para os `delta` serem só os da resposta final, um turno só se mostra final quando termina **sem pedir
tool**. Com tools no catálogo (ou `exigirSaidaDaTool`), o texto de cada turno fica retido até lá; os
eventos `tool` saem na hora, para o cliente ter sinal de vida. Sem tools, o texto sai à medida que
chega. Ou seja, o ganho de latência do primeiro token vale para **fluxo sem tools**.

### Qual nó transmite

Só os nós de agente **finais** — os sem aresta de saída — transmitem texto. O motor marca esses nós
na cópia do documento desta execução (`config.transmitirTexto = true`); o documento guardado não é
tocado. Passos intermediários continuam sem transmitir texto (o texto deles é insumo do passo
seguinte), mas seus eventos `tool` e o cancelamento funcionam.

## Cancelamento

- **Fechar a conexão cancela.** O motor para de consumir o modelo assim que percebe: a primeira
  escrita que falha, ou o batimento, revela a conexão morta.
- Atrás de proxy que não propaga o fechamento, use a rota `/cancel`.
- O resultado parcial é registrado com `encerradoPor = { "name": "CANCELADO" }`, o `uso` do que foi
  consumido e, se algum texto já saiu, `richObjectType = "text"` com o texto parcial. O `status` é
  `ERROR` (não há resposta completa).

> **Mudança de contrato:** `encerradoPor.name` ganha o valor `CANCELADO`. Quem trata o campo como
> enumeração fechada precisa aceitá-lo.

## Entrega garantida do resultado

O chamador recebe **exatamente um resultado final por chave**:

- `fim` escrito na conexão: **não há webhook**.
- Conexão encerrada antes do `fim` (queda, cancelamento, ou `erro`): o motor conclui o registro e
  entrega o resultado pelo webhook já configurado, com o mesmo `idempotencyKey`. Isso vale também
  para o resultado de texto e para o `CANCELADO`.
- "Escrito" quer dizer que a escrita foi aceita pela conexão; não há confirmação de leitura.

Uma falha da execução emite `erro` (com `recuperavel`) e encerra a conexão; o resultado `ERROR` segue
pelo webhook.

## Idempotência

- Mesma chave com execução **em andamento**: `409`.
- Mesma chave com execução **terminada com sucesso**: a rota devolve só o `fim` guardado, sem chamar o
  modelo. A guarda dura `archflow.agent.stream.result-ttl` (padrão `PT10M`; `PT0S` desliga). Só
  resultado `OK` é guardado: repetir a chave depois de falha ou cancelamento tenta de novo.
- A chave guardada só vale para o mesmo `tenantId`; outro tenant com a mesma chave recebe `409`.

## Medição

`uso.msAtePrimeiroToken` (opcional): do início da execução ao primeiro token do provedor. Só é
preenchido quando o provedor transmite — uma resposta bloqueante não tem primeiro token a medir —,
então **na invocação assíncrona ele fica ausente** (o laço assíncrono usa o modelo bloqueante).
Ausente significa "não medido", nunca zero.

## Capacidade

`archflow.agent.stream.max-concurrent` (padrão 50) limita as execuções em fluxo simultâneas. Acima
dele: `429` com `Retry-After` (`archflow.agent.stream.retry-after-seconds`, padrão 5).

## Configuração

| Propriedade | Padrão | |
|---|---|---|
| `archflow.agent.stream.enabled` | `true` | liga/desliga a rota (desligada: 404) |
| `archflow.agent.stream.heartbeat` | `PT15S` | batimento sem evento |
| `archflow.agent.stream.result-ttl` | `PT10M` | guarda do resultado de uma chave terminada |
| `archflow.agent.stream.max-concurrent` | `50` | execuções em fluxo simultâneas |
| `archflow.agent.stream.retry-after-seconds` | `5` | `Retry-After` do 429 |

## O que é por réplica

Enquanto a guarda e o registro forem em memória, **cada réplica enxerga só o que executou**:

- **Idempotência:** o `409` de chave em andamento e o `fim` guardado valem dentro da réplica. Uma
  repetição que caia noutra réplica executa de novo.
- **Limite de capacidade:** `max-concurrent` é por réplica.
- **Cancelamento por chave:** uma chave que está noutra réplica responde `404`, sem erro. Fechar a
  conexão continua sendo o caminho principal.

Uma implementação em banco da guarda (`ResultadoGuardado`) é o passo seguinte para quem roda várias
réplicas.

## Implantação atrás de proxy

A resposta traz `Cache-Control: no-cache` e `X-Accel-Buffering: no`. Mesmo assim, um proxy que segura
resposta precisa ser configurado para **não armazenar** a resposta desta rota:

- nginx: `proxy_buffering off;` (ou confie no `X-Accel-Buffering`), `proxy_read_timeout` maior que o
  fluxo mais longo, e `gzip off` para `text/event-stream`.
- Outros (ALB, Cloudflare, CDN): desligar buffering/compressão para `text/event-stream` e usar um
  timeout ocioso acima do intervalo do batimento.
- Se o proxy não repassa o fechamento da conexão ao servidor, o cancelamento só vale pela rota
  `/cancel`.

## Exemplo mínimo de cliente

Fluxo de exemplo neutro: [`examples/fluxo-de-texto.json`](examples/fluxo-de-texto.json). O corpo do
`invoke` leva esse documento em `definicao.fluxo`, com `definicao.tipo = "FLUXO"` e sem `saidaSchema`.

```bash
curl -N -X POST "$ARCHFLOW/api/agents/invoke/stream" \
  -H "Authorization: Bearer $ARCHFLOW_INVOKE_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "tenantId": "acme",
        "agent": "assistente",
        "conversationId": "c-1",
        "idempotencyKey": "k-123",
        "text": "Olá!",
        "definicao": { "tipo": "FLUXO", "fluxo": <conteúdo de fluxo-de-texto.json> }
      }'
```

```javascript
// Node 18+: lê o SSE, imprime os deltas e cancela ao fechar a conexão.
const ctl = new AbortController();
const res = await fetch(`${base}/api/agents/invoke/stream`, {
  method: "POST", signal: ctl.signal,
  headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
  body: JSON.stringify(invoke),
});
const dec = new TextDecoder(); let buf = "";
for await (const parte of res.body) {
  buf += dec.decode(parte, { stream: true });
  let i;
  while ((i = buf.indexOf("\n\n")) >= 0) {
    const bloco = buf.slice(0, i); buf = buf.slice(i + 2);
    const nome = /^event: (.*)$/m.exec(bloco)?.[1];
    const dados = /^data: (.*)$/m.exec(bloco)?.[1];
    if (nome === "delta") process.stdout.write(JSON.parse(dados).texto);
    if (nome === "fim" || nome === "erro") { console.log("\n", nome); }
  }
}
// ctl.abort() em qualquer ponto fecha a conexão — e isso cancela a execução.
```
