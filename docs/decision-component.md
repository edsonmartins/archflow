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
    fallbacks:                          # tentados em ordem quando o provedor anterior FALHA
      - provider: llm
        model: openai/gpt-4o-mini
    providerOptions:
      http-decisions: { endpoint: "https://openrouter.ai/api/alpha/decisions", retries: 1 }
```

`apiKey` no nó é **ignorada** (com aviso): a chave vem do resolvedor do tenant.

## Saída e arestas

O nó grava no contexto, sob o seu `id`:

| campo | |
|---|---|
| `route` | `AUTO`, `REVIEW` ou `ESCALATE` |
| `confidence` | a **menor** confiança entre as perguntas do `gate` (uma incerta rebaixa a decisão toda) |
| `decision` | o valor da pergunta `primary` |
| `answers.<id>` | `type`, `value`, `confidence`, `probabilities`, `choice`/`score`/`probability` |
| `provider`, `model`, `calibrated`, `usage` | quem respondeu, e o consumo (`costUsd` quando informado) |
| `error` | só quando a decisão não saiu |

```json
{ "sourceId": "triagem", "targetId": "resolver",  "condition": "${triagem.route} == 'AUTO' && ${triagem.decision} == 'pagamentos'" },
{ "sourceId": "triagem", "targetId": "confirmar", "condition": "${triagem.route} == 'REVIEW'" },
{ "sourceId": "triagem", "targetId": "humano",    "condition": "${triagem.route} == 'ESCALATE'" }
```

`REVIEW` e `ESCALATE` ligam naturalmente a um nó `APPROVAL` (decisão humana, que já é durável).

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
| `llm` | um LLM qualquer da plataforma como classificador (reserva, ou para começar sem modelo de decisão). Estado cercado como conteúdo não confiável. | a do próprio LLM (por tenant) | **não** |
| `rules` | regras determinísticas (texto ou `re:regex`) — o primeiro degrau da cascata | não | sim |

**`llm` não é calibrado:** um LLM que escreve "0,95" está estimando, não medindo. A confiança é
**limitada** a `maxConfidence` (padrão 0,89), então por padrão uma decisão por LLM nunca é `AUTO` — pede
revisão. O operador sobe o teto se quiser.

**Cascata:** `fallbacks` só entra quando o provedor **falha**, não quando responde com pouca confiança
(isso é a rota). Uma cascata clássica é `rules → modelo de decisão → llm → humano`.

Novos provedores entram por `ServiceLoader` (`META-INF/services/br.com.archflow.decision.DecisionProvider`)
ou `DecisionProviders.register(...)` — nenhum código do componente muda.

### Modelos de decisão no OpenRouter
O catálogo vivo: `GET https://openrouter.ai/api/v1/models?output_modalities=decisions` (14 modelos na data
da escrita, entre eles `typesafe/jev-1.13`, `liquid/d1`, `perplexity/pplx-decider-v1-27b`,
`upstage/solar-decide`, `cloudflare/clef`, `inception/mercury-decide`). Todos respondem no mesmo
formato: trocar de modelo é trocar o `model` do nó. O protocolo é **alfa** no OpenRouter: o contrato é
coberto por teste contra servidor simulado e, opcionalmente, contra o serviço real (abaixo).

## Chaves
`DecisionKeys` resolve a chave pela referência do provedor (`openrouter`): primeiro a **do tenant** (o
mesmo `TenantKeyResolver` das chaves de LLM) e, na falta dela, `archflow.decision.keys.<ref>` (env
`ARCHFLOW_DECISION_KEYS_OPENROUTER`). Nunca do documento do fluxo.

## Testes contra o serviço real (opcional)
```bash
OPENROUTER_API_KEY=... mvn test -pl archflow-decision,archflow-api -am \
  -Dtest='HttpDecisionProviderTest,DecisaoNoFluxoTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Sem a variável, esses casos são pulados. A chave nunca mora no repositório.

## Limites conhecidos
- O consumo (`usage.costUsd`) sai no resultado do nó, mas **não** alimenta o `ContadorDeUso` do
  agente — um teto de custo por tenant ainda não enxerga decisões.
- Os nós `condition`/`switch` do designer continuam sem efeito no runtime (ver issue de follow-up); o
  caminho que funciona é a condição na aresta, como acima.
- O designer ainda não tem um nó `decision`.
