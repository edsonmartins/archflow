# ArchFlow — resposta ao pedido "o nó `mcp-agent` precisa saber declarar nenhuma ferramenta"

Resposta ao pedido do VendaX Core de 22/09/2026. Feito como pedido, no motor: nada por agente, nada
de `case`.

## 1. O que muda

| config `tools` | antes | agora |
|---|---|---|
| sem a chave | todas as do server, sujeitas ao teto do host | **igual** |
| `["a", "b"]` | allowlist ∩ teto | **igual** |
| **`[]`** | **igual a ausente — todas as do server** | **nenhuma**: `allowOnly(Set.of())`, e o passo roda com `SemFerramentas` — sem abrir cliente MCP |

Três pontos do nó passaram a distinguir os dois casos:

- `politicaDeAcesso` → `allowOnly(Set.of())`;
- `toolsPermitidas` (a retomada) → **conjunto vazio**, não `null`: quem retomar volta sem tool, como foi;
- `execute` → **não chama `host.clientFor`**. Filtrar o server real até o conjunto vazio daria o mesmo
  catálogo ao modelo, mas ainda faria a ida ao server para listá-lo, e o passo passaria a depender de
  um servidor que ele não usa.

**Uma lista só de espaços** (`["  "]`) cai como "nenhuma". É declaração malformada, e esse é o lado
seguro do erro — o outro seria dar o server inteiro a quem quis dar nada.

O documento de config do nó e o `docs/CATALOGO-NOS-FLUXO-LLM.yaml` ganharam a terceira linha (o
catálogo ainda dizia "vazio usa política do host", que era justamente o comportamento que este pedido
corrige).

## 2. Um detalhe que o teste mostrou, e que vocês vão ver na bancada

Com `tools: []`, se o modelo **insistir** em chamar a tool, a tentativa **fica registrada** no
`toolCalls` do result — com `isError: true` e o texto `ERRO: a tool 'X' não está autorizada para este
agente.`. Ela não roda, e nada vai ao servidor; mas a lista não fica vazia.

Não escondemos a tentativa de propósito: sumir com ela tiraria de vocês o sinal de que o modelo tentou
sair do contrato — que é exatamente o que vocês querem ver na bancada do AP. Se, comparando o fluxo com
o `case`, aparecer `toolCalls` não vazio no `ap@1`, é isto: o modelo tentou, e foi negado.

## 3. Sobre o `ap@1` de vocês

- `"tools": []` com `maxIterations: 1` agora faz o que o `case "AP"` faz: catálogo vazio, uma volta,
  sem servidor. A tentação some junto com o catálogo, porque o modelo não vê tool nenhuma no prompt.
- A diferença de prompt que vocês apontaram (`{"narracao": "<a frase>"}` em vez de texto puro) é o que
  o caminho de fluxo precisa mesmo: ele passa a saída por `extractJson`. O `case` aceita as duas formas
  — `NarracaoDoDia.narracao` desembrulha o `{"narracao": …}` —, então a comparação da bancada é justa.

## 4. Como foi verificado

`McpAgentComponentTest`, 6 casos novos:

- `tools: []` → nenhuma tool passa, o cliente do laço é `SemFerramentas.INSTANCIA` e **`clientFor` não
  é chamado**;
- **chave ausente** → o server inteiro, e o cliente vem do host (o par que o pedido pede);
- `tools: []` **com teto do host** → continua nenhuma: o teto é limite, não fonte;
- a retomada volta com conjunto vazio, não `null`;
- lista só de espaços → nenhuma;
- **com o laço de verdade**: o modelo pede `obter_cliente_360`, o catálogo do prompt vai vazio, a
  chamada é negada (fica como tentativa com `isError`), nenhum servidor é aberto, e o passo termina
  com o texto do modelo.

Bateria do `archflow-api` inteira: 799 casos, 0 falhas.
