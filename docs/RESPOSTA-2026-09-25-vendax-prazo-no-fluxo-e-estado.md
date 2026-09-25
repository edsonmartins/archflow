# ArchFlow — resposta ao consolidado de 25/09/2026

Responde ao "O que o VendaX espera do ArchFlow" ponto por ponto.

## 1. Prazo no fluxo — **feito** (branch `feat/prazo-no-fluxo`)

**Uma correção na premissa, antes do resto:** o `validoAte` não estava "sem nenhuma leitura" — ele
**não existia** no nosso `VendaxInvoke`. O record é `@JsonIgnoreProperties(ignoreUnknown = true)`, de
propósito, para que um envelope novo não derrube o receptor; o efeito colateral é que o campo chegava
e era **descartado em silêncio**. Não havia o que ler.

Agora há, e ele manda:

| situação | o que acontece |
|---|---|
| `validoAte` no futuro, fluxo responde antes | `OK`, como sempre |
| `validoAte` no futuro, fluxo demora mais | **`ERROR` no instante do prazo**, com a mesma `idempotencyKey`; o que o fluxo produzir depois é **descartado** |
| `validoAte` **já passado** quando o invoke chega | **`ERROR` sem executar o fluxo** — nada de modelo, nada de custo |
| `validoAte` ausente ou vazio | sem prazo: roda até o fim (lote, e todo Core anterior ao campo) |
| `validoAte` ilegível | sem prazo, com aviso no log |

- É o mesmo desenho do `comPrazo` dos `case`: o trabalho roda noutra thread, a correlação é reposta
  nela, e estourado o prazo a thread é interrompida.
- **O gasto depois do prazo é relatado**, como no NS e no US: o que o modelo consumir depois do
  `ERROR` vai para `/archflow/uso` com motivo `APOS_PRAZO` e o mesmo `execucaoId`. Interromper não
  desfaz a chamada de modelo em voo, e ela é cobrada.
- Aceita instante com deslocamento (`2026-09-25T14:03:00-03:00`) e em UTC (`…Z`).

**Isto destrava a promoção de `ns@1`, `us@2` e `cpa@1`** — do nosso lado.

## 2. `case "CPA"` — **não retirado, e de propósito**

Concordamos com a ordem que vocês escreveram: retirar antes da promoção do `cpa@1` faz o roteiro
voltar `ERROR`. A sequência que seguimos:

1. este PR entra e é implantado (item 1);
2. vocês promovem o `cpa@1` e confirmam;
3. **então** retiramos o `case`, num PR só disso.

**Nos avisem quando a promoção estiver feita.** Enquanto isso o `case` fica onde está.

Sobre o prompt medido: ele está no `RoteiroDeAbordagem` deste repositório, com a medição em
`docs/MEDICAO-2026-09-20-cpa-roteiro.md`. As duas diferenças que vocês listaram são as que a medição
também recomendou — a nota do vendedor fora do payload e a resposta em JSON.

## 3. Qual versão está implantada

**`sha-f3e89d4`**, no ar há 24 horas, `healthy`. É o merge do PR #70.

**Sim, inclui o #68** — e todo o resto do período. Conferido por ancestralidade:

| PR | merge | está em `f3e89d4`? |
|---|---|---|
| #68 `tools: []` = nenhuma ferramenta | `6f4147b` | **sim** |
| #69 DSL 1.4.0 (`McpAgent`) | `ef26d89` | sim |
| #66 reentrega do result | `4a6e23d` | **sim** |
| #65 CS com raciocínio mínimo | `2c80ca5` | **sim** |
| #64 CS sem JSON: nova tentativa e trecho no `ERROR` | `d58cf76` | **sim** |

Então a bancada do `ap@1` pode rodar: `tools: []` já significa "nenhuma ferramenta" no ambiente
público. Um aviso para a leitura do `uso`: com `tools: []`, **se o modelo insistir em chamar a tool,
a tentativa aparece em `toolCalls` com `isError: true`** e o texto "não está autorizada". Ela não
roda e nada vai ao servidor; mas `toolCalls` vazio não é a única saída correta — ver a
`RESPOSTA-2026-09-22-vendax-no-sem-ferramentas`, §2.

O prazo do item 1 **ainda não está implantado**: ele sai no próximo deploy, depois do merge.

## 4. Do nosso lado

- **DSL 1.4.0 está no Maven Central**, verificada: `archflow-model`, `archflow-dsl` e
  `archflow-sdk-java` respondem 200 no `repo1.maven.org`, e o `McpAgent.class` está dentro do jar.
  Quando trocarem a 1.2.0, `"tools": []` vira `McpAgent.node().noTools()` — o método existe porque o
  valor decide o comportamento, e a chave crua não mostra isso a quem lê o fluxo.
- **WC e PGU nascendo fluxo**: sem `case`, nada a fazer aqui. A primeira promoção medindo só a
  candidata é o que o prazo do item 1 torna comparável — antes dele, um fluxo interativo sem prazo
  não era comparável a um `case` com prazo.
