# Resposta ao VendaX — retirada do `case "NS"` e do `case "US"`

**Data:** 15/11/2026 · **Pedido:** `PROMPT-2026-11-15-archflow-retirar-case-ns-us.md` (VEN-1134, da VEN-1070) ·
**Base:** `origin/main` `ad66d14` · **Entrega:** branch `chore/retirar-case-ns-us`

## O que saiu

- **No `VendaxAgentDispatcher`:**
  - o `case "NS"` e o `case "US"`;
  - os métodos `runNsConsulta` e `runUsSugestao`;
  - as constantes `NS_CONSULTA`, `NS_TOOLS`, `NS_MAX_ITERACOES` e `NS_NAO_CONTRATADO`.
- **As classes** `ConsultaAoNegociador` e `SugestaoDeUpsell`, com os testes delas (`ConsultaAoNegociadorTest` e
  `SugestaoDeUpsellTest`).
- **Testes que só usavam o NS para chegar a outra coisa:**
  - os do prazo interativo, em `UsoCompletoTest`, passaram a usar o roteiro do CPA, o caminho interativo que resta
    no `switch`;
  - o caso NS de `MemoriaDoClienteNoInvokeTest` saiu, e o QP, o CS e o fluxo continuam cobertos.

Um invoke `NS` ou `US` **sem** `definicao` de fluxo agora cai no `naoImplementado`. O resultado é
`ERROR "Agente NS ainda não é executado pelo ArchFlow"` (ou `US`), e o laço antigo não roda.

## O que o caminho genérico já cobre

Conferi no código; nada foi reimplementado.

| garantia | onde |
|---|---|
| `SEM_SUGESTAO` em texto chega no `outputText` do `ERROR` | `ContratoVendaXMotorControladoTest` |
| `encerrarEmCodigos [-32001]` encerra o laço e sobe em `encerradoPor` | `McpAgentComponent` + `VendaxResult.comChamadas(…, encerradoPor)` no `runFluxo` |
| `toolCalls` sobem no resultado, mesmo vazias | `ToolCallsNoFluxoTest` |
| prazo interativo vindo do `validoAte` do invoke | `PrazoNoFluxoTest` |
| `<span>` vazio fora de string é normalizado (VEN-1132) | `JsonDeSaidaTest` |

## Testes

`RetiradaDoNsEUsTest` (novo) é o par de controle pedido:

- `NS ns:consulta` e `US us:sugestao` **sem** definição vão para o `naoImplementado`, e o runner não é chamado;
- os mesmos dois **com** definição de fluxo seguem pelo caminho genérico e voltam `OK`.

A suíte do `archflow-api` está verde: **936 testes, 0 falhas, 3 pulados**. Os três pulados são os testes ao vivo
(`OpenRouterUsageMetadataLiveTest` e dois de provedor de decisão), que precisam de chave. A contagem caiu em
relação aos 967 anteriores porque os testes das duas classes retiradas saíram.

## Commit e imagem

- **Commit:** `<preencher após o merge>`
- **Imagem:** `ghcr.io/integrall-tech/archflow:sha-<7 primeiros do merge>`

A VPS puxa a imagem nova em cerca de 2 minutos depois do merge na `main`. Com ela no ar, o Core pode rodar uma
consulta do NS e uma sugestão do US no ambiente.
