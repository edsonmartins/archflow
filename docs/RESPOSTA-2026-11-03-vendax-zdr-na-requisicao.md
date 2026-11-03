# Resposta ao VendaX — preferência de provedor (ZDR) na requisição

**Data:** 03/11/2026 · **Pedido:** "preferência de provedor ZDR em toda chamada ao OpenRouter" (VendaX Core,
VEN-638 frente D, base `59c036d`) · **Entrega:** PR #88, versão **1.5.1**

## Resumo

Os caminhos 1, 2 e 3 passam a mandar, em toda chamada ao OpenRouter:

```json
"provider": {"zdr": true, "data_collection": "deny"}
```

A preferência vem do deployment e nenhum nó de fluxo a desliga, como acontece com a chave de API. Com
`required=true`, um modelo OpenRouter sem a preferência **não é criado**. A 1.5.1 está no ar na VPS e a
preferência já está ligada lá (ver "Estado na VPS").

## Configuração

| propriedade | variável de ambiente | valores |
|---|---|---|
| `archflow.llm.provider-preferences.zdr` | `ARCHFLOW_LLM_PROVIDER_PREFERENCES_ZDR` | `true` / `false` / vazio |
| `archflow.llm.provider-preferences.data-collection` | `ARCHFLOW_LLM_PROVIDER_PREFERENCES_DATA_COLLECTION` | `deny` / `allow` / vazio |
| `archflow.llm.provider-preferences.required` | `ARCHFLOW_LLM_PROVIDER_PREFERENCES_REQUIRED` | `true` / `false` (padrão) |

- **Vazio, nada muda:** o corpo sai byte a byte o de hoje (há teste de controle para isso).
- **Valor mal escrito impede a subida.** Um `dney`, por exemplo, não chega a ser enviado e ignorado.
- **Só os campos declarados vão.** Respeita o `additionalProperties: false` do `ProviderPreferences`.
- **Sem campo novo na DSL e sem sobreposição por tenant ou fluxo.** O pedido dizia que o padrão do
  deployment bastava, e assim nenhum fluxo consegue desligar a garantia.

## Os caminhos

| # | caminho | agora |
|---|---|---|
| 1 | nó `agent`, bloqueante (`createOpenAiCompatibleModel`) | `provider` vai no `customParameters`, **no mesmo mapa** do `reasoning` |
| 2 | nó `agent`, em fluxo (`openAiStreaming`) | idem; antes não levava nem o `reasoning`, e agora leva os dois |
| 3 | nó `decision` (`http-decisions`, `/systemone`) | `provider` no corpo quando o host é `openrouter.ai`, o que vale também para `/api/alpha/decisions` |
| — | provedores que não são OpenRouter (OpenAI direto, DeepSeek, Gemini…) | **não** recebem o campo |

O caminho 1 tinha exatamente a armadilha apontada no pedido. O `customParameters` substitui o mapa inteiro, então
`reasoning` e `provider` agora saem de uma função só (`LLMProviderHub.parametrosDoCorpo`). Os dois convivem no
corpo, e há teste para isso nos dois caminhos.

O provedor `llm` do nó `decision` e o `/archflow/assist/*` passam pelo mesmo `LLMConfigResolver`, então também
levam a preferência.

### O que `required=true` faz em cada caminho

- **Agente (1 e 2):** a criação do modelo lança `IllegalStateException` com `[LLM-ZDR]` na mensagem. A execução
  termina em ERROR e **nenhuma** requisição sai.
- **Decisão (3):** `DecisionException` não repetível, antes de enviar. Com `onError: ESCALATE` (o padrão), o nó
  devolve rota ESCALATE com `error`. Ou seja, a decisão escala em vez de ir sem a garantia.
- **Provedor que não é OpenRouter:** o `required` não tem efeito.

## Caminhos 4–6

Nenhum dos três leva a preferência, e não os alteramos:

- **4 — `langchain4j-decisions`:** só é usado quando o nó declara `provider: langchain4j-decisions`. Os nós
  `bo-decisao`, `cs-decisao`, `vc-resposta` e `vi-decisao` não declaram provider, então vão pelo `http-decisions`
  (caminho 3).
- **5 — `OpenRouterChatAdapter`:** **está no classpath do servidor.** Ele é alcançado por nós dos tipos
  `llm-chat`, `llm-streaming` ou `chat` com provider `openrouter`. Os nós `agent` não passam por ele. Não temos
  acesso a `resources/fluxos/`, então **pedimos ao Core que confira se algum fluxo usa esses tipos.** Se algum
  usar, esse caminho precisa do mesmo tratamento, e fazemos.
- **6 — embeddings de conhecimento:** só seria usado com `archflow.knowledge.embedding.provider=openai` e
  `base-url` apontando para o OpenRouter. A VPS não tem essa configuração.

## Testes

Os testes leem **o corpo cru recebido por um servidor local**, no mesmo molde do `HttpDecisionProviderTest`:

- **`PreferenciaDeProvedorNoCorpoTest` (provider-hub), com o servidor respondendo JSON e SSE do protocolo
  OpenAI:**
  - caminhos 1 e 2 com a preferência ligada: o corpo traz `provider`;
  - com `reasoning` declarado, os dois campos convivem;
  - `required=true` sem preferência: a criação falha e o servidor recebe **zero** requisições;
  - OpenAI direto e DeepSeek com a preferência ligada: sem `provider`;
  - controle: preferência desligada, corpo **byte a byte igual** ao de um `OpenAiChatModel` /
    `OpenAiStreamingChatModel` construído sem `customParameters`.
- **Defeito reintroduzido:** tirando o `provider` do streaming, `emFluxoLeva` e `emFluxoComReasoning` ficam
  vermelhos. Verificado antes do merge.
- **`HttpDecisionProviderTest`:**
  - o corpo do `/systemone` traz `provider` com só os dois campos;
  - endpoint que não é do OpenRouter não recebe o campo;
  - `required` falha sem nenhuma chamada;
  - controle byte a byte;
  - o predicado de host recusa `openrouter.ai.evil.example`.
- **`PreferenciaDeProvedorBeanTest`:**
  - as três variáveis de ambiente chegam às propriedades sem esbarrar em `archflow.llm.provider` nem em
    `archflow.llm.precos` (o incidente de setembro);
  - o bean substitui o `http-decisions` embutido.
- **Suítes completas:** model 235, provider-hub 162, decision 51, archflow-api 884. Zero falhas e todos os pisos
  de cobertura atendidos; o CI da main passou.

## Versão

**1.5.1**, tag `v1.5.1` de 03/11/2026. Inclui também a imagem na entrada do agente (VEN-516, PR #87).
`archflow-model`, `archflow-dsl` e `archflow-sdk-java` 1.5.1 estão no Maven Central.

A mudança está no **servidor** (`archflow-api`, `archflow-decision` e provider-hub, que não vão ao Central). Para
o Core, subir o `archflow.version` só atualiza a DSL e o SDK; o ZDR vale pelo servidor e pelas variáveis, e o Core
não precisa de mudança para ele.

## Estado na VPS

- **Imagem:** `sha-5f5b67f`, `build.version` 1.5.1, saudável. Confirmado pelo watcher e por
  `/actuator/info` de dentro do container.
- **Preferência ligada às 19:24 UTC de 03/11:** `ZDR=true` e `DATA_COLLECTION=deny` no `.env` e repassadas pelo
  `docker-compose.yml`. O container subiu saudável em 25 s e o log não teve erro.
- **`REQUIRED` ainda está `false`.** Vamos ligá-lo depois de ver uma chamada real de agente passar com a
  preferência. Ele não muda o que é enviado; só faz o archflow falhar se a preferência for removida.

## O que pedimos ao Core

1. **Conferir uma chamada real de cada tipo** (agente bloqueante, em fluxo e decisão), como o pedido previa.
   - **Resposta da System One:** deve trazer `provider` (o `DecisionsResponse` tem esse campo).
   - **Erro do OpenRouter numa chamada de agente:** significa que nenhum endpoint de retenção zero atende o modelo
     do nó. Nesse caso é preciso trocar de modelo, porque desligar o ZDR derrotaria o propósito. Vale olhar
     primeiro o `google/gemini-2.5-flash-lite` e o `typesafe/jev-1.14`.
   - **Avisar quando a conferência passar,** para ligarmos o `REQUIRED`.
2. **Procurar nos fluxos de `resources/fluxos/` os tipos `llm-chat`, `llm-streaming` e `chat`** (caminho 5).
3. **Rever o ADR-027 v2 (D-4).** A frase "já roda por provedores com retenção zero" agora tem garantia na
   requisição, não só na conta.
