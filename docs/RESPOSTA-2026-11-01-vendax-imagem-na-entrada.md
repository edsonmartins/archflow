# Resposta ao VendaX — imagem na entrada do agente

**Data:** 01/11/2026 · **Pedido:** `analysis/PEDIDO-2026-11-01-archflow-imagem-no-no-de-llm.md` (VendaX, VEN-516)

## O que mudou

O invoke ganha o campo `imagens`, e todo nó `mcp-agent` do fluxo recebe essas imagens no **primeiro turno do
usuário**, logo depois do texto:

```json
{"tenantId": "…", "agent": "CPE", "text": "…",
 "imagens": [{"mime": "image/png", "base64": "iVBORw0KGgo…"}]}
```

- **Tipos aceitos:** `image/png`, `image/jpeg`, `image/webp` e `image/gif`. Fora disso, ou com base64 inválido, o
  envelope é recusado antes de chamar o modelo.
- **Sem `imagens`, nada muda:** o primeiro turno é a mesma `UserMessage.from(texto)` de antes.
- **Chave transient:** as imagens vão ao contexto do fluxo sob `transient.imagens`, como a memória do cliente, e
  não entram no estado durável. Um passo executado depois de uma retomada roda sem elas.
- **O modelo do nó precisa ter visão.** Quem escreve o fluxo escolhe o modelo.

## Diferença em relação ao pedido

O pedido propunha uma opção na DSL (`Nodes.llmChat(...).imagens(...)`). Os fluxos do VendaX usam o nó
`mcp-agent`, não o `llm-chat`, e a memória do cliente já atravessa pelo invoke. As imagens seguem o mesmo
caminho: **não há mudança na `archflow-dsl`**, e o Core não precisa de versão nova da biblioteca. Basta o
servidor com esta versão.

## Onde

- `ImagemDaEntrada`: o tipo, com a validação.
- `VendaxInvoke.imagens`: o campo do envelope, com construtor de compatibilidade.
- `AgentFlowRunner`: põe as imagens no contexto, sob `McpAgentComponent.CTX_IMAGENS`.
- `McpAgentComponent`: lê do contexto e passa às `Options` (`comImagens`).
- `McpAgentRunner.primeiroTurno`: monta o `TextContent` e um `ImageContent` por imagem.

## Testes

- **`ImagemNoInvokeTest` (7 testes):** o texto e a imagem no primeiro turno; o caso sem imagem; o codec do estado
  suspenso; o envelope; a recusa; o contexto transient.
- **`McpAgentComponentTest`:** o contexto chegando às `Options`, com e sem imagem.
- **Os defeitos foram reintroduzidos**, um de cada vez: tirar o `comImagens` do componente e ignorar as imagens no
  primeiro turno. Cada um deixa vermelho só o seu teste, e os testes de memória seguem verdes.

## O que não foi conferido

- **Uma chamada real ao OpenRouter por este caminho.** O formato que o adaptador da OpenAI gera para o
  `ImageContent` (`image_url` com `data:`) é o que a bancada do VendaX usou direto contra a API.
- **O limite de corpo do `invoke` na VPS.** Um encarte em PNG tem centenas de KB em base64.
