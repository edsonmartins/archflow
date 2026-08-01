# ArchFlow — integração com o VendaX Core: 18 invokes aceitos, 0 executados

Trabalhe em `/Users/edsonmartins/desenvolvimento/archflow`. Leia o `CLAUDE.md` do repositório antes
de mexer e siga as convenções dele — o que vem abaixo é diagnóstico, não licença para ignorar padrão
local.

## Estado do repositório quando isto foi escrito (01/08/2026)

Branch `fix/eventos-observabilidade`, com alterações **não commitadas** em workflow runtime store,
timeout de embedding da OpenAI, `ProductionReadinessGuard` e UI. Foi conferido: **nenhuma delas toca
o caminho da integração VendaX.** `McpAgentRunner`, `HttpMcpClient`, `VendaxMcpClientProvider` e
`VendaxAgentDispatcher` estavam limpos, e no `ArchflowBeanConfiguration` o único trecho alterado é o
timeout de embedding — o bean `vendaxAgentExecutor` é código commitado.

Confira o estado atual antes de começar; se a árvore tiver mudado, revalide os números de linha.

## O sintoma

Entre 24 e 28/07/2026, o VendaX Core entregou **18 invokes de agente** ao ArchFlow (15 `CS`, 3 `QP`).
Todos foram aceitos com **202 na primeira tentativa, sem erro e sem dead-letter** no outbox do Core.
Nenhum produziu resultado: `core.llm_usage` = 0 linhas, `core.suggestion_outcome` = 0 linhas. Nenhum
`agent.error` chegou de volta ao Core.

Ou seja: **o ArchFlow aceitou 18 ordens de execução e nenhuma virou nada, silenciosamente.**

## O que já foi verificado (não refaça)

- `ARCHFLOW_BASE_URL=https://archflow.com.br` está configurado no serviço do Core, junto com
  `ARCHFLOW_API_KEY`, `ARCHFLOW_SERVICE_TOKEN` e `ARCHFLOW_RESULT_SECRET`. A fiação existe.
- `https://archflow.com.br/actuator/health` responde **200**. `POST /api/agents/invoke` sem corpo
  responde **400**, então a rota existe e valida.
- O endpoint MCP do Core (`POST https://copilot.vendax.ai/mcp`) responde **401 em 0,6 s** sem token.
  Ele **não pendura** — responde rápido. Com token de serviço válido deve funcionar.
- `CS` e `QP` **estão implementados** em `VendaxAgentDispatcher.runAndReport`. Não é caso de agente
  não implementado (que devolveria `VendaxResult.error`).
- `VendaxResultSender` está **correto**: tem `.timeout(30s)`, não lança, e loga em WARN quando
  `archflow.vendax.core.base-url` está vazia. Se o resultado sumiu ali, há log dizendo isso.

## Passo 0 — confirmar qual defeito causou a parada

Antes de corrigir, rode na VPS do ArchFlow (o usuário tem acesso e roda por você) e traga o resultado:

```bash
C=$(docker ps --format '{{.Names}}' | grep -i archflow | head -1)

# 1. as configurações de que a integração depende
docker exec "$C" env | grep -iE 'vendax|invoke.key|core.base|mcp'

# 2. as 4 threads do pool de agente estão vivas ou penduradas?
PID=$(docker exec "$C" jcmd -l | grep -v jcmd | head -1 | cut -d' ' -f1)
docker exec "$C" jstack "$PID" > /tmp/af.txt
grep -c '"vendax-agent"' /tmp/af.txt
grep -A12 '"vendax-agent"' /tmp/af.txt | grep -E '^"|Thread.State|at (br\.com\.archflow|java\.net|jdk\.internal\.net)'

# 3. o que o log diz
docker logs "$C" --since 720h 2>&1 | grep -iE 'vendax|invoke|falhou|não implementado|descartado|base-url|MCP' | tail -40
```

**Se as 4 threads `vendax-agent` aparecerem paradas em socket read**, o defeito 1+2+3 abaixo é a
causa e o subsistema está morto desde a quarta falha. **Se não houver thread `vendax-agent` nenhuma
parada e o log mostrar erro repetido**, olhe primeiro o defeito 5.

## Os defeitos

Todos foram encontrados por leitura de código e são reais independentemente de qual causou a parada.

### 1. `.get()` sem timeout no caminho de execução do agente

`archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java`

- linha ~278: `allowedTools(client.listTools().get(), ...)`
- linha ~399: `...callTool(new McpModel.ToolArguments(toolName, effectiveArgs)).get()`

`CompletableFuture.get()` sem prazo espera para sempre. Um MCP server que aceita a conexão e não
responde trava a thread chamadora indefinidamente.

**Correção:** prazo explícito em ambos (`get(n, TimeUnit.SECONDS)`), com valores distintos —
`listTools` é rápido e pode ser curto; `callTool` pode ser longo. O estouro precisa virar erro
classificável, não `TimeoutException` crua subindo como falha genérica.

### 2. Requests do MCP client sem timeout de leitura

`archflow-langchain4j/archflow-langchain4j-mcp/src/main/java/br/com/archflow/langchain4j/mcp/client/HttpMcpClient.java`

Linha ~89: `HttpClient.newBuilder().connectTimeout(timeout).build()`. No `java.net.http.HttpClient`,
`connectTimeout` cobre **apenas o TCP connect**. O prazo de resposta é por request
(`HttpRequest.Builder.timeout(...)`), e **nenhum dos requests o define** — veja as linhas ~148/154
(`DELETE` do close) e ~249/267 (`POST` do JSON-RPC).

**Correção:** aplicar `.timeout(...)` em todo `HttpRequest` construído aqui, derivado do `Duration`
que o construtor já recebe. É o conserto de raiz; o defeito 1 é a segunda linha de defesa.

### 3. Pool fixo de 4 com fila ilimitada

`archflow-api/src/main/java/br/com/archflow/api/config/ArchflowBeanConfiguration.java` linhas ~1196–1210:

```java
Executors.newFixedThreadPool(threads /* default 4 */, ...)
```

`newFixedThreadPool` usa `LinkedBlockingQueue` **sem limite**. Com os defeitos 1 e 2, bastam quatro
execuções penduradas para o subsistema de agentes morrer de vez: tudo que chega depois enfileira para
sempre, o Core segue recebendo 202, e o `/actuator/health` continua 200 porque a camada web é outro
pool. **A falha é permanente e invisível.**

**Correção:** agente é I/O-bound esperando o modelo — virtual threads (Java 21+) removem o
bloqueio de cabeça de fila. **Mas não troque o pool antes de corrigir 1 e 2**: sem timeout, virtual
thread transforma um travamento visível em vazamento invisível, acumulando milhares de threads
penduradas em vez de 4. Timeout primeiro, pool depois.

### 4. I/O bloqueante no `ForkJoinPool.commonPool`

Ainda no `HttpMcpClient`: são 3 usos de `CompletableFuture.supplyAsync(...)` **sem executor**, o que
os joga no `commonPool`, cujo paralelismo é `cores - 1` e que é compartilhado com o resto da
aplicação. Chamada de rede bloqueante ali contamina quem não tem nada a ver com agente.

**Correção:** executor próprio (virtual threads serve bem), ou o cliente HTTP assíncrono
(`sendAsync`), que não ocupa thread enquanto espera.

### 5. Client MCP cacheado nunca invalidado em falha

`archflow-api/src/main/java/br/com/archflow/api/mcp/vendax/VendaxMcpClientProvider.java` linhas ~38–39 e ~66–78:

```java
private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
public McpClient clientFor(String tenantId) {
    return clients.computeIfAbsent(tenantId, this::buildAndConnect);
}
```

Cachear por tenant está certo e é o que mantém a conexão quente. O problema é que **não há remoção**:
se a conexão morre ou o token expira depois de cacheada, `computeIfAbsent` devolve o client morto
para sempre e **todo invoke daquele tenant falha igual**. Isso combina bem com 18 falhas idênticas.

**Correção:** invalidar a entrada quando uma chamada falha por transporte/autenticação, e reconectar
na próxima. Cuidado para não invalidar por erro de negócio — só por falha de canal.

### 6. Nenhuma métrica do pool de agente

Não há contador de invoke recebido, execução iniciada, concluída, falhada, nem gauge de fila. É por
isso que este diagnóstico teve de ser feito por arqueologia, e é o que garante que a próxima falha
será descoberta do mesmo jeito.

**Correção — faça esta primeiro.** Micrometer, com o mínimo que responde "está rodando?":
`invokes recebidos`, `execuções concluídas`, `execuções falhadas` (com causa), `duração` e
`profundidade da fila`. E um health indicator que fique **DOWN quando o pool está saturado** — hoje
o health mente por omissão.

## Melhoria de desempenho (separada dos defeitos)

`McpAgentRunner` chama **`listTools()` a cada execução** — ida à rede ao Core mais montagem do
catálogo, toda vez. A conexão já está quente; o catálogo não.

**Correção:** cachear o catálogo de tools por tenant, invalidando pela versão da definição do agente
(que o Core já manda no payload, via `DefinicaoDeAgente`). Mexe em latência e em tokens ao mesmo
tempo, porque o catálogo entra no prompt.

Vale avaliar também **prompt caching do provedor** para o prefixo estável (system prompt + skills +
catálogo de tools). É o equivalente real de "manter o agente quente" — reduz custo e latência sem
manter processo nenhum de pé.

## Ordem sugerida

1. Métrica + health (defeito 6) — sem isso o resto é feito no escuro
2. Timeouts (defeitos 2 e 1, nessa ordem: raiz e depois defesa)
3. Invalidação do client cacheado (defeito 5)
4. Virtual threads no executor e no `supplyAsync` (defeitos 3 e 4)
5. Cache do catálogo de tools
6. Prompt caching

## Como verificar de verdade

Teste unitário não pega nada disso — os defeitos vivem todos na fronteira entre processos. O que
prova é um teste de integração que suba um MCP server falso e cubra os três casos que hoje param o
sistema: **servidor que não responde** (o timeout dispara e a thread volta), **servidor que derruba a
conexão depois de cacheada** (o client é invalidado e reconecta) e **rajada maior que o pool** (a fila
não cresce sem limite e a métrica acusa).

Depois: dispare **um** invoke real do Core e confirme o par completo — `llm_usage` ganhando linha no
Core e `Resultado do agente ... entregue ao Core` no log do ArchFlow.
