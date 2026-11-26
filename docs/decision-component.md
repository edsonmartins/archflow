# Nó de decisão (`decision`)

Um nó que faz **perguntas tipadas** sobre um estado a um modelo de decisão — ou a um LLM, ou a regras —
e devolve a resposta **com a confiança e uma rota**. O fluxo decide o que fazer com ela pelas condições
das arestas, que é a única ramificação que o motor tem.

É genérico em três eixos: **quem decide** (provedor plugável), **o que se decide** (perguntas
declaradas no nó) e **o que fazer com a incerteza** (limiares e rota). Nada aqui conhece um fornecedor,
uma aplicação ou um agente.

## Perguntas

| tipo | pergunta | resposta |
|---|---|---|
| `choice` | qual destas opções? (`criteria`: opção → descrição) | a opção, `probabilities`, `confidence` |
| `score` | onde isto cai numa escala ordenada? (`criteria`: lista de níveis, ao menos 2) | `score` (fracionário), `probabilities`, `legend`, `confidence` |
| `noul` | esta condição vale? (`criteria`: `{true, false}`, opcional) | `probability`, e `confidence = \|2p − 1\|` |

Perguntas sobre o mesmo estado são independentes: nenhuma enxerga a resposta das outras.

## Configuração do nó

```yaml
- id: triagem
  type: decision
  componentId: decision
  config:
    provider: http-decisions            # padrão
    model: typesafe/jev-1.13            # fixe a versão em produção; ~typesafe/jev-latest muda sozinho
    questions:
      time:
        type: choice
        instructions: Qual time cuida deste ticket?
        criteria: { pagamentos: "Cobrança, checkout.", acesso: "Login, senha." }
      urgencia:
        type: score
        criteria: ["pode esperar", "esta semana", "bloqueia receita"]
      e_defeito:
        type: noul
    state: { ticket: "${input}", plano: "${cliente.plano}" }   # opcional; padrão: a entrada do nó
    primary: time                       # a pergunta cujo valor vira `decision`; padrão: a primeira
    gate: [time, e_defeito]             # perguntas que decidem a rota; padrão: todas
    thresholds: { auto: 0.9, review: 0.5 }
    onError: ESCALATE                   # ou FAIL
    timeoutMs: 10000
    fallbacks:                          # tentados em ordem quando um provedor FALHA
      - provider: llm
        model: openai/gpt-4o-mini
    cascade:                            # degraus seguintes, se o anterior respondeu com pouca confiança
      - { provider: http-decisions, model: upstage/solar-decide }
    acceptAt: 0.9                       # confiança que encerra a cascata; padrão: thresholds.auto
    consensus:                          # outros modelos votam sobre o mesmo estado
      models: [ { model: liquid/d1 } ]
      onDisagree: REVIEW                # ou ESCALATE
    providerOptions:
      http-decisions: { endpoint: "https://openrouter.ai/api/v1/systemone", retries: 1 }
```

`apiKey` no nó é **ignorada** (com aviso): a chave vem do resolvedor do tenant.

## Saída e arestas

O nó grava no contexto, sob o seu `id`:

| campo | |
|---|---|
| `route` | `AUTO`, `REVIEW` ou `ESCALATE` |
| `confidence` | a **menor** confiança entre as perguntas do `gate` (uma incerta rebaixa a decisão toda) |
| `decision` | o valor da pergunta `primary` |
| `primary` | o id da pergunta cujo valor virou `decision` |
| `answers.<id>` | `type`, `value`, `confidence`, **`route`** (a rota que *esta* pergunta teria sozinha, pelos limiares do nó), `probabilities`, `choice`/`score`/`probability`, e no `noul` `result` (`probability ≥ 0,5`, `true`/`false`) |
| `provider`, `model`, `calibrated`, `usage` | quem respondeu, e o consumo (`costUsd` quando informado) |
| `error` | só quando a decisão não saiu |

```json
{ "sourceId": "triagem", "targetId": "resolver",  "condition": "${triagem.route} == 'AUTO' && ${triagem.decision} == 'pagamentos'" },
{ "sourceId": "triagem", "targetId": "confirmar", "condition": "${triagem.route} == 'REVIEW'" },
{ "sourceId": "triagem", "targetId": "humano",    "condition": "${triagem.route} == 'ESCALATE'" }
```

`REVIEW` e `ESCALATE` ligam naturalmente a um nó `APPROVAL` (decisão humana, que já é durável).

### O ramo vem da resposta; a rota é a confiança
`route` diz **o quanto a resposta é confiável** (`RoutePolicy` sobre `confidence`), não **qual** foi a resposta.
Um pedido comum e um urgente respondidos com a mesma confiança seguem a mesma rota, então ramificar as
categorias pela rota manda os dois para o mesmo lugar (RCHFLW-116: o "Gerar com IA" ligou `AUTO` ao LLM e
`ESCALATE` ao humano, e todo pedido seguiu o mesmo ramo). Ramifique pela **resposta** — `${D.decision}`
(o valor da `primary`), `${D.answers.<id>.choice}` ou, num `noul`, `${D.answers.<id>.result}` — e use a rota
só como portão de confiança. A triagem "urgente vai ao humano, o resto o LLM responde":

```json
{ "id": "triagem", "type": "decision", "componentId": "decision", "configuration": {
    "provider": "llm",
    "questions": {
      "assunto": { "type": "choice", "instructions": "Qual o assunto do pedido?",
                   "criteria": { "financeiro": "cobrança, pagamento", "suporte_tecnico": "defeito, sistema fora do ar",
                                 "outro": "qualquer outro assunto" } },
      "urgente": { "type": "noul", "instructions": "O pedido é urgente?",
                   "criteria": { "true": "algo parado ou fora do ar", "false": "dúvida que pode esperar" } } },
    "primary": "assunto", "gate": ["urgente"] },
  "connections": [ { "sourceId": "triagem", "targetId": "e_urgente" } ] },
{ "id": "e_urgente", "type": "condition", "componentId": "condition", "configuration": {
    "conditionExpression": "${triagem.answers.urgente.result} == true || ${triagem.route} == 'ESCALATE'" },
  "connections": [ { "sourceId": "e_urgente", "targetId": "humano",    "branch": "true" },
                   { "sourceId": "e_urgente", "targetId": "responder", "branch": "false" } ] }
```

O `|| ${triagem.route} == 'ESCALATE'` manda ao humano a decisão que falhou ou saiu com confiança abaixo de
`review`: uma falha nunca vira o caminho comum. Com o provedor `llm` a confiança é limitada a 0,89, abaixo do
`auto` padrão (0,9) — uma aresta `AUTO` desse nó **nunca** é seguida, e não pode ser o único caminho do caso
comum. Por categoria, o mesmo vale com um `switch` sobre `${triagem.decision}` (um caso por opção; a decisão
que falhou tem `decision` nulo e cai no `default`). `TriagemPelaRespostaDaDecisaoTest` executa esse desenho.

O `condition` repassa a saída da decisão sem mudar, e o `llm-chat` depois dele receberia esse mapa (`route`,
`answers`, …) como mensagem, não o texto do cliente. Por isso o `responder` do exemplo diz qual valor é a mensagem
(RCHFLW-120):

```json
{ "id": "responder", "type": "llm-chat", "componentId": "llm-chat", "configuration": {
    "systemPrompt": "Responda ao pedido do cliente em uma frase.",
    "userMessage": "${receber}" } }
```

### A "Mensagem do usuário" do LLM Chat (`userMessage`)
Campo opcional dos nós de chat (`llm-chat` e o `llm-streaming` legado). É um texto com referências `${…}`
resolvidas **como numa condição** — o mesmo resolvedor (`ConditionEvaluator.resolvePath`), as mesmas raízes:

- `${<id da Entrada>}` é a mensagem que chegou à execução; `${<id>}` a saída de um passo, `${<id>.campo}` um campo
  dela; `${input}` é a **entrada do passo** — a saída do passo anterior, como nas condições (depois de um
  `decision`/`condition`, o JSON da classificação);
- texto fora de `${}` vai como está e pode misturar: `"Cliente: ${receber}"`;
- texto vai como texto, número e booleano pelo texto deles, **mapa, lista e outros objetos viram JSON** — num
  template misto (`"Cliente: ${receber}"`). **Uma referência sozinha** (`${receber}`) é lida como a mensagem, como o
  nó de chat lê a própria entrada (`messageText`): texto vai como veio e um mapa dá a primeira chave de texto entre
  `input`/`message`/`text`/`prompt` (sem nenhuma, o JSON). Assim `${receber}` serve para o texto digitado no
  Executar e para o chamador de API que manda `{"message": "…"}`;
- **vazio = o de sempre**: a mensagem é a entrada do passo. Nenhum fluxo existente muda;
- referência sem valor na execução, `${` sem fechar e resultado em branco **falham o passo** nomeando nó, campo e
  referência — nada vazio nem `${…}` literal vai ao modelo, e nenhuma chamada é feita.

A checagem estática é a das condições (`ControlExpressionRules.checkTemplate` / `controlExpressions.ts`, com os
casos de `controlExpressionCases.json`): `MALFORMED_TEMPLATE` (`${` que não fecha, vazio ou aninhado) e
`TEXT_OUTPUT_FIELD` são recusados no editor e no servidor; `UNKNOWN_STEP` impede o Executar do editor e só avisa
no servidor. A gramática não é a da comparação: não há operador, e texto sem referência é literal. O copiloto e o
`nl-to-flow` preenchem o campo no LLM que responde depois de um `decision`/`condition`/`switch`; o `nl-to-flow`
avisa em `observacoes` quando o rascunho esquece.

### O ramo da aresta (`branch`)
Em vez de montar a condição à mão, a aresta pode declarar o ramo que quer dizer, e a factory a converte:

```json
{ "targetId": "resolver",  "branch": "AUTO" }       // → ${triagem.route} == 'AUTO'
{ "targetId": "humano",    "branch": "ESCALATE" }
```

Vale para `decision` (rotas `AUTO`/`REVIEW`/`ESCALATE` — o portão de confiança, não a categoria) e para os
nós `condition`/`switch` (abaixo). Uma `condition` escrita à mão na mesma aresta **vence** o ramo.

## Nós `condition` e `switch`
Até aqui existiam só no designer: sem tratamento na factory, caíam no catálogo como componentes
inexistentes. Agora são passos de verdade (`ControlStep`) que **calculam o ramo**; quem ramifica
continua sendo a condição da aresta.

- `condition`: avalia `conditionExpression` (a sintaxe das arestas) e grava `branch = "true" | "false"`.
  Expressão malformada ou que não se avalia é `false` (com o motivo em `error`), nunca "verdadeira".
- `switch`: resolve `switchExpression` e, se o valor é um dos `switchCases` (um por linha, sem
  diferenciar maiúsculas), grava `branch = <o caso>`; senão `"default"`.
- A **entrada segue intacta**: um nó de controle decide o caminho, não transforma o dado.

### A expressão é conferida antes de executar (RCHFLW-116)
O avaliador é permissivo: um operando sem `${}` é procurado como variável e, se não existe, vira o próprio
texto. `ticket.urgente == true` comparava a string `"ticket.urgente"` com `true` e dava sempre `false` — o
pedido urgente seguia o ramo errado sem erro nem aviso. Agora a expressão de um `condition`/`switch` é recusada
**antes do primeiro passo** (`ExecutableNodesValidator`, no servidor e no standalone; `runBlockers`, no
Executar do editor), com o nó e o campo, quando:

| código | o que é | exemplo recusado | forma certa |
|---|---|---|---|
| `MALFORMED` | fora da gramática (`operando op operando`, unidos por `&&`/`\|\|`) | `${x} == 1 &&` | `${x} == 1` |
| `BARE_OPERAND` | operando que não é `${caminho}`, texto entre aspas, número nem `true`/`false` | `ticket.urgente == true`, `${t.route} == ESCALATE` | `${t.route} == 'ESCALATE'` |
| `UNKNOWN_STEP` | `${X.campo}` em que nenhum prefixo é id de passo nem campo declarado no nó Entrada, e `X` não é raiz que o servidor grava (`input`, `step`, `llm`, `archflow`, `__archflow`, `approvalPayload`, `payload`, `vendax`) | `${triagem.route}` com o **rótulo** do nó | `${node-1712345678901.route}` |
| `TEXT_OUTPUT_FIELD` | `${X.campo}` com `X` um LLM Chat — a saída dele é o texto da resposta | `${classificar.urgente} == true` | `${classificar} contains 'URGENTE'`, ou um nó `decision` |
| `NO_REFERENCE` | switch sem nenhum `${…}` | `triagem.decision` | `${triagem.decision}` |
| `MALFORMED_TEMPLATE` | na `userMessage` do LLM Chat, um `${` que não fecha, vazio ou aninhado | `Cliente: ${receber` | `Cliente: ${receber}` |

Para não recusar entrada legítima: `${nome}` **sem ponto** é aceito mesmo sem passo com esse id — as chaves do
JSON de entrada viram variáveis e só existem na execução. Um campo **aninhado** de uma chave da entrada
(`${ticket.urgente}` com a entrada `{"ticket": {"urgente": true}}`) resolve na execução, mas estaticamente não se
distingue de uma variável inventada: ele passa quando a chave (`ticket`) está em **Campos de entrada esperados**
do nó Entrada (`ioFields`). É a forma que funciona com qualquer entrada — `${<id da Entrada>.ticket.urgente}` não
resolve quando a entrada também traz a chave `input` (a Entrada guarda só o valor dela), e o fluxo sem nó Entrada
não tem id para prefixar. As raízes que o servidor grava fora dos passos também passam: o `approvalPayload` da
aprovação e, no invoke do VendaX, o `payload` lido como objeto (`${payload.pergunta}`) e as chaves `vendax.*`.
**`UNKNOWN_STEP` só é recusado no editor.** No servidor (`ExecutableNodesValidator`) ele vira um aviso no log:
fluxos criados por API — o VendaX Core, um YAML, o standalone — leem chaves da entrada sem declará-las na Entrada,
e o `${X.campo}` resolve quando a entrada do run traz `X`. Recusar no servidor quebraria fluxos que funcionam
hoje. Os outros quatro códigos nunca funcionam (comparam o próprio texto ou leem campo de uma saída de texto) e
são recusados nos dois lugares. A condição escrita à mão numa **aresta** não passa por esta regra (fluxos
existentes usam `route == 'billing'`). A regra mora em `ControlExpressionRules` (archflow-core) e em
`controlExpressions.ts` (designer); os dois leem os mesmos casos (`controlExpressionCases.json`).

O copiloto passa pela mesma regra: `addNode`/`configureNode` recusam a expressão com o motivo e os ids
válidos, e o agente corrige na rodada seguinte; se o "Gerar com IA" terminar com um fluxo que não executa, o
aviso diz o nó e o campo em vez de "fluxo montado". Para classificar, o copiloto monta o nó `decision` com o
provedor `llm` (o LLM da plataforma; `http-decisions`/`langchain4j-decisions` só com um model que o usuário
deu) e ramifica pela **resposta** com um `condition`/`switch` logo depois (acima); a aresta pela rota fica para
o portão de confiança, e o `AUTO` de um decision `llm` é recusado. "Adicione X antes de Y" usa `addNode` com
`insertBefore`.

## Cascata por confiança
`fallbacks` entra quando um provedor **falha**. `cascade` entra quando o provedor **respondeu, mas com
pouca confiança**: os degraus são tentados em ordem e a cascata para no primeiro com
`confidence ≥ acceptAt`. Se nenhum atinge, vale o **mais confiante** — e a rota (`REVIEW`/`ESCALATE`)
diz que não é seguro. A saída traz `cascade` com a trilha (`ACCEPTED`, `LOW_CONFIDENCE`, `FAILED`).
Uma cascata típica é `rules → modelo de decisão barato → modelo maior/LLM → humano`.

## Consenso entre modelos
`consensus.models` roda os mesmos pedidos em outros modelos, **em paralelo**. Se discordam do valor da
pergunta `primary` (a opção, o nível arredondado, o lado do sim/não), a rota é rebaixada para no mínimo
`onDisagree` (padrão `REVIEW`) e `routeReason` vira `consensus_disagreement`. A `decision` segue sendo a
do modelo principal; `consensus.votes` lista o voto de cada um, e um modelo que **falha** fica em
`consensus.failures` sem contar como discordância. O `usage` do resultado soma todos os votos.

## Consumo
Cada chamada feita — de um degrau da cascata, de um voto do consenso ou de um fallback — é reportada ao
`ContadorDeUso` da execução. O custo declarado pelo provedor (`usage.cost`, em USD) soma em
`uso.declaredCostUsd` do resultado ao Core, **separado** de `costCents` (reais, da tabela de preços):
este runtime não converte moeda. Ausente é "nenhum provedor informou", nunca zero.

## Na DSL (`archflow-dsl` 1.5.0+)

```java
Workflows.define("triagem-do-pedido")
    .step("intencao", Decision.node()
            .model("typesafe/jev-1.14")                         // provider padrão: http-decisions
            .keyRef("openrouter")                               // referência; a chave vem do tenant
            .choice("intencao", "O cliente está fechando um pedido?",
                    Map.of("PEDIDO", "Confirma itens e quantidades.",
                           "DUVIDA", "Pergunta sobre produto ou prazo."))
            .yesNo("urgente", "Pede resposta imediata?")
            .primary("intencao").gate("intencao")
            .thresholds(0.9, 0.5)
            .cascade("http-decisions", "upstage/solar-decide")
            .consensus("liquid/d1"))
    .step("fecha",  McpAgent.node().systemPrompt("..."))
    .step("humano", Nodes.approval())
    // O ramo vem da resposta (decision = a opção da primary); a rota só diz se ela é confiável.
    .edge("intencao", "fecha",  "${intencao.decision} == 'PEDIDO' && ${intencao.route} == 'AUTO'")
    .edge("intencao", "humano", "${intencao.decision} != 'PEDIDO' || ${intencao.route} != 'AUTO'")
    .build();
```

`edge(from, to, condição)` grava a condição na aresta. `route(from, to, ramo)` emite `branch`, que o **motor**
converte na condição ao carregar (`Decision.REVIEW` → `${intencao.route} == 'REVIEW'`): serve ao portão de
confiança, não a escolher a categoria.
`DslDecisionConformanceTest` (archflow-api) passa o documento emitido pelo desserializador real e executa o
nó — se a DSL e o componente divergirem num nome de chave, é lá que aparece.

## O nó como último passo: o que volta ao chamador

Quando o nó de decisão é o **último passo** de um fluxo acionado pelo VendaX (`AgentFlowRunner`), a saída
dele vai no `richObject` do result nesta forma (pedido de 14/10/2026, VEN-221 / ADR-107):

```json
{"decisao":   {"intencao": {"tipo": "choice", "valor": "PEDIDO", "confianca": 0.97, "rota": "AUTO",
                            "probabilidades": {"PEDIDO": 0.97, "DUVIDA": 0.03}},
               "urgente":  {"tipo": "noul", "valor": true, "confianca": 0.6, "rota": "REVIEW"}},
 "rota": "AUTO", "confianca": 0.97, "principal": "intencao",
 "modelo": "typesafe/jev-1.14-20261014", "provedor": "http-decisions",
 "uso": {"entrada": 312, "saida": 9, "custoUsd": 0.00002}}
```

- `rota`/`confianca` no topo são as do **nó** (a menor confiança do `gate`); `rota` por pergunta é a daquela
  pergunta sozinha. `urgente` acima é incerta e não rebaixa nada porque está fora do `gate` — as duas
  rotas existem para que isso seja visível.
- `modelo` é como o provedor respondeu, com a versão. `uso` é o **da chamada de decisão**, em USD, como o
  provedor declarou; o consumo da execução inteira (tokens, `costCents`, `costReais`, `declaredCostUsd`)
  continua em `VendaxResult.uso`, que já soma a decisão.
- O que o nó não informou fica **ausente** (nunca zero). `erro` só aparece quando a decisão não saiu — e
  aí `rota` é `ESCALATE` e `decisao` é vazio.
- O tipo do rich object vem do `saidaSchema` da definição (`decisao@1` → `decisao`), como para qualquer
  fluxo; sem schema, na rota em fluxo, o JSON vai como texto.

## Rota e limiares

`confidence ≥ auto` → `AUTO`; `≥ review` → `REVIEW`; abaixo → `ESCALATE`. Os padrões (0,9 / 0,5) são os
que a documentação do TypeSafe recomenda para risco comum; **operação destrutiva pede limiar mais
alto**, e quem decide é o fluxo.

### Falha nunca vira palpite
Provedor fora do ar, sem chave, resposta que não casa com o contrato, pergunta sem resposta: com
`onError: ESCALATE` (padrão) a saída traz `route: ESCALATE`, `confidence: 0`, `error` e nenhuma
resposta — o fluxo segue pela escalada em vez de seguir confiante. `FAIL` lança e o motor decide.

## Provedores

| id | o que é | chave | calibrado |
|---|---|---|---|
| `http-decisions` | qualquer serviço no protocolo `POST {model, state, questions} → {answers, usage}`: **Decisions API do OpenRouter** (padrão) e a API do próprio fabricante. O serviço é escolhido por `endpoint` e `model`. | sim (`keyRef`, padrão `openrouter`) | sim |
| `langchain4j-decisions` | qualquer `DecisionModel` da **langchain4j** (1.22+): `backend: typesafe` (TypeSafe, ou o que fala o mesmo protocolo — o OpenRouter é `baseUrl: https://openrouter.ai/api`) ou `backend: openai` (a **Decisions API da OpenAI**, `gpt-6-luna`). Traz a repetição, os listeners e o cliente HTTP da langchain4j. O custo em USD **não** vem (só os tokens). Com `baseUrl` do OpenRouter, leva a preferência de provedor (`provider: {zdr, data_collection}`) e respeita `required`, como o `http-decisions`. | sim (`keyRef`: `typesafe` ou `openai`, conforme o backend) | sim |
| `llm` | um LLM qualquer da plataforma como classificador (reserva, ou para começar sem modelo de decisão). Estado cercado como conteúdo não confiável. | a do próprio LLM (por tenant) | **não** |
| `rules` | regras determinísticas (texto ou `re:regex`) — o primeiro degrau da cascata | não | sim |

**`llm` não é calibrado:** um LLM que escreve "0,95" está estimando, não medindo. A confiança é
**limitada** a `maxConfidence` (padrão 0,89), então por padrão uma decisão por LLM nunca é `AUTO` — pede
revisão. O operador sobe o teto se quiser.

**Cascata:** `fallbacks` só entra quando o provedor **falha**, não quando responde com pouca confiança
(isso é a rota). Uma cascata clássica é `rules → modelo de decisão → llm → humano`.

Novos provedores entram por `ServiceLoader` (`META-INF/services/br.com.archflow.decision.DecisionProvider`)
ou `DecisionProviders.register(...)` — nenhum código do componente muda.

### Recusa
A API da OpenAI pode **recusar uma pergunta** e responder as outras. A recusa vira `type: "refused"` (valor
nulo, confiança 0): se a pergunta está no `gate`, a rota escala; fora do `gate`, não rebaixa nada. No
consenso, a recusa de um modelo conta como discordância. Nunca é preenchida com um palpite.

### Qual provedor usar
- **`http-decisions`** (padrão): sem dependência da langchain4j, devolve o **custo declarado** (`usage.cost`)
  e fala o caminho estável do OpenRouter (`/api/v1/systemone`; o `/api/alpha/decisions` responde o mesmo, mas é alfa).
- **`langchain4j-decisions`**: quando se quer a OpenAI direta (com recusa), a TypeSafe direta ou o que a
  langchain4j vier a suportar — sem escrever cliente.

### Modelos de decisão no OpenRouter
O catálogo vivo: `GET https://openrouter.ai/api/v1/models?output_modalities=decisions` (14 modelos na data
da escrita, entre eles `typesafe/jev-1.13`, `liquid/d1`, `perplexity/pplx-decider-v1-27b`,
`upstage/solar-decide`, `cloudflare/clef`, `inception/mercury-decide`). Todos respondem no mesmo
formato: trocar de modelo é trocar o `model` do nó. O contrato é coberto por teste contra servidor
simulado e, opcionalmente, contra o serviço real (abaixo).

## Chaves
`DecisionKeys` resolve a chave pela referência do provedor (`openrouter`): primeiro a **do tenant** (o
mesmo `TenantKeyResolver` das chaves de LLM, `archflow.llm.tenant-keys.<tenant>.<ref>`), depois
`archflow.decision.keys.<ref>` (env `ARCHFLOW_DECISION_KEYS_OPENROUTER`) e, por fim, a chave de LLM da
plataforma (`archflow.llm.keys.<ref>`, ou `archflow.llm.api-key` quando `<ref>` é o
`archflow.llm.provider`). Nunca do documento do fluxo.

O destino também não é livre: com a chave em mãos, um `options.endpoint` (`http-decisions`) ou
`options.baseUrl` (`langchain4j-decisions`) só é aceito se for o endereço oficial do fornecedor da
chave, o `archflow.llm.base-url` ou um item de `archflow.llm.allowed-base-urls`. Fora disso a tentativa
falha com `[LLM-HOST]` (não repetível — a rota escala ou o `fallbacks` é tentado) e nada sai.

"Fornecedor da chave" é o `keyRef`. Quando ele é um provider de LLM (`openrouter`, `openai`), vale o
endereço oficial dele — a chave da OpenAI não vai para o OpenRouter. Quando não é (o `typesafe`, padrão
do `langchain4j-decisions`, ou uma referência do operador), valem também `https://api.typesafe.ai` e o
OpenRouter (`https`), que é a configuração documentada acima: `backend: typesafe` com
`baseUrl: https://openrouter.ai/api` e a chave em `ARCHFLOW_DECISION_KEYS_TYPESAFE` continua funcionando.

O modelo também segue o cadastro do tenant: quando o tenant da execução tem `allowedModels` preenchido,
o `model` de cada tentativa do `http-decisions`/`langchain4j-decisions` (inclusive os degraus de
`cascade` e os votos de `consensus`) precisa estar na lista, ou a tentativa falha com `[LLM-MODELO]`
(não repetível) antes de qualquer requisição. Lista vazia ou "nenhum tenant" não restringem. O provedor
`llm` passa pelo resolver de LLM, que aplica a mesma regra.

## Testes contra o serviço real (opcional)
```bash
OPENROUTER_API_KEY=... mvn test -pl archflow-decision,archflow-api -am \
  -Dtest='HttpDecisionProviderTest,LangChain4jDecisionProviderTest,DecisaoNoFluxoTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Sem a variável, esses casos são pulados. A chave nunca mora no repositório.

## Designer
O nó `decision` está na paleta (categoria controle) com painel de propriedades: provedor, modelo, perguntas
(JSON validado — só grava quando parseia), estado, `primary`/`gate`, limiares, `onError`, cascata,
reserva e consenso. Ao ligar uma aresta a partir de um `condition` ou `switch`, ela já recebe o
**primeiro ramo livre**. A partir de um `decision` não: a aresta nasce **incondicional** (segue qualquer
que seja a rota) até que uma rota (`AUTO`/`REVIEW`/`ESCALATE`) seja escolhida no seletor da aresta — dar
`AUTO` sozinho condicionava o próximo passo sem ninguém ter pedido. Ao selecionar a aresta dá para
trocar o ramo ou escrever uma condição à mão.

**Correção que veio junto:** o editor descartava a `condition` das arestas ao carregar e ao salvar —
um fluxo aberto e salvo no designer perdia toda condição e passava a seguir todos os ramos. Agora
`condition` e `branch` fazem a ida e a volta (`workflowSerde.ts`, com teste).

## Limites conhecidos
- Os nós `parallel`, `loop`, `merge`, `map`, `filter` e `reduce` do designer ainda não têm execução no
  runtime (só `condition`, `switch` e `decision` foram tratados).
- No modo standalone (`archflow-standalone`) e no SDK embarcado o documento passa pela mesma fábrica do
  servidor (`archflow-flow-runtime`, RCHFLW-37): `condition`, `switch` e `decision` executam lá também. Os
  provedores `llm` e `langchain4j-decisions` são do servidor e não existem fora dele — ali só os embutidos
  (`http-decisions`, `rules`) e os registrados por SPI; a chave vem de `ARCHFLOW_DECISION_KEYS_<REF>`. Um nó
  que cite um provedor ausente ali (em `provider`, `fallbacks`, `cascade` ou `consensus.models`) recusa o fluxo
  antes do primeiro passo — sem isso o `onError: ESCALATE` padrão faria o fluxo terminar COMPLETED pelo ramo
  de escalada. No servidor nada muda: o provedor desconhecido continua virando ESCALATE com `error`.
