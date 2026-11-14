# Resposta ao VendaX — encerrar na saída da tool e custo do turno sem `usage.cost`

**Data:** 14/11/2026 · **Pedido:** `PEDIDO-2026-11-13-archflow-encerrar-na-saida-da-tool.md` (VEN-1044), pedidos 1 e 2
· **Base:** `origin/main` `258ddbf` · **Entrega:** branch `feat/encerrar-na-saida`

## Pedido 1 — `encerrarNaSaida`

Chave nova no nó `mcp-agent`, desligada por padrão:

```json
{ "saidaDaTool": ["responder_diretoria"], "encerrarNaSaida": true }
```

Na DSL: `Nodes.mcpAgent().outputFromTool("responder_diretoria").stopOnOutputTool()`, ou a forma crua
`.with("encerrarNaSaida", true)`.

- **A primeira chamada bem-sucedida** a uma das tools de `saidaDaTool` encerra o laço. A saída do passo é o
  resultado dela, pela mesma regra de hoje (`textoDaSaida`).
- **Erro não encerra.** Isso vale tanto para `isError` no resultado quanto para código de protocolo
  (`-32602`): o modelo recebe o motivo e corrige na volta seguinte.
- **O laço termina como conclusão, não como `encerrado`.** A saída do passo **não** traz `encerradoPor`,
  que continua significando "uma tool encerrou com código de erro, o texto não é resposta".
- **Tools pedidas no mesmo turno depois da resposta aceita não rodam.** Exemplo: `[responder_diretoria,
  explicar_variacao]` executa só a primeira.
- **Sem `saidaDaTool`, o nó é recusado na carga.** Um flag que não encerraria nada daria a impressão de
  proteção sem que ela existisse.
- **Vale também depois de uma suspensão.** A chave vai no estado durável da retomada, então um laço que
  suspende para aprovação humana e volta continua parando na saída.
- **Independe de `exigirSaidaDaTool`.** Os dois podem ser usados juntos ou separados.

O QP (`montar_rascunho_cotacao`) pode usar a mesma chave.

## Pedido 2 — o custo do turno que o OpenRouter não mandou

Os três itens foram implementados.

### 1. Busca do custo que faltou

Quando um turno do OpenRouter volta sem `usage.cost`, o laço consulta `GET {baseUrl}/generation?id=<id>` e
lê `data.total_cost`.

- **A consulta não segura o laço.** Ela começa no turno e corre junto com as tools e os turnos seguintes.
  No fim, o laço espera as que ainda estão em voo, até um prazo. Na prática, só a do último turno costuma
  ainda estar pendente.
- **Repete em 404 dentro do prazo,** porque a estatística da geração demora um pouco a existir depois da
  resposta. Também repete em 429 e 5xx. Não repete em 401 ou 403.
- **Usa a mesma chave do turno:** a do tenant, pelo mesmo resolvedor do modelo.
- **A consulta só leva o id.** Nada do prompt nem da resposta sai.
- **Se o custo não chegar no prazo, o turno segue desconhecido, como hoje.** Nunca há soma parcial como
  total.

| propriedade | padrão | efeito |
|---|---|---|
| `archflow.llm.custo-da-geracao.prazo` (`ARCHFLOW_LLM_CUSTODAGERACAO_PRAZO`) | `PT2S` | tempo máximo da consulta e da espera no fim do laço. `PT0S` desliga |

### 2. Quais turnos ficaram sem custo

O `uso` ganha dois campos aditivos:

```json
"uso": {
  "declaredCostUsd": null,
  "turnsWithoutCost": 1,
  "modelUsage": [
    { "declaredCostUsd": 0.0011, "generationId": "gen-…a" },
    { "declaredCostUsd": null,   "generationId": "gen-…b" }
  ]
}
```

- **`turnsWithoutCost`** é a contagem de turnos sem custo declarado. Vem sempre que há `uso`, inclusive
  `0`.
- **`turnsWithoutCost: 0` com o total nulo** significa que uma tentativa falhou antes de informar uso
  (repetição de transporte ou exceção do provedor). Nesse caso não há turno onde contar.
- **`modelUsage[].generationId`** é a chave para conciliar o custo depois. Fica ausente quando o provedor
  não a informa.

### 3. Log

Em INFO, para todo turno do OpenRouter sem `usage.cost`:

```
Turno OpenRouter sem usage.cost (geração=gen-…, via=corpo|sse); consultando o custo pela geração
```

O resultado da consulta também sai em INFO ("obtido" ou "não obtido em N ms"). O campo `via` responde se o
buraco vem de um caminho só.

## Testes

- `EncerrarNaSaidaTest`: no runner (sem a opção o laço segue, a primeira resposta aceita encerra,
  `isError` e `-32602` não encerram, o resto do turno não roda) e no componente (repasse ao runner e à
  retomada, desligado por padrão, recusa sem `saidaDaTool`).
- `CustoQueFaltouTest`:
  - id e caminho de origem, por corpo e por SSE;
  - o custo tardio entra no total;
  - consulta falha ou estoura o prazo sem lançar;
  - o laço consulta só o turno sem custo, com a chave do tenant;
  - consulta HTTP contra um servidor local: 404 → 200, 401 sem repetir, prazo, id codificado na URL.
