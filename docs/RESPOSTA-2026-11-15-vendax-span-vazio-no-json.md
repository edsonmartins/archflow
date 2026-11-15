# Resposta ao VendaX — `<span>` vazio entre os campos do JSON

**Data:** 15/11/2026 · **Pedido:** `PEDIDO-2026-11-15-archflow-span-no-json.md` (VEN-1132) · **Base:** `origin/main`
`1a91cfc` · **Entrega:** branch `fix/span-vazio-no-json`

## O que muda

Esta é a segunda normalização do `JsonDeSaida.objeto`, ao lado da cerca externa. Ela é estreita, tem nome e
fica registrada no log:

1. **Lê como hoje.** Se a saída for um objeto, é devolvida sem mudança e sem log novo.
2. **Se não for, remove só os `<span>` vazios que estão fora de string JSON** e lê de novo, **uma vez**, com as
   mesmas regras (`FAIL_ON_TRAILING_TOKENS`, `STRICT_DUPLICATE_DETECTION`, objeto). Conta como vazio:
   - `<span>` exatamente, sem atributo;
   - só espaço em branco, inclusive quebra de linha;
   - `</span>`.
3. **Se der objeto,** devolve o texto normalizado e registra no log, em INFO:
   ```
   agent_output_normalized: emptySpan=true removed=<n>
   ```
   Com a cerca externa na mesma saída, a linha é
   `agent_output_normalized: externalFence=true emptySpan=true removed=<n>`.
4. **Se não der,** devolve `null`, como hoje. O `ERROR` "não devolveu um JSON" e o `outputText` saem iguais. O
   `outputText` traz o texto original, sem a normalização.

Quatro coisas continuam fora da normalização:

- **O texto dentro de string,** como o valor de `"resposta"`, fica como veio, mesmo que tenha `<span> </span>`.
  A varredura acompanha aspas e escape (`\"`).
- **`<span>` com conteúdo, com atributo ou outra tag** continuam falhando.
- **Vírgula ou aspa faltando** também continuam falhando. A normalização não repara nada.
- **O resultado `OK`** não ganha campo novo. A normalização só aparece no log.

## Versão

A versão dos POMs não muda: continua **1.5.1**, porque o release não altera os POMs da `main`. Na VPS, a correção
entra no ar quando o PR for mergeado, com a imagem `sha-<commit do merge>`, como sempre.

## Testes

`JsonDeSaidaTest` (novo), com o par "deve" / "não deve" de cada regra do pedido:

- o exemplo medido e as variantes `<span></span>` e `<span>\n</span>`;
- span dentro de string, inclusive depois de uma aspa escapada;
- span com conteúdo, com atributo e outra tag;
- JSON quebrado por outro motivo;
- trailing tokens, array e chave duplicada continuam recusados;
- a cerca externa, com e sem o span.

No dispatcher, a saída medida vira `OK` com o objeto (`caixas: 20`). Um span com conteúdo continua `ERROR`, com o
`outputText` original. A suíte do `archflow-api` está verde: 967 testes, 3 pulados (os ao vivo).
