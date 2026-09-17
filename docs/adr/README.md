# Decisões de arquitetura

O archflow registra decisões em dois tipos de documento, e só nesses dois:

| Tipo | Pasta | Responde | Muda quando |
|---|---|---|---|
| **ADR** | `docs/adr/` | **o que** foi decidido e **por quê** | a decisão muda (nova ADR substitui a antiga) |
| **Design** | `docs/design/` | **como** implementar: assinaturas, pontos de extensão, fases | a implementação avança |

Um design declara, na primeira linha, quais decisões ele detalha ("Detalha D12–D16 da ADR-0005").
Um design sem ADR é sinal de que há decisão registrada no lugar errado.

**RFC não é uma categoria do archflow.** O nome fica para os documentos que produtos consumidores
nos enviam — como a `RFC-005 v2` do VendaX, em `documentos/`. Quando uma RFC externa pede algo que
diverge do archflow, a resposta é uma ADR (exemplo: ADR-0004).

## Convenções

- **Numeração** com quatro dígitos, sem reaproveitar número.
- **Decisões numeradas em sequência entre todas as ADRs** (`D1`, `D2`, …). Uma decisão é citada
  pelo número em qualquer lugar do código ou da documentação, sem precisar dizer de qual ADR.
  Próximo número livre: **D23**.
- **Status:** `Proposto` → `Aceito` → `Aceito (implementado)`; ou `Substituído por ADR-NNNN`,
  ou `Rejeitado`. Uma ADR aceita e só em parte implementada diz quais decisões faltam.
- **O status é atualizado quando o código muda**, no mesmo PR. Status parado em "Proposto" com a
  decisão implementada faz o documento mentir — foi o caso de 0001–0003 até a revisão de
  17/09/2026.
- **Registro retroativo é válido** quando a decisão já foi tomada no código: melhor uma ADR datada
  depois que decisão nenhuma fora do javadoc (exemplo: ADR-0006).
- **Medições entram com data** ("medido em 06/08: …"). É o que permite, depois, saber se o
  motivo ainda vale.
- Português, como o resto da documentação de decisão.

## Índice

| ADR | Título | Status | Decisões | Design |
|---|---|---|---|---|
| [0001](0001-agent-runtime-substrate.md) | Agent Runtime Substrate | Aceito (parcial: D2 parcial, D3 só biblioteca) | D1–D3 | [0001](../design/0001-llm-config-resolver-and-agent-primitive.md), [0002](../design/0002-governance-service-convergence.md) |
| [0002](0002-dynamic-orchestration.md) | Orquestração dinâmica multi-agente | Aceito (parcial: D5–D7 parciais) | D4–D7 | [0003](../design/0003-dynamic-orchestration.md), [0004](../design/0004-workflow-execution-and-orchestration-nodes.md), [0005](../design/0005-async-flow-execution.md) |
| [0003](0003-ag-ui-protocol.md) | AG-UI como protocolo agente↔UI | Aceito (parcial: D9 parcial) | D8–D11 | [0006](../design/0006-ag-ui-bridge.md) |
| [0004](0004-execution-context-mutability.md) | `ExecutionContext`: mutação deprecated | Aceito | — | — |
| [0005](0005-memoria-de-longo-prazo.md) | Memória de longo prazo com Brain Sentry | Proposto | D12–D16 | [0008](../design/0008-memoria-de-longo-prazo-brain-sentry.md) |
| [0006](0006-fronteira-de-confianca-do-harness.md) | Fronteira de confiança do harness de agente | Aceito (implementado) | D17–D22 | — |

Design sem ADR própria: [0007 — MCP em fluxo e execução local](../design/0007-mcp-em-fluxo-e-execucao-local.md)
(levantamento; a Parte A está implementada e suas regras de confiança foram registradas na ADR-0006).

## Modelo

Copie para `docs/adr/NNNN-titulo-curto.md`:

```markdown
# ADR-NNNN — Título que diz a decisão

- **Status:** Proposto
- **Data:** AAAA-MM-DD
- **Decisores:** …
- **Contexto de origem:** o que provocou a decisão (incidente, pedido, análise)
- **Empilha sobre:** ADRs de que esta depende
- **Detalhamento:** design que a implementa, se houver

## Sumário

O problema em um parágrafo e a lista das decisões (`Dn`), uma linha cada.

## Contexto

O estado atual, com evidência: classes, medições datadas, incidentes. O que falha hoje e por quê.

## Decisão

### Dn — Título

O que passa a valer, e as regras que acompanham. Quando já implementada: PRs e classes.

## Consequências

### Positivas
### Negativas / riscos
### Neutras

## Alternativas consideradas

Cada uma com o motivo de rejeição.

## Plano de adoção (ordem)

## Em aberto

## Referências
```
