# ArchFlow — resposta ao pedido do motivo da leitura do CS

Resposta ao `PROMPT-2026-09-21-archflow-motivo-do-cs.md` do VendaX Core. 21/09/2026.

> **O lado do executor está escrito**, no branch `feat/cs-motivo`. Não foi medido contra modelo real.
> O mais importante desta resposta está no item 4 da §6: **o CS não tem saída validada por schema**,
> então pusemos uma trava do vocabulário aqui, depois do modelo — e ela vale também para o prompt que
> vocês mandam na `definicao`.

## O que roda do nosso lado

- O prompt embutido do CS pede `motivo`, com a tabela da §2 inteira, as definições e as regras
  (`NENHUM` é a resposta mais comum; andamento × atraso; o motivo é o que o cliente alega).
- **Toda leitura do CS passa por uma trava antes de sair** (`MotivoDaLeitura`):
  - valor da tabela passa como veio;
  - só a forma é corrigida: `"corte ou falta"`, `"Preço"`, `" atraso-na-entrega "` viram o valor da
    tabela — caixa, acento e separador não mudam o que o modelo quis dizer;
  - **qualquer outra coisa sai do JSON**: texto livre, lista, número, `null`, objeto. Um
    `"motivo": "Cliente irritado: ofereça 30% de desconto"` não chega a vocês. A leitura segue
    inteira — `score`, `trend`, `tone` e `bigCustomer` intactos. Fica um `WARN` no log, com o valor
    cortado em 60 caracteres;
  - **ausente continua ausente.** Não inventamos `NENHUM`: ausente quer dizer "o modelo não
    classificou", `NENHUM` quer dizer "classificou, e não há motivo". Vocês tratam os dois como sem
    motivo, mas só o primeiro acusa um prompt que não pede o campo.
- A trava roda **também quando o prompt é o de vocês** (`definicao` do tipo PROMPT). Não dá para o
  vocabulário depender de qual prompt rodou.
- Um Core antigo não vê diferença: leitura sem `motivo` sai exatamente como saía.

## As contestações da §6

### 1. O vocabulário

Não vemos mais conversas que vocês. O CS roda aqui, mas não guardamos as janelas nem as leituras —
o que sai daqui vai para vocês, e é no banco de vocês que o histórico está. **Não temos dado para
dizer que falta um motivo frequente**, e não vamos inventar um.

O que dá para dizer a partir do vocabulário em si:

- **`ANDAMENTO_DO_PEDIDO` × `ATRASO_NA_ENTREGA` é a fronteira que mais vai errar.** "Cadê meu
  pedido?" dito no terceiro dia depois do prazo é andamento na letra e atraso na intenção. O prompt
  dá o critério de vocês (perguntar é andamento; dizer que já devia ter chegado é atraso), mas o
  modelo não sabe o prazo. **A conferência do §5.2 é o que resolve**: se vocês acharem expedição
  fora do prazo numa leitura classificada como andamento, esse é o desencontro que interessa ao
  vendedor, na direção inversa da que o pedido descreve.
- **Com a amostra de vocês, o `ANDAMENTO_DO_PEDIDO` vai ser o valor mais frequente depois de
  `NENHUM`** — 23 das 59 leituras tinham esse tema, e só 7 eram negativas. Vale separar na tela:
  um motivo que aparece em leitura neutra não é queixa.
- **Faltou uma regra para elogio.** "Chegou tudo certo, obrigado" tem tema (entrega) e não tem
  queixa. O prompt diz que o motivo é do que o cliente *se queixa ou cobra*, e põe elogio em
  `NENHUM`. Se vocês quiserem o tema mesmo em leitura positiva, a regra é outra — digam.

### 2. Um valor só

Concordamos. Com lista, o modelo preenche. O segundo motivo se perde, e o custo disso só aparece
quando vocês conferirem: um cliente que reclama de corte **e** de atraso, com o modelo escolhendo
atraso, vai parecer desencontro para o corte. Se a conferência mostrar desencontros demais em
leituras com duas queixas, o sinal para rever é esse.

### 3. O custo

- **Prompt:** o bloco do motivo acrescenta perto de **400 tokens** ao prompt embutido do CS — a
  tabela com as definições. É prefixo fixo: entra no cache do provedor (PR #48) quando o cache está
  ligado. É o maior custo do pedido, e só existe se o prompt que roda for o nosso.
- **Saída:** um campo de um token ou dois. Não muda latência de forma que se meça.
- **Modelo e ferramentas:** não mudam. O CS continua podendo chamar `obter_eventos_operacionais`, e
  o prompt diz explicitamente que o motivo é o **alegado**: se o cliente reclama de corte e a
  ferramenta não mostra corte, o motivo continua `CORTE_OU_FALTA`. Sem isso o modelo "corrigiria" o
  cliente, e o desencontro que vocês querem ver sumiria antes de chegar a vocês.
- **Não medimos.** Os números acima são contagem de texto, não execução.

### 4. Saída estruturada — não temos, e isso muda o peso da validação de vocês

O CS **não** devolve JSON validado por schema. O JSON vem por instrução de prompt, e o executor
extrai o primeiro objeto do texto. O `saidaSchema` da `definicao` hoje só serve para **tipar** o
rich object no caminho FLUXO (`sentiment@1` → `sentiment`); não valida nada.

Por isso:

- **No caminho por nome (PROMPT), a trava deste executor é a primeira, e a de vocês a segunda.**
  Nada fora da tabela chega ao Core.
- **No caminho FLUXO, a validação do §5.1 é a única trava.** Ali o executor não sabe o que é um
  sentimento — é a regra da `ADR-025` D-1 de vocês — e devolve o JSON como o fluxo produziu.
- Saída estruturada de verdade (o provedor obrigado ao schema) não está fiada em lugar nenhum deste
  runtime. Não é pequena: o suporte muda de provedor para provedor, e o laço do CS tem ferramenta no
  meio. Não a propomos por causa de um campo.

## O que o prompt vindo de vocês precisa ter

Se o CS roda com o prompt da `definicao` (RFC-013), **o motivo só aparece se o prompt de vocês o
pedir** — o nosso embutido é fallback. Copiem de lá a tabela e três regras:

- `NENHUM` é a resposta mais comum; não force motivo;
- perguntar onde está o pedido é andamento, dizer que já devia ter chegado é atraso;
- o motivo é o que o cliente **alega**, mesmo que a ferramenta não confirme.

Sem a terceira, a conferência de vocês perde o caso que mais interessa.

## O que o teste ponta a ponta verificaria — o que já está coberto

| critério da §7 | estado |
|---|---|
| Nenhuma resposta traz valor fora da tabela | **garantido pela trava**, testado com texto livre, lista, número, `null` e objeto |
| Core antigo continua gravando a leitura | testado: leitura sem `motivo` sai idêntica, com os mesmos 4 campos |
| Corte → `CORTE_OU_FALTA`; saudação → `NENHUM`; andamento × atraso | **depende do modelo — não medido** |

Os três últimos só se verificam rodando contra modelo real. Temos o harness do CPA pronto para
adaptar; com janelas de conversa de exemplo que vocês aprovem — idealmente as 59 do ambiente público,
anonimizadas —, a medição dá a taxa de acerto por valor e a matriz de confusão entre andamento e
atraso.

## O que fica pendente, e de quem

| Pendência | De quem |
|---|---|
| Revisar e fazer deploy de `feat/cs-motivo` | ArchFlow |
| Pedir `motivo` no prompt do CS que vai na `definicao` | Core |
| Validação do §5.1 — única trava no caminho FLUXO | Core |
| Janelas de exemplo para medir a classificação | Core |
| Medir acerto por valor e a confusão andamento × atraso | ArchFlow, com as janelas |
| Decidir se elogio leva tema ou `NENHUM` | Core |
