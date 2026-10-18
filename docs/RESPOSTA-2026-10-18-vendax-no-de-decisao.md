# ArchFlow — resposta ao pedido do nó de decisão (VEN-221)

Resposta ao pedido do VendaX Core de 14/10/2026 (`PEDIDO-2026-10-14-archflow-no-de-decisao.md`), nos
quatro pontos. Feito no branch `feat/vendax-custo-reais-e-no-de-decisao`, no motor e na DSL — sem `case` novo.

## 1. Versão publicada com o `archflow-decision`

**Publicada: `archflow-dsl` 1.5.0 está no Maven Central** (também `archflow-model` e `archflow-sdk-java` 1.5.0;
`maven-metadata` já aponta `latest=1.5.0`). Tag `v1.5.0` cortada em 18/10/2026 sobre a main com os PRs #83
(esta entrega), #84 e #85. É a primeira versão com o módulo `archflow-decision` e o nó `decision` no runtime.

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-dsl</artifactId>
    <version>1.5.0</version>
</dependency>
```

`archflow-decision` em si não vai ao Central — é do runtime; o que o Core consome dele é o contrato que a DSL
escreve. Requer Java 25, como as versões anteriores.

Nota de bastidor: a primeira execução do release falhou na validação do Central ("Bundle has content that does NOT
have a .pom file"). Não era POM nosso: a imagem do runner do GitHub de 12/10 trouxe Maven 3.10, que deixa arquivos
de repositório local dentro do bundle. Corrigido no PR #84 fixando Maven 3.9 no job; nada havia sido publicado.

**Qual versão roda na VPS (VEN-50).** A imagem no ar é `sha-838447c` (main com os POMs em 1.5.0), saudável desde
15:38 UTC de 18/10 — confirmado pelo watcher da VPS. Passou a existir `GET /actuator/info`, que responde:

```json
{"build": {"artifact": "archflow-api", "name": "archflow-api", "version": "1.5.0", "time": "2026-10-18T…"},
 "image": {"revision": "<sha do commit de que a imagem foi construída>"}}
```

`build.*` vem do `build-info` do Maven; `image.revision` é o `github.sha` que `build-and-push.yml` passa ao
Dockerfile (`ARG GIT_REVISION` → `ARCHFLOW_BUILD_REVISION`).

**Pela internet o caminho dá 404, e isso é do proxy, não do app.** O Caddy da VPS só repassa `/actuator/health*`
(a página `/actuator` do app lista `info`, `metrics` e `prometheus` como expostos; o 404 vem vazio e sem
`content-type`, assinatura do Caddy, não do Spring). É um bloqueio deliberado — métricas não devem ser públicas.
Duas saídas, à escolha de quem opera a VPS (`/opt/archflow-devops`): liberar só `/actuator/info` no Caddyfile
(expõe versão e commit, nada mais), ou consultar de dentro da VPS: `curl -s localhost:16080/actuator/info`. Até
lá, a referência pública é a tag `ARCHFLOW_IMAGE_TAG` em `/opt/archflow-devops/.env`.

## 2. O construtor na DSL

```java
Workflows.define("triagem-do-pedido")
    .step("intencao", Decision.node()
            .model("typesafe/jev-1.14")                 // provider padrão: http-decisions (OpenRouter)
            .keyRef("openrouter")                       // referência; a chave vem do tenant (ponto 4)
            .choice("intencao", "O cliente está fechando um pedido?",
                    Map.of("PEDIDO", "Confirma itens e quantidades.",
                           "DUVIDA", "Pergunta sobre produto ou prazo."))
            .yesNo("urgente", "Pede resposta imediata?")
            .primary("intencao").gate("intencao")
            .thresholds(0.9, 0.5)
            .escalateOnError()
            .cascade("http-decisions", "upstage/solar-decide")
            .consensus("liquid/d1"))
    .step("fecha",  McpAgent.node().systemPrompt("…"))
    .step("humano", Nodes.approval())
    .route("intencao", "fecha",  Decision.AUTO)
    .route("intencao", "humano", Decision.REVIEW)
    .route("intencao", "humano", Decision.ESCALATE)
    .build();
```

| pedido | método |
|---|---|
| `provider`, `model` | `provider(..)`, `model(..)` |
| `questions` (`choice`, `score`, `noul`) | `choice(id, instrução, opções)`, `score(id, instrução, níveis…)`, `yesNo(id, instrução[, seSim, seNão])` — na ordem declarada; a primeira é a `primary` por padrão |
| `state` | `state(Map)` |
| `thresholds`, `primary`, `gate` | `thresholds(auto, review)`, `primary(..)`, `gate(..)` |
| `onError` | `escalateOnError()` (padrão) / `failOnError()` |
| `cascade`, `consensus` | `cascade(provider, model)`, `acceptAt(..)`, `consensus(modelos…)`, `consensusVote(provider, model)`, `consensusEscalatesOnDisagree()`; `fallback(provider, model)` para quando o provedor **falha** |
| arestas por rota | `WorkflowBuilder.route(from, to, Decision.AUTO|REVIEW|ESCALATE)` — sai como `branch`; o motor monta `${intencao.route} == 'AUTO'` ao carregar |
| escape | `providerOption(k, v)` (provedor principal), `providerOptions(provider, Map)`, `with(Map)`; `Nodes.decision()` é o nó cru |

Garantias: sem pergunta, `spec()` recusa com `DslValidationException`; `apiKey` não tem método — a chave nunca
entra no documento. `DslDecisionConformanceTest` (archflow-api) passa o documento emitido pelo desserializador
**real** e executa o nó (provedor de regras); se a DSL e o `DecisionComponent` divergirem num nome de chave, é
lá que aparece. O `WorkflowCodeGenerator` devolve `.route(...)` para uma conexão com `branch`.

Também: o transporte do `archflow-standalone` passou a resolver `branch` em condição (pela mesma regra,
`BranchCondition` no `archflow-model`) — antes ignorava a chave e o fluxo seguia todos os ramos.

## 3. O que volta ao Core

Quando o nó de decisão é o **último passo** do fluxo, o `richObject` do result é:

```json
{"decisao":   {"intencao": {"tipo": "choice", "valor": "PEDIDO", "confianca": 0.97, "rota": "AUTO",
                            "probabilidades": {"PEDIDO": 0.97, "DUVIDA": 0.03}},
               "urgente":  {"tipo": "noul", "valor": true, "confianca": 0.6, "rota": "REVIEW"}},
 "rota": "AUTO", "confianca": 0.97, "principal": "intencao",
 "modelo": "typesafe/jev-1.14-20261014", "provedor": "http-decisions",
 "uso": {"entrada": 312, "saida": 9, "custoUsd": 0.00002}}
```

- **Por pergunta**: `valor`, `confianca` e `rota` — a rota *daquela* pergunta pelos limiares do nó. **No
  topo**: `rota`/`confianca` do nó (a menor confiança do `gate`). No exemplo, `urgente` é incerta e não
  rebaixa nada porque está fora do `gate`; as duas rotas existem para isso ser visível.
- **Por chamada**: `modelo` como o provedor respondeu (com a versão, `typesafe/jev-1.14-20261014`) e `uso` da
  chamada de decisão, em USD, como o provedor declarou. O consumo da **execução inteira** continua em
  `VendaxResult.uso` (`costCents`, `costReais`, `declaredCostUsd`…), que já soma a decisão — o nó reporta ao
  `ContadorDeUso`.
- O que o nó não informou fica **ausente** (nunca zero). `valor` nulo é explícito (recusa da OpenAI), para
  não se confundir com pergunta inexistente. `erro` só aparece quando a decisão não saiu — aí `rota` é
  `ESCALATE` e `decisao` é vazio: falha não vira palpite.
- O `richObjectType` vem do `saidaSchema` da definição (`decisao@1` → `decisao`), como para todo fluxo.
  Antes desta mudança, um fluxo que terminasse num `decision` dava `ERROR` "o fluxo não devolveu um JSON".

Se o nó **não** é o último passo (a decisão alimenta um agente que redige), a saída do fluxo segue sendo a
do agente; a decisão fica no contexto (`${intencao.decision}`) para o prompt dele.

## 4. A chave

Confirmado: `keyRef` (padrão `openrouter`) é resolvido pelo `DecisionKeys` do hospedeiro — primeiro pelo
**`TenantKeyResolver` do tenant**, o mesmo das chaves de LLM, com `openrouter` como id de provedor; na falta,
`archflow.decision.keys.openrouter` (env `ARCHFLOW_DECISION_KEYS_OPENROUTER`). Nunca do documento. Como o
LLM dos agentes do VendaX já roda pelo OpenRouter com a chave do tenant, a mesma chave serve às decisões —
nada a enviar pelo Core. Sem chave resolvida, o nó não chama: escala com `erro: "sem chave de API…"`.

## Medido aqui

Os testes contra o serviço real (`OPENROUTER_API_KEY=… mvn test -pl archflow-decision,archflow-api
-Dtest='HttpDecisionProviderTest,DecisaoNoFluxoTest'`) continuam opcionais e são pulados sem a variável. O
que esta entrega mediu foi a conformidade (DSL → desserializador real → execução) e a forma do envelope; o
`jev-1.14` 36/36 em 14/10 é a medição de vocês.
