# ArchFlow — resposta ao pedido das `toolCalls` no caminho de fluxo

Resposta ao pedido do VendaX Core de 22/09/2026. Feito como pedido, no branch `feat/fluxo-toolcalls`:
no motor, sem nada por agente e sem `case` novo.

## 1. As três camadas, na mesma linha

| peça | o que mudou |
|---|---|
| `McpAgentComponent` | já produzia `toolCalls`; agora aceita `encerrarEmCodigos` e expõe `encerradoPor` |
| `AgentFlowRunner.Saida` | passa a carregar `toolCalls` e `encerradoPor` — era aqui que o dado morria |
| `VendaxAgentDispatcher.runFluxo` | repassa os dois, no `OK` **e no `ERROR`** |
| `VendaxResult` | dois campos aditivos: `toolCalls` e `encerradoPor` |

## 2. O que o Core passa a receber

```json
"toolCalls": [
  {"name": "plano_negociacao",
   "arguments": {"clienteRef": "20572", "itens": [{"skuRef": "77012:1", "descontoPedidoPb": 850}]},
   "isError": false}
]
```

- **Na ordem em que rodaram**, com o `isError` de cada uma — é dele que sai "a última bem-sucedida".
- **`arguments` como o modelo os mandou**, sem normalizar.
- **Sem o `resultText`**, como vocês pediram: pode ser grande, e o Core é dono das tools.
- **Vale para todo fluxo**, sem `config` para ligar: preferimos como vocês, porque a ausência do campo
  é indistinguível de "não chamou nada". Por isso a lista **vai mesmo vazia** quando nada foi chamado.
- **Campo aditivo.** O caminho por `case` (QP, CS, NS, AP, US) continua sem o campo no envelope —
  ausente ali significa "quem produziu este result não informa", que é o estado de hoje.

## 3. Encerrar em código de erro da tool (§3.1)

No nó:

```json
{"type": "mcp-agent", "config": {"encerrarEmCodigos": [-32001], "...": "..."}}
```

- Lista vazia ou ausente mantém o laço como era: todo erro de tool volta ao modelo, porque um erro
  transitório merece a segunda volta.
- Lista malformada (não é lista, ou tem item que não é inteiro) é **recusada na inicialização do
  passo**, não no meio da execução — o contrário viraria um laço que nunca encerra, e a falha só
  apareceria como uma resposta sem o dado.
- O passo expõe `encerradoPor` (`{"name": "...", "code": -32001}`), e o `result` leva o mesmo campo.

**Uma coisa que o pedido não citava e o teste mostrou:** um laço encerrado **não redige**, então o
fluxo não devolve JSON e o `result` sai como `ERROR`. Sem o campo, esse `ERROR` seria indistinguível
de "o modelo não redigiu" — que é exatamente a distinção que vocês pediram. Por isso o `ERROR` do
caminho de fluxo também leva `toolCalls` e `encerradoPor`, e a mensagem diz o motivo:
`O fluxo de NS não devolveu um JSON (encerrado por plano_negociacao, código -32001)`.

## 4. O que falta para o NS sair do `case`

Do nosso lado, nada mais: o fluxo agora leva o que o `ConsultaAoNegociador` usa — as chamadas com os
argumentos e o encerramento em `-32001`. Quando o lado de vocês montar o `parametro` a partir do
`toolCalls`, o `case "NS"` pode sair, e com ele a última regra de negócio do VendaX que vive dentro
deste executor.

Duas coisas que hoje o `case` faz e **não** são do motor — precisam existir aí:

- o `parametro` sai dos **argumentos da última chamada bem-sucedida** de `plano_negociacao`, não do
  texto do modelo;
- o `ERROR` "o agente NS não foi contratado por este tenant" é uma frase que o `case` escreve ao
  reconhecer o `-32001`. Como fluxo, o Core a escreve a partir do `encerradoPor`.

## 5. Como foi verificado

`ToolCallsNoFluxoTest` (6 casos): o fluxo com duas tools sai com as duas na ordem, com argumentos e
`isError`; o fluxo sem tool sai com **lista vazia, não nula**; o encerramento sobe com nome e código,
no `ERROR`; o envelope tem `name`/`arguments`/`isError` e **não** tem `result`; e o caminho por nome
continua sem os campos.

`McpAgentComponentTest` (5 casos novos): os códigos do nó chegam ao laço; sem a lista, nada encerra;
lista malformada é recusada na inicialização; o passo diz o que encerrou; sem encerramento, o campo
não aparece.

A bateria do `archflow-api` passa inteira (792 casos).
