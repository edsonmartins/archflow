# ArchFlow — resposta ao pedido do custo sem arredondar (VEN-245)

Resposta ao pedido do VendaX Core de 14/10/2026 (`PEDIDO-2026-10-14-archflow-custo-sem-arredondar.md`).
Feito como pedido, no branch `feat/vendax-custo-reais-e-no-de-decisao`: um campo aditivo, `costCents` intocado.

## O que o Core passa a receber

Em todo `uso` — no `VendaxResult` (OK e ERROR) e no relato de `/api/v1/public/archflow/uso`:

```json
"uso": {
  "model": "openrouter/google/gemini-2.5-flash-lite",
  "tokens": 5000, "inputTokens": 4600, "outputTokens": 400,
  "costCents": 1,
  "costReais": 0.0031,
  "execucaoId": "…", "provider": "openrouter", "llmTurns": 1, "toolCalls": 1, "durationMs": 812
}
```

| campo | o que é | quando falta |
|---|---|---|
| `costCents` | centavos de real, **arredondado para cima** — como antes; é o que o teto lê | nulo explícito: sem preço declarado para o modelo (nunca "de graça") |
| `costReais` | **o mesmo custo**, em reais, **sem arredondar** | ausente (`NON_NULL`) exatamente nos mesmos casos em que `costCents` é nulo |

Os dois saem da mesma conta (`TabelaDePrecos`, tokens × preço declarado por milhão); só a última operação
difere — `CEILING` para centavos, divisão exata para reais. Consumo zero dá `0.0`, que aí é um fato.

Com os preços do exemplo acima (50 e 200 centavos por milhão): 4600 × 50 + 400 × 200 = 310.000 → 0,31 centavo →
`costCents: 1`, `costReais: 0.0031`. É a diferença que faz as 213 chamadas do CS deixarem de ser uma série de 0 e 1.

## O que não mudou

- `costCents` continua arredondado para cima: um teto que arredondasse para baixo deixaria mil consultas
  baratas custarem zero.
- `declaredCostUsd` (custo declarado pelos provedores de decisão, em USD) continua separado: este runtime não
  converte moeda. `msAtePrimeiroToken` idem.
- Os relatos sem result (`SEM_RESULT`, `SUSPENSO`, `APOS_PRAZO`) levam `costReais` do mesmo jeito, pelo mesmo
  caminho (`usoDe` no dispatcher é o único ponto que monta o `Uso`).

## Onde está

| peça | mudança |
|---|---|
| `TabelaDePrecos.custoEmReais(Resumo)` | a conta sem arredondar; `null` nos mesmos casos de `custoEmCentavos` |
| `VendaxResult.Uso.costReais` | campo aditivo `Double`, `NON_NULL`; `Uso.de(resumo, centavos, reais)` |
| `VendaxAgentDispatcher.usoDe` | passa os dois |
| testes | `TabelaDePrecosTest` (fração não arredonda; nulo como os centavos), `UsoCompletoTest`, `RelatoDeUsoTest` (forma do envelope, ausente sem preço) |

Do lado do Core, `core.llm_usage.custo_reais` (V256) já recebe o campo, segundo o pedido.
