# ADR-0005 — Memória de longo prazo dos agentes: Brain Sentry atrás de um contrato só

- **Status:** Proposto
- **Data:** 2026-09-17
- **Decisores:** Edson Martins
- **Contexto de origem:** discussão sobre o archflow como harness de agente, em que a falta de
  memória entre execuções apareceu como lacuna; intenção declarada de usar o Brain Sentry
  (brainsentry.io) como memória de contexto.
- **Empilha sobre:** [ADR-0001 — Agent Runtime Substrate](0001-agent-runtime-substrate.md)
  (memória é substrato, não negócio) e
  [ADR-0006 — Fronteira de confiança do harness](0006-fronteira-de-confianca-do-harness.md)
  (cerca de conteúdo não confiável; identidade pelo transporte).
- **Detalhamento:** [Design 0008](../design/0008-memoria-de-longo-prazo-brain-sentry.md).

## Sumário

Hoje um agente do archflow não lembra nada de uma execução para a outra. A conversa de um fluxo
sobrevive a uma suspensão (`FlowStateChatMemory`), mas uma execução nova começa vazia, e o laço de
agente (`McpAgentRunner`) monta as mensagens do zero a cada vez.

Esta ADR decide que a **memória de longo prazo** do archflow é o **Brain Sentry**, acessado por
**um contrato só** e consumido pelos três lugares que chamam modelo, com cinco decisões:

- **D12.** Um contrato (`EpisodicMemory`) e um componente de uso, para todas as famílias.
- **D13.** Memória recuperada é conteúdo não confiável e entra como bloco de contexto cercado.
- **D14.** O escopo vem do transporte; o tenant é isolado no servidor.
- **D15.** Registra-se fato resumido, não transcrição.
- **D16.** Opcional e degradável: sem Brain Sentry, nada muda; com ele fora do ar, o agente segue.

## Contexto

O archflow chama modelo em três famílias de código, e cada uma trata memória de um jeito
(levantamento completo no design 0008, seção 2):

| Família | Memória hoje |
|---|---|
| **A.** Laço de agente (`McpAgentRunner`) — VendaX e nó `mcp-agent` | nenhuma |
| **B.** Nós de chat do fluxo (adapters OpenAI/Anthropic/OpenRouter, RAG) | janela de 20 mensagens no contexto, só dentro da execução |
| **C.** Entradas avulsas (AG-UI, assistente, orquestração dinâmica, conversation) | cada uma com a sua |

Já existem três abstrações de memória, e nenhuma resolve isso:

- `EpisodicMemory` (`archflow-conversation`) tem implementação Brain Sentry
  (`BrainSentryMemoryAdapter`), mas nada no runtime a chama.
- `MemoryProvider` (`archflow-agent`) diz no javadoc que o motor a chama; o motor não chama.
- `MemoryRestorer` (`archflow-core`) está ligada, mas só é chamada na **retomada**.

Ligar o Brain Sentry numa família só deixaria as outras sem memória — e um `llm-chat` e um
`mcp-agent` no mesmo fluxo já enxergam memórias diferentes hoje. Ligar em cada família do seu
jeito repetiria, dentro do archflow, a divergência entre produtos que a ADR-0001 veio eliminar.

A leitura do adaptador para esta decisão encontrou defeitos de isolamento que hoje não têm efeito
porque nada o chama (design 0008, seção 4). O mais grave: memória **sem tags** passa pelo filtro de
tenant e é devolvida a qualquer tenant.

## Decisão

### D12 — Um contrato, um componente, três consumidores

A memória de longo prazo é acessada **apenas** por `EpisodicMemory`, e o Brain Sentry é a
implementação de referência. Sobre ele, um componente do `archflow-api` concentra as regras desta
ADR e expõe duas operações: **recuperar** (devolve um bloco pronto para o prompt) e **registrar**
(assíncrono).

As três famílias consomem esse componente, e só ele. `MemoryProvider` e `MemoryRestorer` continuam
com os papéis que têm (variáveis de contexto; retomada) e não viram caminho para o Brain Sentry.

### D13 — Memória recuperada é conteúdo não confiável

O que volta da memória foi derivado de conversas com terceiros. Ela entra no prompt:

1. **dentro da `UntrustedContentFence`**, com a regra de que conteúdo cercado é dado, nunca
   instrução — o mesmo tratamento do resultado de tool MCP;
2. **como bloco de contexto**, nunca como mensagens antigas na janela. Mensagem antiga seria lida
   como fala do interlocutor atual, e a memória pode vir de outra conversa.

O `intercept` do Brain Sentry, que devolve o prompt já enriquecido pelo servidor, não é usado no
caminho dos agentes: a cerca e o lugar da memória no prompt são decididos do lado do archflow.

### D14 — Escopo pelo transporte, tenant isolado no servidor

- **Tenant:** isolado **no Brain Sentry**, não por prefixo de consulta e filtro no cliente. Cada
  tenant do archflow usa a **própria chave de serviço** (`bs_…`), que o Brain Sentry prende a um
  tenant e não deixa trocar por header. Tenant sem chave **não tem memória**. As tags `tenant:` e
  `context:` vão em toda busca como recorte do servidor, que é o que isola no modo de chave única.
  (Decidido em 17/09 após ler o código do Brain Sentry; implementado no PR #53.)
- **Escopo** (cliente, vendedor, conversa): declarado por quem aciona o agente e lido do invoke ou
  do contexto. **Nunca** de um argumento que o modelo preenche — a mesma regra da D17.
- Memória sem escopo verificável **não** é devolvida. "Não dá para filtrar" significa "não
  devolve", nunca "devolve tudo".

### D15 — Fato resumido, não transcrição

O que se registra é um resumo dos fatos relevantes do episódio, não a conversa crua. Transcrição
leva dados pessoais para outro sistema e enche a memória de ruído que depois volta ao prompt. O
custo do resumo, quando feito por modelo, é somado ao consumo da execução (D19).

### D16 — Opcional e degradável

- **Desligada por padrão.** Sem `archflow.brainsentry.baseUrl`, nenhum caminho de execução muda.
- **Recuperação com prazo curto** e circuit breaker. Falhou ou estourou: a execução segue **sem**
  memória, com log e métrica. Um agente interativo (D21) não pode perder o prazo esperando memória.
- **Registro assíncrono.** Não atrasa a resposta; a falha vira métrica, não exceção.
- **Observável:** por execução, quantas memórias vieram, quanto tempo levou e se o circuito estava
  aberto. Sem isso, "o agente não lembrou" é indistinguível de "o Brain Sentry estava fora".

## Consequências

### Positivas

- Agentes passam a acumular conhecimento sobre clientes, vendedores e conversas sem que cada
  produto construa a própria memória.
- Uma regra de segurança (cerca, isolamento) é escrita uma vez e vale para as três famílias.
- Fecha a inconsistência entre `llm-chat` e `mcp-agent` no mesmo fluxo.

### Negativas / riscos

- **Novo vetor de injeção de prompt.** Memória é conteúdo de terceiros que persiste e reaparece em
  outras execuções. A D13 mitiga; não elimina.
- **Dependência externa no caminho de execução.** Mitigada pela D16, ao custo de respostas que
  variam conforme a memória estava ou não disponível.
- **Dados pessoais em outro sistema.** A D15 reduz; a política de retenção e remoção passa a ser
  também do Brain Sentry (o adaptador hoje não suporta `clear`).
- **Dois ganchos novos no motor** (início e fim de execução) para a família B, porque o
  `MemoryRestorer` só roda na retomada.

### Neutras

- A memória dentro de uma execução (`FlowStateChatMemory`) continua como está.
- A gestão da janela dentro de uma execução longa (compactação) é outro problema e fica fora.

## Alternativas consideradas

1. **Ligar só no `McpAgentRunner`.** Rejeitada como solução final: cobre só a família A. Aceita
   como primeira fase, por ser o uso real de hoje (VendaX).
2. **Usar o `intercept` do Brain Sentry.** Rejeitada no caminho dos agentes: o archflow perderia o
   controle da cerca e do lugar da memória no prompt.
3. **Persistir a janela de chat entre execuções** (estender o `FlowStateChatMemory`). Rejeitada:
   janela crescente não é memória, e mensagens antigas no prompt são o que a D13 proíbe.
4. **Cada produto guarda a própria memória e manda no invoke.** É o que o VendaX faz hoje com a
   janela da conversa. Não é rejeitada: continua valendo como contexto de curto prazo, e a relação
   entre as duas fontes é uma decisão em aberto.

## Plano de adoção (ordem)

0. **Corrigir o adaptador** (design 0008, seção 4): a busca lê o formato real da resposta;
   memória sem tag não volta; `recall` respeita o contexto e devolve tenant/contexto reais;
   `getById` confere o tenant; `createMemory` passa pelo circuit breaker; chave por tenant.
   Pré-requisito de tudo. **Em revisão no PR #53.**
1. Componente de memória + família A (runner, `mcp-agent`, dispatcher do VendaX).
2. Família B, com os ganchos de início e fim no motor.
3. Família C, uma entrada por vez.

## Em aberto

1. ~~Isolamento de tenant no servidor~~ — decidido na D14 (chave por tenant). Resta: onde a chave
   de cada tenant fica guardada no `archflow-api`.
2. Escopo padrão dos agentes do VendaX: por cliente, por conversa, ou ambos.
3. Quem decide o que registrar: o agente (tool de "lembrar"), o fluxo (nó explícito) ou o harness
   (sempre, ao fim).
4. Família B: incluir o bloco em cada adapter, ou decorator de `ChatMemory`.
5. A memória do archflow complementa ou substitui a janela que o Core do VendaX manda no invoke.

## Referências

- `docs/design/0008-memoria-de-longo-prazo-brain-sentry.md`
- `archflow-brainsentry/src/main/java/br/com/archflow/brainsentry/BrainSentryMemoryAdapter.java`
- `archflow-conversation/src/main/java/br/com/archflow/conversation/memory/EpisodicMemory.java`
- `archflow-api/src/main/java/br/com/archflow/api/flow/FlowStateChatMemory.java`
- `archflow-api/src/main/java/br/com/archflow/api/trust/UntrustedContentFence.java`
