# Resposta ao VendaX — o texto da cobrança de saída vem do nó

**Data:** 15/11/2026 · **Pedido:** `PEDIDO-2026-11-14-archflow-texto-sem-tool-devolve-a-vez.md` (VEN-1083, reescrito em
15/11) · **Base:** `origin/main` `8dfe558` · **Entrega:** branch `feat/lembrete-da-saida`

Os dois itens foram implementados. O limite de uma cobrança por execução não mudou.

## 1. `lembreteDaSaida`

É uma chave nova e opcional no nó `mcp-agent`:

```json
{
  "saidaDaTool": ["responder_diretoria"],
  "exigirSaidaDaTool": true,
  "lembreteDaSaida": "Continue: se falta um número, chame a próxima ferramenta de leitura; se já tem o que precisa, responda chamando responder_diretoria."
}
```

Na DSL: `.outputReminder("…")`, ou a forma crua `.with("lembreteDaSaida", "…")`.

- **Com a chave,** a cobrança manda ao modelo esse texto, tal como está e como mensagem de usuário, a mesma
  forma que a cobrança já usa. O motor não acrescenta nem interpola nada.
- **Sem a chave, ou com texto em branco,** vale a frase de sempre. Nada muda para quem já usa a cobrança.
- **Sem `exigirSaidaDaTool` e `saidaDaTool`, o nó é recusado na carga.** Sem elas não há cobrança, então o
  texto nunca seria dito.
- **O texto vai no estado da retomada,** então vale também quando a cobrança acontece depois de uma
  aprovação humana.

## 2. A cobrança no `uso`

Há um campo aditivo novo, `uso.outputReminders`: o número de cobranças da execução, hoje `0` ou `1`. Ele vem
sempre que há `uso`, inclusive com `0`. A volta extra continua contada em `llmTurns`. O novo campo só permite
separá-la de uma leitura a mais. No relato do que foi gasto depois do prazo (`menos`), conta só as cobranças
daquele trecho.

## Testes

`LembreteDaSaidaTest` cobre:

- o texto do nó chega ao modelo, e sem ele vai a frase padrão;
- a cobrança é contada no `uso` e chega a `outputReminders`;
- `0` quando não há cobrança;
- o recorte do trecho depois do prazo;
- o repasse ao runner e à retomada;
- a recusa sem `exigirSaidaDaTool`.

A suíte da DSL (`McpAgentTest`) também cobre a chave.
