# Resposta ao VendaX — `<span>` vazio entre os campos do JSON

**Data:** 15/11/2026 · **Pedido:** `PROMPT-2026-11-15-archflow-span-no-json.md` (VEN-1132) · **Base:**
`origin/main` `1a91cfc` · **Entrega:** branch `fix/span-vazio-no-json`

## O que mudou

A mudança está em `JsonDeSaida.objeto`, ao lado da cerca externa. É uma normalização **nomeada, estreita e
registrada**, não reparo genérico.

1. O texto é lido como hoje. Se for um objeto, sai como hoje, sem log novo.
2. Se não for, removo os `<span>` **vazios** que estão **fora de string JSON**: exatamente `<span>`, só
   espaço em branco e `</span>`. Para saber o que está dentro de uma string, a varredura acompanha aspas e
   escape (`\"`).
3. Depois disso o texto é lido de novo, **uma vez só**, com as mesmas regras de hoje:
   `FAIL_ON_TRAILING_TOKENS`, `STRICT_DUPLICATE_DETECTION` e "tem de ser objeto".
4. Se der objeto, a saída é o texto normalizado, e o resultado é `OK`.
5. Se não der, a saída é `null`. O `ERROR` "não devolveu um JSON" e o `outputText` continuam iguais, com o texto
   original.

O que **não** é tocado:

- `<span>` dentro de string, como no valor de `"resposta"`;
- `<span>` com conteúdo ou com atributo;
- qualquer outra tag;
- vírgula ou aspa faltando.

## Como aparece no log

Em INFO, com o mesmo prefixo da cerca externa:

```
agent_output_normalized: emptySpan=true removed=1
agent_output_normalized: externalFence=true emptySpan=true removed=1   (cerca e span juntos)
```

`removed` é o número de spans removidos. **Não há campo novo no result:** o Core recebe `OK` com o objeto já
normalizado.

## Versão

A correção vai para a `main` com o merge do PR e chega ao ambiente pelo CD da VPS. Os POMs seguem em `1.5.1`,
e o artefato no Maven Central só muda na próxima tag de release. Avisamos quando estiver no ar para a nova
rodada da ns@6 (corpus-ns-consulta-v6, 40×3).

## Testes

`JsonDeSaidaTest`, com o par "deve" / "não deve" de cada regra:

| entrada | resultado |
|---|---|
| o exemplo da §2 do pedido | objeto, `caixas = 20` |
| `<span></span>` e `<span>\n</span>` | objeto |
| span dentro de string, inclusive depois de `\"` | objeto, texto intacto |
| span com conteúdo, span com atributo, `<b> </b>` | `null` |
| `{"a": 1 "b": 2}`, com e sem span | `null` |
| span que abriria caminho para trailing tokens, array ou chave duplicada | `null` |
| cerca externa, com e sem span dentro | objeto |
| dispatcher: a saída da §2 | `OK` com o objeto, não `ERROR` |
| dispatcher: span com conteúdo | `ERROR` "não devolveu um JSON" |
