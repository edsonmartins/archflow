# ADR-0006 — Fronteira de confiança do harness de agente: o que o modelo não decide

- **Status:** Aceito (implementado)
- **Data:** 2026-09-17 — registro retroativo de decisões tomadas e implementadas entre
  08/2026 e 16/09/2026
- **Decisores:** Edson Martins
- **Contexto de origem:** integração com o VendaX Core (agentes QP, CS e NS via MCP). As decisões
  estavam registradas só em javadoc e em mensagens de commit dos PRs #39, #42, #43, #45, #49, #50
  e #51 (https://github.com/edsonmartins/archflow/pulls?q=is%3Apr+is%3Amerged).
- **Empilha sobre:** [ADR-0001 — Agent Runtime Substrate](0001-agent-runtime-substrate.md).

## Sumário

O `McpAgentRunner` e o dispatcher do VendaX formam o **harness de agente** do archflow: o código em
volta do modelo que decide o que ele pode fazer e o que acontece antes e depois de cada turno.
Nos últimos PRs, um mesmo princípio orientou várias decisões:

> **O que o ambiente sabe não se pergunta ao modelo, e o que o harness relata é o que aconteceu —
> não o que o modelo disse que aconteceu.**

Campo que o modelo preenche é campo que o modelo erra, e erra de forma plausível: o valor chega
bem formado e errado, sem erro nem log. Esta ADR registra as seis decisões que aplicam esse
princípio:

- **D17.** Identidade, correlação e origem viajam pelo transporte.
- **D18.** O que se devolve ao chamador é derivado do que aconteceu.
- **D19.** O consumo de modelo é contado por execução e relatado em todo resultado.
- **D20.** Quem hospeda o laço declara os erros terminais.
- **D21.** A classe interativa tem prazo, e o prazo é da resposta, não do laço.
- **D22.** Repetição é uma só, e só para o que uma repetição resolve.

"Harness" aqui é o **harness de agente**. O **harness de negócio** da ADR-0001 — quando acionar,
o que aceitar, o que mostrar — continua nos produtos.

## Contexto

Três incidentes medidos deram forma a essas decisões:

- **05/08.** No Core do VendaX, um campo de fator exposto ao agente foi preenchido com a quantidade
  falada e envenenou um léxico permanente.
- **07/08.** Auditoria: o `clienteRef` chegava ao server só como argumento de tool. Se o modelo
  copiasse o cliente errado, tudo ficaria coerente *para o cliente errado* — e nenhuma guarda do
  server teria como notar.
- **06/08.** O ThreadLocal de correlação era definido no dispatcher (`[vendax-agent-N]`), mas o
  passo do fluxo rodava em `[virtual-N]`. O header nunca saía, e nada falhava.

Depois vieram dois pedidos do VendaX com a mesma natureza: a task de origem, que decide **a quem
creditar uma venda** (30/08), e a consulta ao NS, em que o Core refaz a conta com o parâmetro
devolvido e recusa a frase que citar outro número (16/09).

## Decisão

### D17 — Identidade, correlação e origem pelo transporte

Cliente, vendedor, janela, trace e task de origem saem do invoke e vão por **header** em toda
chamada MCP (`X-Vendax-Cliente`, `-Vendedor`, `-Janela`, `-Trace`, `-Task`). O modelo não vê, não
escolhe e não pode alterar esses valores. Regras que acompanham:

1. **A ausência é escrita.** `CorrelacaoMcp.definir` sem valores **limpa** o ThreadLocal em vez de
   deixar o anterior, e todo caminho que define limpa no `finally`. Valor vazado entre execuções é
   pior que valor ausente: ausência é visível, presença errada não é contestada.
2. **Atravessa threads pelo contexto, não pelo ThreadLocal.** No fluxo, os valores viajam no
   `ExecutionContext` e são repostos na thread que chama as tools (`McpAgentComponent`). Em qualquer
   execução deslocada para outra thread (D21), a correlação é reposta lá.
3. **Captura na borda.** `HttpMcpClient.callTool` lê o valor na thread do chamador, antes do
   trabalho assíncrono, onde o ThreadLocal não existe.
4. **Opaco.** O archflow repassa; não interpreta nem valida o formato.

PRs #42 (correlação por header), #43 (atravessa thread), #49 (identidade) e #50 (task).
Classes: `CorrelacaoMcp`, `HttpMcpClient`, `VendaxAgentDispatcher`, `AgentFlowRunner`,
`McpAgentComponent`.

### D18 — O que se devolve é derivado do que aconteceu

Quando o resultado de um agente carrega um valor que também aparece numa chamada de tool, vale o
da **chamada**. Caso concreto: o `parametro` da consulta ao NS é lido dos argumentos da última
chamada bem-sucedida de `plano_negociacao`, não do JSON em que o modelo diz o que usou. Os números
da resposta saíram da tool com aqueles argumentos, e é com eles que o Core refaz a conta.

Corolário: **sem chamada, sem parâmetro.** Se o modelo não consultou a tool, qualquer número que
ele afirme foi inventado, e o resultado sai com o parâmetro vazio.

PR #51. Classe: `ConsultaAoNegociador`.

### D19 — Todo consumo chega ao consumidor, e chega uma vez

- O runner soma os tokens **a cada turno** num `ContadorDeUso` criado por quem hospeda a execução.
  Somar só no fim deixaria sem custo justamente as execuções que falham ou estouram o prazo.
- **Uma execução, um `execucaoId`**, gerado com o contador e presente em todo relato dela. O Core
  conta cada `(execucaoId, motivo)` uma vez — é o que torna a reentrega segura. **Não** é a chave de
  idempotência do result: execuções diferentes reusam a mesma chave, e cada uma gastou.
- O consumo vai em **todo** result ao VendaX (`VendaxResult.uso`), inclusive `ERROR`, e é omitido
  quando nenhum modelo foi chamado.
- **O que não tem result é relatado à parte** (`POST /api/v1/public/archflow/uso`, mesma assinatura):
  `SEM_RESULT` (QP sem cotação), `SUSPENSO` (fluxo esperando aprovação, com o gasto até ali) e
  `APOS_PRAZO` (o que o laço gastou **depois** do `ERROR` por prazo — só a diferença, para não contar
  o trecho anterior duas vezes). Reentrega em rede e 5xx; não em 4xx.
- **Detalhamento aditivo**, quando se sabe: provedor, tokens de entrada e saída, turnos, chamadas de
  tool que foram ao servidor, duração.
- O custo só existe com **preço declarado**, em **centavos de real por milhão de tokens**
  (`TabelaDePrecos`; `archflow.llm.precos[...]` ou `ARCHFLOW_LLM_PRECOS`). Sem preço, o custo é
  **nulo, nunca zero**: zero diria a um teto que a execução foi de graça. O valor é arredondado para
  cima, porque alimenta um teto.
- No fluxo, o contador viaja no contexto sob chave `transient`: é infraestrutura, não estado, e não
  vai para o checkpoint.

PRs #51 e #55. Classes: `ContadorDeUso`, `TabelaDePrecos`, `McpAgentRunner`, `VendaxResult`,
`VendaxUsoRelato`, `VendaxResultSender`.

### D20 — Erros terminais são declarados por quem hospeda o laço

Por padrão, o erro de uma tool volta ao modelo, que pode corrigir o argumento e tentar de novo.
Quem hospeda o laço pode declarar códigos JSON-RPC **terminais** (`Options.codigosQueEncerram`):
ao recebê-los, o laço para na hora e o resultado diz qual chamada o encerrou
(`Result.encerradoPor`). O runner não sabe o que um código significa; quem sabe é o chamador.

Caso concreto: `-32001` ("o agente NS não foi contratado") na consulta ao NS.

PR #51. Classe: `McpAgentRunner`.

### D21 — Classe interativa: o prazo é da resposta

Quando há uma pessoa esperando (ADR-025 D-2 do VendaX), a execução tem prazo
(`archflow.vendax.agent.prazo-interativo`, padrão 20 s):

1. A execução roda noutra thread para que o prazo valha mesmo com uma chamada de modelo pendurada,
   e a correlação é reposta nessa thread (D17).
2. Estourado o prazo, o `ERROR` sai **na hora**, com a mesma chave de idempotência, e sem
   reenfileirar.
3. O que a execução ainda produzir depois é **descartado**. Um resultado atrasado seria uma segunda
   resposta para a mesma chave.

PR #51. Classe: `VendaxAgentDispatcher`.

### D22 — Uma repetição, e só para o que ela resolve

O laço repete **uma vez** em três situações, e não em outras:

| Situação | Por quê |
|---|---|
| chamada de tool malformada pelo provedor (`call:nome{…}` como texto) | falha transitória de serialização; a mesma entrada funciona na tentativa seguinte |
| falha de transporte (timeout, conexão recusada, conexão encerrada sem resposta) — uma vez por laço | medido em rota fria; as chamadas seguintes reaproveitam a conexão |
| o modelo conclui sem chamar a tool de saída exigida (cobrança) | um turno a mais custa menos que a execução inteira perdida |

Não se repete: 4xx (é o provedor dizendo que a requisição está errada), nem 5xx/429 — esses
exigem espera, e repetir na hora contra um provedor sobrecarregado piora o quadro. Se o modelo
insistir, quem decide é o chamador.

PRs #39 (cobrança), #45 (malformada) e #49 (transporte). Classe: `McpAgentRunner`.

## Consequências

### Positivas

- Os erros mais caros — identidade trocada, crédito de venda atribuído à task errada, número
  inventado aceito — deixam de depender do comportamento do modelo.
- O custo de modelo passa a ter dono (a execução) e destino (o resultado).
- Falhas terminais e prazos estourados devolvem resposta rápida em vez de consumir turnos.

### Negativas / riscos

- **A armadilha de thread é recorrente.** Já apareceu três vezes (correlação, identidade, prazo) e
  não produz erro quando acontece: o header simplesmente não sai. Toda nova fronteira de thread
  precisa de teste que leia o valor **dentro** dela.
- **O server precisa conferir.** Headers só protegem se o server os usar no lugar do argumento; o
  lado do VendaX está no outro repositório.
- **A retomada não tem ponte para o VendaX.** Levantado dos dois lados em 17/09:
  - só o `runAndReport` do dispatcher envia result; nenhum caminho de retomada passa por ele;
  - `McpAgentRunner.resume` não tem chamador — um `mcp_agent_states` suspenso fica órfão;
  - a aprovação (`ApprovalQueueService.submitDecision`) retoma sem `McpAgentHost` no contexto, então
    um passo `mcp-agent` depois dela falharia;
  - execuções do VendaX não são registradas no `WorkflowRuntimeStore`, então `/resume` não as acha;
  - `conversationId`, `agent` e `saidaSchema` não são persistidos, então não há como montar o
    result depois.

  **Decisão:** a suspensão relata o gasto até ali (`SUSPENSO`) e não envia result; o Core trata esse
  relato como final. A ponte não é desenhada agora, porque **nenhum agente do VendaX suspende** —
  todas as políticas são `ToolApprovalPolicy.none()`, nenhum fluxo tem nó `APPROVAL` e o Core não
  chama `/api/approvals` nem `/resume`. Quando houver aprovação humana num agente do VendaX, a
  retomada precisará: guardar o vínculo execução↔invoke (`conversationId`, `agent`, `saidaSchema`,
  `idempotencyKey`); injetar o `McpAgentHost` no caminho da aprovação; e chamar
  `VendaxResultSender.send` ao terminar, com o uso gasto depois da retomada no próprio result.
  (Os outros buracos de consumo — execução sem result e gasto depois do prazo — foram fechados no
  PR #55, que também passou a tratar `PAUSED` como suspensão, e não como saída vazia.)

### Neutras

- As decisões são genéricas, mas a primeira aplicação é o VendaX; os nomes dos headers ainda têm o
  prefixo `X-Vendax-`.

## Alternativas consideradas

1. **Validar o argumento do modelo contra o invoke, em vez de usar header.** Rejeitada: o server
   continuaria tendo o argumento como única fonte, e a validação seria mais uma regra a esquecer.
2. **Confiar no parâmetro declarado pelo modelo (D18).** Rejeitada: divergência entre o dito e o
   feito recusaria a resposta certa ou aprovaria uma errada.
3. **Custo zero sem preço (D19).** Rejeitada: um teto contaria errado sem aviso.
4. **Prazo dentro do laço, checado entre turnos (D21).** Rejeitada: não limita uma chamada de modelo
   pendurada.

## Referências

- PRs #39, #42, #43, #45, #49, #50 e #51 (mensagens de commit com as medições)
- `archflow-langchain4j/archflow-langchain4j-mcp/src/main/java/br/com/archflow/langchain4j/mcp/client/CorrelacaoMcp.java`
- `archflow-api/src/main/java/br/com/archflow/api/agent/mcp/McpAgentRunner.java`
- `archflow-api/src/main/java/br/com/archflow/api/agent/vendax/VendaxAgentDispatcher.java`
- `archflow-api/src/main/java/br/com/archflow/api/agent/vendax/ConsultaAoNegociador.java`
- Testes: `TaskDeOrigemTest`, `ConsultaAoNegociadorTest`, `EncerramentoEUsoTest`, `HttpMcpClientTest`
