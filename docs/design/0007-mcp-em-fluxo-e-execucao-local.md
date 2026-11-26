# Design 0007 — MCP dentro do fluxo e execução local (edge)

Dois problemas que apareceram integrando o **VendaX Sales Copilot** ao archflow. São
independentes na implementação e acoplados no destino: quem roda perto do dado
também precisa das ferramentas, e quem tem ferramentas precisa saber onde elas
rodam.

Este documento descreve o estado atual, as opções, e o que cada uma custa.

**Estado em 02/08/2026: a Parte A está resolvida; a Parte B continua um levantamento
para decidir junto.** A Parte A foi reescrita nessa data porque descrevia o nó de MCP
como inexistente muito depois de ele ter sido construído — e documento de levantamento
que envelhece sem aviso é lido como estado atual. Foi o que aconteceu: uma revisão de
spec do VendaX listou como "o que construir" um trabalho que já estava pronto.

Ao mexer aqui: se a Parte B for decidida, atualize-a do mesmo jeito, ou marque-a como
histórica. O custo de um documento desatualizado não é ele estar errado — é ele ser
convincente.

---

## Parte A — RESOLVIDA: o nó de fluxo chama tools MCP

> **Atualizado em 02/08/2026.** Esta parte descrevia o nó de MCP como inexistente e discutia três
> opções para construí-lo. A opção (2) **foi implementada**, e o texto abaixo passou a descrever um
> estado que já não existia — a ponto de induzir uma revisão de spec do VendaX a listar como "o que
> construir" um trabalho que estava pronto. O levantamento original está no histórico do git; aqui
> fica o que vale.

### A.1 O que existe

| classe | o que resolve |
|---|---|
| `McpAgentRunner` | o laço agente↔tools |
| `ToolAccessPolicy` | allowlist — o que o agente pode chamar |
| `ToolApprovalPolicy` | o que exige decisão humana |
| `ToolTrustPolicy` / `ToolTrust` | confiança por origem de tool |
| `ToolArgumentValidator` | valida argumentos antes da chamada |
| `ToolCatalogBudget` | teto de tokens do catálogo por turno |
| `McpAgentStateStore` (InMemory/Jdbc) | estado do agente entre turnos |
| **`McpAgentComponent`** | **o laço acima como passo de fluxo** |
| **`McpAgentHost`** / `ConfiguredMcpAgentHost` | **de onde vêm runner, cliente MCP e teto de tools** |

`McpAgentComponent` é **componente do catálogo**, não tipo de nó servido por adapter. A razão é a
que o levantamento original já apontava: o valor do runner não é o laço, é a política em volta dele.
Reimplementá-la na config de um adapter seria reescrever seis classes maduras num lugar onde o erro
é silencioso — uma allowlist que não pega deixa o agente chamar tool destrutiva sem ninguém notar.
Como componente, ele ainda herda o `ComponentAccessPolicy` do motor — o `allowing(...)` da DSL —
que vale inclusive para sub-agentes de um nó de orquestração.

### A.2 Config do nó

```
systemPrompt   (obrigatório) instrução do agente
tools          (lista)       allowlist; ausente = todas as do server, sujeitas ao teto do host
server         (texto)       REFERÊNCIA ao servidor MCP; ausente = padrão do host
maxIterations  (inteiro)     voltas do laço; ausente = padrão do runner
```

Saída: `text`, `toolCalls`, `suspended` e — quando suspenso — `approvalRequestId` e `pendingTool`.
A suspensão é explícita de propósito: tratar um laço suspenso como conclusão entregaria ao passo
seguinte uma resposta parcial como se fosse final, e a aprovação pendente sumiria sem ninguém a ver.

### A.3 As três decisões que o levantamento deixou em aberto

**1. De onde vem o cliente MCP — resolvido: do contexto de execução.**
`McpAgentHost` é injetado no `ExecutionContext` sob a chave `mcp.host`; o nó carrega `server` como
*referência*, e o host resolve `clientFor(tenantId, serverRef)`. Endereço e credencial nunca entram
no documento do fluxo, que é versionado e visível no designer. É também o que permite o mesmo
documento rodar na nuvem e num agente local — cada lado fornece o seu host.

Os servidores vêm de `archflow.mcp.servers.<ref>.base-url|service-token`. A chave antiga
`archflow.vendax.mcp.*` é semeada sob `ref = "vendax"`, para instalação existente não precisar
editar configuração.

**2. Quem manda a allowlist — resolvido: interseção, nunca soma.**
`McpAgentComponent.politicaDeAcesso` intersecciona a lista do nó com `host.toolCeiling(tenantId)`.
Sem lista no nó vale o teto; sem os dois vale `allowAll()` **declarado**, não por omissão. É o que
impede que, num produto onde o cliente edita o fluxo, ele conceda ao agente tools que a plataforma
não lhe deu.

**3. Aprovação humana: nó `approval()` ou `ToolApprovalPolicy` — AINDA ABERTO.**
Continuam existindo dois mecanismos para "parado esperando gente". O componente já devolve
`suspended` e `approvalRequestId`, o que torna viável materializar a suspensão num nó de approval —
mas a decisão não foi tomada, e enquanto não for, uma suspensão decidida dentro do runner não
aparece no designer.

**4. Estado — AINDA ABERTO.** `McpAgentStateStore` e o `StateManager` do motor guardam coisas
diferentes do mesmo agente. Falta decidir quem é a fonte da verdade num replay.

### A.4 O que sobrou

- **`mcp-tool`** (chamada direta de tool, sem laço) está no catálogo da UI marcado
  `runtimeStatus: design_only` e **não tem componente no backend**. Quem montar um fluxo com ele
  descobre em execução. Ou ganha construtor, ou sai da paleta.
- O caminho VendaX do `archflow-api` (`QpAgentService`, `switch` por nome de agente,
  `CS_SYSTEM_PROMPT`) **não usa nada disto** — foi por ele que todo agente passou, e é por isso que
  o componente ficou construído e sem ligação até 02/08. Ver `RFC-013` v2 do VendaX, §4.2.

## Parte B — execução local (o modelo do agente iPaaS)

A ideia: o agente recebe o fluxo da plataforma, **executa localmente** — perto do
dado, possivelmente com modelo local — e devolve **telemetria** para a plataforma
acompanhar. Mesmo modelo dos agentes do Mentors iPaaS.

### B.1 O que já existe

Mais do que parece.

- **`archflow-standalone`** — `StandaloneRunner` e o modelo serializável
  (`SerializableFlow`, `SerializableStep`, `SerializableConnection`,
  `SerializableFlowConfig`, `YamlFlowSerializer`).
- **`EmbeddedWorkflowRunner`** no SDK — executa um `WorkflowDocument` no próprio
  processo, com `agentId` e timeout.
- **Protocolo de telemetria, em protobuf** — `archflow-events-proto` define
  `EventEnvelope` (id, timestamp, `correlation_id`, `execution_id`, `tenant_id`),
  `FlowEvent`, e — o que interessa aqui — **`FlowEventBatch` com
  `source_agent_id`** e `IngestResponse`.
- **Publicador** — `ProtobufEventPublisher` posta lotes por HTTP assíncrono.
- **Ingestão** — `EventIngestController` / `SpringEventIngestController` no lado
  da plataforma.

Ou seja: **a metade "devolve telemetria" está construída**. O `source_agent_id`
no lote é exatamente o campo de um agente remoto identificado.

### B.2 O que falta

**1. Publicar `archflow-standalone` no Maven Central.**
Hoje só `archflow-dsl`, `archflow-sdk-java` e `archflow-model` estão lá. O
`EmbeddedWorkflowRunner` declara o standalone como dependência `optional`, então
nada quebra na resolução — e falha em runtime com uma mensagem dizendo que
precisa do build do fonte. Enquanto isso, o modelo não se instala por
dependência, que é o que o torna adotável.

**2. Como o agente recebe o fluxo.**
`client.get(id)` já traz o documento. Falta o conceito acima dele: **atribuição**
(qual agente roda qual fluxo), **versão** e **rollout**. Sem isso, cada instalação
vira configuração manual, e atualizar um fluxo em vinte clientes é vinte
operações.

**3. Identidade e ciclo de vida do agente.**
`source_agent_id` existe no lote, mas não há registro (*enrolment*), credencial
por agente nem revogação. Um agente que roda dentro do cliente é uma fronteira de
confiança: precisa poder ser desligado do lado de cá.

**4. Fila de trabalho — o ponto que decide a topologia.**
No caso do VendaX (RFC-013, topologia C) a plataforma **não alcança** a rede do
cliente: não há como fazer push de uma invocação. O agente precisa **puxar**
trabalho — long-poll ou fila autenticada. Hoje `execute` é o cliente chamando o
servidor, o oposto. Sem este endpoint, "rodar local" só funciona para fluxos
disparados por gatilho local (cron, arquivo, fila do próprio cliente), não para
os que a plataforma origina.

**5. Segredos e config de modelo ficam no edge.**
É a razão de existir do modo local — modelo on-premise (Ollama, vLLM) ou chave do
próprio cliente. Consequência: a plataforma **não pode** assumir que resolve
`LLMConfig` centralmente. O resolvedor precisa aceitar "este agente resolve
sozinho", e a plataforma precisa lidar com não saber qual modelo rodou.

**6. Plugins: o agente baixa os que o fluxo usa.** — ver §B.3, é o item com mais
peça pronta e mais decisão pendente.

**7. Telemetria de um ambiente que existe para não vazar dado.**
Se o motivo de rodar local é privacidade, o evento não pode carregar o conteúdo.
`FlowEvent`/`ScalarValue` transportam valores. Falta um modo **sem payload** —
só transições de estado, tempos, custo, erro — e uma política de redação
declarada no fluxo ou na instalação. Sem isso, o cliente que exigiu execução
local está mandando o dado pela porta dos fundos.

**8. Aprovação humana no edge.**
`approval()` suspende de forma durável, e o estado é local. Quem aprova, e por
qual tela? Ou a suspensão sobe para a plataforma (e aí o *que* está sendo
aprovado precisa subir junto — ver item 7), ou o edge precisa de superfície
própria de aprovação.

### B.3 Plugins: o agente baixa os que o fluxo usa

É o modelo do Mentors iPaaS — o jar do agente recebe o fluxo e **baixa os plugins
que aquele fluxo usa** antes de executar. Resolve de uma vez o problema de "o
fluxo referencia um componente que não existe nesta instalação", e é o que torna
o edge instalável sem provisionamento manual por cliente.

A boa notícia: quase tudo já existe, espalhado por três módulos.

| peça | onde | o que faz |
|---|---|---|
| `ExtensionManifest` | `archflow-marketplace` | `name`, `version`, `entryPoint`, `type`, `permissions`, `dependencies`, `signature`, `minArchflowVersion` |
| `ExtensionInstaller` | `archflow-marketplace` | instala de arquivo local **ou URL**, com `verifySignatures` |
| `ExtensionSignatureValidator` | `archflow-marketplace` | assinatura contra chaves confiáveis (X.509 RSA, Base64) |
| `PermissionValidator`, `DependencyResolver` | `archflow-marketplace` | permissões declaradas e fecho de dependências |
| `MarketplaceController` | `archflow-api` | superfície de instalação na plataforma |
| `FlowPluginManager` | `archflow-agent` | carrega os jars do `pluginsPath`, uma vez, com isolamento de classloader e `onLoad`/`onUnload`; falha de carga **propaga** em vez de sumir |
| `ArchflowPluginManager` | `archflow-plugin-loader` | descoberta por `META-INF/services` |

O que falta é o **elo entre o fluxo e essa lista**, mais quatro decisões.

**a) De onde sai a lista de extensões de um fluxo.**
Cada passo tem `componentId`, mas não há mapa `componentId → extensão@versão`.
Três caminhos:

- *derivar no edge*, consultando um registry da plataforma em tempo de preparo —
  automático, porém o resultado muda quando o registry muda, e o mesmo fluxo
  passa a rodar diferente em dias diferentes;
- *declarar no documento do fluxo* — reprodutível e revisável, mas quem escreve o
  fluxo passa a manter a lista à mão;
- *a plataforma calcular o fecho na publicação* e **fixar as versões** no
  documento distribuído.

A terceira é a que dá a propriedade que interessa no edge: *mesma versão de
fluxo, mesmo conjunto de plugins*. Sem isso não há como reproduzir um incidente
no cliente — e é justamente no cliente que não se tem acesso para investigar.

**b) A assinatura deixa de ser opcional.**
Baixar plugin é baixar código que roda com os privilégios do processo, no
ambiente do cliente. `verifySignatures` existe e é parâmetro; no edge precisa ser
**default ligado**, com a chave da plataforma embarcada no jar do agente. O
problema do `onLoad` não desaparece — passa a ser governado: o agente só executa
o que a plataforma assinou.

**c) Cache e operação sem rede.**
O agente roda dentro do cliente e não pode parar porque a plataforma ficou
inacessível. Cache endereçado por conteúdo (`name@version` + hash), instalação
idempotente, e execução com o que já está em disco quando o download falha — com
telemetria dizendo que rodou com cache, não em silêncio.

**d) Versão do runtime e permissões, verificadas **antes** de rodar.**
O manifesto já traz `minArchflowVersion` e `permissions`. O agente no cliente
pode estar mais velho que a plataforma, e a instalação pode não ter concedido uma
permissão que o plugin pede. Os dois casos precisam falhar **no preparo**, com
mensagem dizendo qual plugin e qual requisito — não no meio da execução, onde o
sintoma vira um passo que não roda.

Vale também a plataforma recusar a *distribuição* de um fluxo cujos plugins
exigem runtime mais novo do que o agente informou no registro (§B.2 item 3) — o
erro aparece para quem publica, que é quem pode corrigir, em vez de aparecer no
cliente.

### B.4 A pergunta que ordena o resto

**O edge é um executor burro ou um agente autônomo?**

- **Executor burro** — recebe fluxo e entrada, executa, devolve resultado e
  telemetria. A plataforma orquestra. Exige a fila do item 4 e pouco mais.
- **Agente autônomo** — tem gatilhos próprios, decide quando rodar, sincroniza
  quando dá. Exige tudo acima, mais reconciliação de estado e resolução de
  conflito.

O caso do VendaX é o primeiro. O modelo do iPaaS, pelo que descrevem, tende ao
segundo. Vale escolher antes de construir, porque o item 4 muda de forma conforme
a resposta.

---

## Como as duas partes se encontram

Se A sair como componente do catálogo (opção 2) e B avançar, o agente local passa
a precisar do cliente MCP e da política de tools **dentro dele**. Duas
consequências:

1. O componente MCP não pode depender de nada que só exista no processo do
   servidor. Se a resolução do cliente MCP for por injeção no contexto de
   execução (A.3 item 1), o edge fornece a sua — e é isso que permite o mesmo
   fluxo rodar nos dois lugares.
2. O servidor MCP do VendaX é SaaS; o agente estaria no cliente. A chamada sai da
   rede do cliente para a nuvem, o que é aceitável — mas inverte a suposição do
   item 7: o *argumento* da tool sai do ambiente local por definição. A política
   de redação precisa valer para chamadas de tool, não só para eventos.

---

## Referências

- `docs/USANDO-O-SDK-JAVA.md` — DSL e SDK 1.1.0; seção 5.2 e seção 7
- `archflow-api/.../agent/mcp/` — o runtime de agente MCP
- `archflow-api/.../flow/adapter/AdapterNodeTypes.java` — roteamento de nó → adapter
- `archflow-api/.../flow/DefaultFlowStepFactory.java` — construção de passo
- `archflow-events-proto/.../flow_events.proto` — `FlowEventBatch.source_agent_id`
- VendaX `RFC-013` — catálogo de agentes, slots por tenant, topologias A/B/C
