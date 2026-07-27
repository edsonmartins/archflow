# O protocolo de eventos: `FlowEventBatch` e o resto

Como um agente archflow rodando fora do servidor manda o que está acontecendo
para o servidor.

Fonte da verdade: `archflow-events-proto/src/main/proto/archflow/events/v1/flow_events.proto`.

---

## 1. Sim, há mais tipos — cinco mensagens e dois enums

`FlowEventBatch` é só o envelope de transporte. O modelo inteiro:

| tipo | o que é |
|---|---|
| `FlowEventBatch` | **o que trafega**: um lote de eventos + quem os produziu |
| `FlowEvent` | um evento: envelope + `data` + `metadata` |
| `EventEnvelope` | metadados de roteamento (domínio, tipo, id, timestamp, correlação, execução, tenant) |
| `ScalarValue` | variante tipada para os valores de `data`/`metadata` |
| `IngestResponse` | resposta declarada… **e nunca usada** — ver a seção 7 |

Mais os enums `Domain` (8 valores) e `EventType` (32 valores).

### Como eles se aninham

```
FlowEventBatch
├── source_agent_id      : string
├── batch_created_millis : int64
└── events[]             : FlowEvent
                           ├── envelope : EventEnvelope
                           │              ├── domain / type
                           │              ├── id (UUID) / timestamp_millis
                           │              └── correlation_id / execution_id / tenant_id
                           ├── data     : map<string, ScalarValue>
                           └── metadata : map<string, ScalarValue>
```

`FlowEvent` é o nome do *message* no proto. Não confunda com
`br.com.archflow.agent.streaming.domain.FlowEvent`, que é a classe Java de
fábrica dos eventos de ciclo de vida (`FlowEvent.flowStarted(...)`).

---

## 2. Os 32 tipos de evento, por domínio

O enum Java `ArchflowEventType` espelha o proto exatamente, e cada valor tem uma
forma textual (é ela que aparece no SSE/JSON).

| domínio (`Domain`) | tipos (`EventType`) |
|---|---|
| **universais** — usados por vários | `START` `END` `ERROR` |
| `CHAT` `"chat"` | `DELTA` `MESSAGE` |
| `THINKING` `"thinking"` | `THINKING` `REFLECTION` `VERIFICATION` |
| `TOOL` `"tool"` | `TOOL_START` `PROGRESS` `RESULT` `TOOL_ERROR` |
| `AUDIT` `"audit"` | `TRACE` `SPAN` `METRIC` `LOG` |
| `INTERACTION` `"interaction"` | `SUSPEND` `FORM` `RESUME` `CANCEL` |
| `SYSTEM` `"system"` | `CONNECTED` `DISCONNECTED` `HEARTBEAT` |
| `PAYLOAD` `"payload"` | `PAYLOAD_CHUNK` `PAYLOAD_COMPLETE` |
| `FLOW` `"flow"` | `FLOW_STARTED` `FLOW_COMPLETED` `FLOW_FAILED` `STEP_STARTED` `STEP_COMPLETED` `STEP_FAILED` `STEP_SKIPPED` |

A forma textual é o nome em minúsculas com underscore: `FLOW_STARTED` →
`"flow_started"`, `TOOL_ERROR` → `"tool_error"`.

Os enums têm um `*_UNSPECIFIED = 0` cada, exigência do proto3 — não use.

### O que é emitido e o que só está declarado

Um consumidor que trate os 32 como igualmente prováveis vai escrever tratamento
para coisa que nunca chega. O estado real:

- **`FLOW_*` e `STEP_*` são emitidos de verdade.** Quem os produz é o
  `RegistryFlowLifecycleListener`, e ele está ligado em dois lugares:
  `ArchFlowAgent` (linha 287) e `FlowEngineFactory` (linha 111).
- **`PAYLOAD_CHUNK` e `PAYLOAD_COMPLETE` só aparecem em teste.** Nenhum código
  de produção os emite. Estão no protocolo, e é só isso.
- Os demais são referenciados por código de produção. "Referenciado" não é
  garantia de que uma execução qualquer os produza — a maioria depende do
  caminho tomado (só há `TOOL_*` se o fluxo chamar ferramenta).

Trate a lista como o que o protocolo **permite**, não como o que você **vai
receber**.

---

## 3. O transporte

```
POST /api/events/ingest
Content-Type: application/x-protobuf
X-Tenant-Id: <opcional>

<FlowEventBatch serializado>
```

Definido em `SpringEventIngestController` (`@RequestMapping("/api/events")`),
delegando para `EventIngestController`, cuja implementação é registrada como
bean em `ArchflowBeanConfiguration` (linha 403) e desemboca no
`EventStreamRegistry` — de onde a UI consome.

**Exige autenticação.** `/api/events` não está entre os caminhos públicos do
`JwtAuthenticationFilter`, então o `Authorization: Bearer` ou o `X-API-Key`
valem aqui como em qualquer outro endpoint.

A resposta é **JSON**, não protobuf:

```json
{ "accepted": 12, "rejected": 0, "message": "..." }
```

---

## 4. Produzindo: `ProtobufEventPublisher`

```java
ProtobufEventPublisher pub = new ProtobufEventPublisher(
        registry,                                        // EventStreamRegistry local
        URI.create("https://servidor/api/events/ingest"),
        "meu-agente");                                   // vira source_agent_id
```

No `archflow-standalone` isso já vem pronto: passe `--events-url` e o
`StandaloneRunner` monta o publisher sozinho.

### Os números que definem o comportamento

| constante | valor | efeito |
|---|---|---|
| `QUEUE_CAPACITY` | 2048 | fila em memória |
| `BATCH_FLUSH_THRESHOLD` | 100 | envia assim que acumula 100 |
| `FLUSH_PERIOD_MS` | 1000 | e envia de qualquer jeito a cada 1s |
| `HTTP_TIMEOUT` | 5s | por tentativa |
| `MAX_SEND_ATTEMPTS` | 3 | tentativas por lote |
| `RETRY_BASE_DELAY_MS` | 1000 | backoff exponencial: 1s, depois 2s |

Três comportamentos que decidem o que você perde:

- **Fila cheia descarta o mais antigo.** Sob rajada, você perde o começo da
  história, não o fim. Se a ordem importar mais que a recência, isto é o
  contrário do que se quer — e não é configurável.
- **4xx não é repetido; 5xx e erro de rede são.** Um 401 por token vencido não
  melhora com retry, então o lote é perdido de imediato.
- **A entrega é best-effort.** Não há persistência da fila: se o processo morre,
  o que estava na memória vai junto. Isto é telemetria, não trilha de auditoria
  — para "quem autorizou o quê", a fonte é o estado durável do motor, não este
  canal.

---

## 5. `ScalarValue`, e a armadilha do `toString()`

`data` e `metadata` são `map<string, ScalarValue>`. O `ScalarValue` é um `oneof`
com seis alternativas:

| campo | tipo |
|---|---|
| `string_value` | string |
| `int_value` | int64 |
| `double_value` | double |
| `bool_value` | bool |
| `bytes_value` | bytes |
| `null_value` | bool — `true` equivale ao `null` do JSON |

Escolhido em vez de `google.protobuf.Any` para manter o formato compacto e não
depender dos well-known types.

**A armadilha:** o `ProtobufEventMapper` converte `String`, `Boolean`, `Long`,
`Integer`, `Double`, `Float` e `Number` nos campos certos — e **qualquer outra
coisa cai num `toString()` e vira string**. Um `Map`, uma lista ou um objeto de
domínio colocado em `data` chega do outro lado como o texto do `toString()`,
sem erro e sem aviso.

Se você precisa de estrutura no payload, serialize explicitamente (JSON numa
`string_value`, por exemplo) em vez de confiar que o mapeador vai preservá-la.

---

## 6. Consumindo

O servidor decodifica o lote e republica cada evento no `EventStreamRegistry`,
que é o que alimenta os canais de streaming da UI. Do lado do consumidor, o
que chega é o evento já traduzido — o protobuf não vaza para o navegador.

Cada evento é publicado em **dois canais**:

| canal | quem escuta |
|---|---|
| `executionId` | quem acompanha aquela execução específica |
| `__admin__:<tenantId>` | visão administrativa de tudo do tenant |

O segundo só existe quando há tenant — o `X-Tenant-Id` da requisição. Sem ele,
o evento chega apenas em quem estiver acompanhando a execução pelo id.

A resposta conta os dois lados: `accepted` são os eventos republicados,
`rejected` os que falharam individualmente. Um lote pode voltar
`{"accepted": 8, "rejected": 2}` — **sucesso parcial não é erro HTTP**, então um
cliente que só olhe o status code não percebe a perda.

Para consumir em Java fora do servidor, use as classes geradas do
`archflow-events-proto`:

```java
FlowEventBatch batch = FlowEventBatch.parseFrom(corpoDaRequisicao);
for (FlowEvent e : batch.getEventsList()) {
    EventEnvelope env = e.getEnvelope();
    if (env.getType() == EventType.STEP_FAILED) {
        String passo = e.getData().get("stepId").getStringValue();
        // ...
    }
}
```

> `archflow-events-proto` **não está publicado no Maven Central** — só
> `archflow-model`, `archflow-dsl` e `archflow-sdk-java` estão. Para usá-lo hoje
> é preciso construir o archflow do fonte (`mvn install`).

---

## 7. Declarado e não usado

Duas coisas no protocolo que existem no papel e não no código. Registradas aqui
para ninguém gastar tempo procurando quem as consome:

- **`IngestResponse`.** O `.proto` a declara, e **nenhuma linha de Java a
  referencia**. O endpoint devolve um `record IngestResultDto(int accepted, int
  rejected, String message)` serializado em JSON — mesmos três campos, outro
  formato. Se você estiver escrevendo um cliente, espere JSON na resposta,
  apesar do que o `.proto` sugere.
- **`PAYLOAD_CHUNK` / `PAYLOAD_COMPLETE`.** Ver a seção 2.

---

## 8. Resumo para quem vai integrar

1. O que você envia é `FlowEventBatch`; o que você lê caso a caso é `FlowEvent`.
2. `POST /api/events/ingest`, `application/x-protobuf`, autenticado, resposta em
   JSON.
3. Use `ProtobufEventPublisher` em vez de montar o lote à mão — o batching, o
   retry e o descarte já estão resolvidos, e as regras deles estão na seção 4.
4. Só ponha escalares em `data`/`metadata`. Qualquer outra coisa vira
   `toString()` silenciosamente.
5. Programe para os tipos que o seu caminho realmente produz, não para os 32.
