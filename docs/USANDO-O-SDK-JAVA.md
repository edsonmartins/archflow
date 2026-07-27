# Usando o archflow em outro projeto Java

Guia da DSL e do SDK publicados no Maven Central em **1.1.0**.

---

## 1. Dependências

Escrever fluxos (só isso):

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-dsl</artifactId>
    <version>1.1.0</version>
</dependency>
```

Escrever **e** falar com um servidor archflow:

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-sdk-java</artifactId>
    <version>1.1.0</version>
</dependency>
```

O `archflow-sdk-java` já traz o `archflow-dsl`. Gradle:

```kotlin
implementation("br.com.archflow:archflow-sdk-java:1.1.0")
```

**Requer Java 25.** Os artefatos são compilados com `release 25`; um projeto em 17 ou 21 não consegue nem compilar contra eles.

### O que está publicado, e o que não está

| artefato | no Central |
|---|---|
| `archflow-dsl` | sim |
| `archflow-sdk-java` | sim |
| `archflow-model` | sim (dependência do dsl) |
| `archflow-standalone` | **não** |

A ausência do `archflow-standalone` tem uma consequência concreta: o
`EmbeddedWorkflowRunner` (seção 5) **não funciona** só com o que vem do Central.
Ele é uma dependência `optional`, então nada quebra na resolução — mas para
executar no próprio processo hoje é preciso construir o archflow do fonte
(`mvn install`). O runner falha com uma mensagem dizendo exatamente isso.

Tudo o mais nas seções 2 a 4 e 6 funciona só com o Central.

---

## 2. Escrevendo um fluxo

```java
import br.com.archflow.dsl.*;

WorkflowDocument doc = Workflows.define("rag-doc-qa")
        .named("Document Q&A")
        .describedAs("Responde citando a base de conhecimento")
        .version("1.0.0")
        .allowing("input", "pgvector", "openai", "notify")
        .step("pergunta",  Nodes.component("input"))
        .step("busca",     Nodes.vectorSearch("pgvector").with("topK", 6))
        .step("resposta",  Nodes.llmChat("openai")
                                .with("model", "gpt-4o")
                                .with("temperature", 0.2))
        .step("falhou",    Nodes.component("notify"))
        .edge("pergunta", "busca")
        .edge("busca", "resposta", "chunks.size() > 0")
        .onError("busca", "falhou")
        .build();
```

### Saídas

```java
doc.toJson();            // documento canônico, identado
doc.toYaml();            // mesmo texto de GET /api/workflows/{id}/yaml
doc.toMap();             // Map<String,Object>, sem passar por serialização
doc.id();                // "rag-doc-qa"
doc.writeTo(Path.of("fluxos/rag.yaml"));   // formato pela extensão (.yaml/.yml → YAML)
```

### Arestas

| método | significado |
|---|---|
| `.edge(a, b)` | quando `a` termina bem, segue para `b` |
| `.edge(a, b, "cond")` | idem, se a condição for satisfeita; a DSL transporta o texto sem interpretar |
| `.onError(a, b)` | percorrido quando `a` **falha** |

Não existe aresta de erro condicional — `onError` não aceita condição.

---

## 3. Os tipos de nó

A DSL é tipada onde o motor tem contrato fechado, e aberta a string onde não tem.
Isso é deliberado, e vale entender antes de procurar um método que não existe.

### 3.1 Passos do próprio motor

```java
Nodes.approval()      // portão humano: suspende o fluxo até alguém decidir
Nodes.orchestrate()   // orquestração dinâmica multi-agente
```

São os únicos cujo comportamento a DSL pode prometer: o motor os reconhece por
tipo e os constrói com classes dedicadas, sem passar pelo catálogo.

```java
.step("aprovacao", Nodes.approval().with("prompt", "Pode aplicar a correção?"))
```

### 3.2 Nós servidos por adapter LangChain4j

O **conjunto de tipos** é fechado; o **provider** é string, porque quais existem
depende de quais adapters estão no classpath do servidor.

```java
Nodes.llmChat("openai")           Nodes.embedding("openai")
Nodes.llmStreaming("anthropic")   Nodes.memory("redis")
Nodes.vectorSearch("pgvector")    Nodes.rag("default")
Nodes.vectorStore("pinecone")
Nodes.adapter("tipo-novo", "provider")   // escape hatch
```

### 3.3 Qualquer componente do catálogo

```java
Nodes.component("semantic-router")
```

**String pura, sem verificação em compilação.** Não é preguiça: o catálogo é
populado em runtime, por SPI e por jars de plugin carregados de um diretório.
Não existe lista fechada em tempo de compilação, e helpers por componente
produziriam uma API que envelhece em silêncio quando um plugin some.

### 3.4 Ajustes de nó

```java
Nodes.component("prompt-template")
     .with("template", "rag.qa")        // uma chave
     .with(Map.of("a", 1, "b", 2))      // várias
     .operation("render")               // default: "execute"
     .labeled("Monta o prompt")         // nome no canvas; o motor não lê
     .at(720, 200)                      // posição no canvas do designer
```

`NodeSpec` é **imutável** — cada `with` devolve uma instância nova. Dá para
guardar um nó em constante e reusá-lo em vários fluxos sem vazamento:

```java
static final NodeSpec GPT4O = Nodes.llmChat("openai").with("model", "gpt-4o");
```

---

## 4. Validação

`build()` lança `DslValidationException` com **todos** os problemas, não só o
primeiro:

```java
try {
    doc = Workflows.define("f")./* ... */.build();
} catch (DslValidationException e) {
    e.getProblems().forEach(System.err::println);
}
```

Pega:

- id do fluxo em branco
- fluxo sem nenhum passo
- aresta para ou de um passo inexistente
- passo duplicado (falha já no `.step()`, não no `build()`)
- componente usado mas fora do `allowing(...)` declarado

**Não** pega — e não tem como: se o componente existe de fato, se o provider do
adapter está no classpath, se as chaves de `config` fazem sentido. Nada disso é
conhecível fora do runtime que vai executar o fluxo.

### Sobre o `allowing(...)`

Restringe o fluxo a uma lista de componentes. Um passo fora dela falha com
"component not found" em runtime — e a restrição vale também para os sub-agentes
de um nó de orquestração, senão seria contornável por delegação.

**Não declarar significa sem restrição.** Declarar faz a DSL conferir a coerência
em `build()`, antes de rodar.

---

## 5. Executando

### 5.1 Pelo servidor (REST)

```java
import br.com.archflow.sdk.*;

ArchflowClient client = ArchflowClient.builder()
        .baseUrl("http://localhost:8080")
        .apiKey(System.getenv("ARCHFLOW_API_KEY"))   // ou .bearerToken(jwt)
        .requestTimeout(Duration.ofSeconds(30))
        .build();

PublishResult r = client.publish(doc);
Map<String, Object> execucao = client.execute(r.assignedId(), Map.of("input", "olá"));
```

Outras operações:

```java
client.get(id);        // Map, ou null se não existir
client.list();         // List<Map>
client.getYaml(id);    // String
client.delete(id);     // false se já não existia
```

Erros viram `ArchflowClientException`, com `getStatusCode()` e `getResponseBody()`.

#### O detalhe do id — leia antes de publicar em produção

A API **não tem upsert por id do cliente**. `POST /api/workflows` descarta o id
do corpo e gera um `wf-<aleatório>`; `PUT` respeita o id mas devolve 404 se o
fluxo não existir.

`publish()` tenta `PUT` e cai para `POST`. Por isso ele devolve um objeto e não
uma `String`:

```java
PublishResult r = client.publish(doc);
if (r.idChanged()) {
    // O servidor gravou com OUTRO id. Guarde r.assignedId() em algum lugar —
    // sem isso, cada publicação cria uma cópia nova em vez de atualizar.
    salvarMapeamento(doc.id(), r.assignedId());
}
```

`r.requestedId()`, `r.assignedId()`, `r.created()`, `r.body()`.

A execução é **assíncrona** no servidor: o retorno de `execute` significa
"aceita e enfileirada", não "concluída". Um fluxo com passo de aprovação fica
suspenso esperando uma pessoa.

### 5.2 No próprio processo (requer build do fonte)

```java
try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder()
        .agentId("meu-app")
        .timeout(Duration.ofMinutes(5))
        .build()) {

    FlowResult result = runner.run(doc, Map.of("input", "olá"));
    System.out.println(result.getStatus());
}
```

Exige `archflow-standalone` no classpath — ver a seção 1.

**Plugins não são carregados por default.** O `AgentConfig` do motor tem
`pluginsPath` default `"plugins"`, e abrir um jar de plugin **executa o `onLoad`
dele** — código arbitrário, sem sandbox, com os privilégios do processo. Uma
biblioteca que faça isso porque existia uma pasta com esse nome ao lado do
executável é uma armadilha, então aqui a varredura vem desligada. Quem precisa:

```java
.pluginsPath("/caminho/que/eu/controlo")
```

Cada runner cria um agente com threads próprias. Use try-with-resources ou uma
instância de vida longa — não uma por execução.

---

## 6. Trazendo fluxos que já existem para código

```java
GeneratedCode code = WorkflowCodeGenerator
        .fromFile(Path.of("fluxos/rag.json"))   // ou .fromJson / .fromYaml / .from(Map)
        .inPackage("com.acme.flows")
        .generate();

if (!code.isLossless()) {
    code.unrepresented().forEach(System.err::println);   // não apague o original ainda
}
Files.writeString(Path.of("src/main/java", code.relativePath()), code.source());
```

A classe gerada expõe `static WorkflowDocument build()`.

**Nem todo documento cabe na DSL**, e o gerador declara o que não soube
representar em vez de descartar em silêncio — um `.java` aparentemente
equivalente é o pior resultado possível numa migração, porque a perda só aparece
depois que o original foi apagado. Casos típicos:

- campos que o servidor acrescenta (`status`, `updatedAt`)
- chaves que a DSL não modela
- aresta que é caminho de erro **e** tem condição (a DSL não combina as duas)

`isLossless()` responde à única pergunta que importa: dá para tratar o código
como a fonte da verdade e descartar o JSON?

---

## 7. Duas armadilhas do formato

Valem para quem for gerar o documento por conta própria, sem a DSL.

**As arestas ficam dentro de cada passo** (`steps[].connections`), que é de onde
o motor as lê. Os templates da UI (`archflow-ui/public/templates`) trazem um
`connections` no **topo** do documento — outro formato. Emitir aquele produz um
fluxo que abre no designer e não caminha ao executar: ele roda o primeiro passo
e para, sem erro.

**O campo `type` tem dois dialetos.** O designer grava o tipo do nó
(`"llm-chat"`, `"input"`); fluxos escritos à mão usam o nome do `StepType`
(`"AGENT"`, `"TOOL"`). Os dois são aceitos. Não reescreva um `llm-chat` para
`TOOL` num round-trip — o roteamento de adapter casa pelo tipo do nó e deixaria
de reconhecer o passo, silenciosamente.

A DSL cuida dos dois casos sozinha.

---

## 8. Exemplo completo

```java
package com.acme;

import br.com.archflow.dsl.*;
import br.com.archflow.sdk.*;
import java.time.Duration;
import java.util.Map;

public final class PublicaFluxo {

    public static void main(String[] args) {
        WorkflowDocument doc = Workflows.define("triagem-incidente")
                .named("Triagem de incidente")
                .allowing("input", "openai", "notify")
                .step("entrada",  Nodes.component("input"))
                .step("analise",  Nodes.llmChat("openai")
                                       .with("model", "gpt-4o")
                                       .labeled("Classifica severidade"))
                .step("gate",     Nodes.approval()
                                       .with("prompt", "Aplicar a remediação proposta?"))
                .step("aplica",   Nodes.component("notify"))
                .step("erro",     Nodes.component("notify").with("canal", "oncall"))
                .edge("entrada", "analise")
                .edge("analise", "gate")
                .edge("gate", "aplica")
                .onError("analise", "erro")
                .build();

        doc.writeTo(java.nio.file.Path.of("fluxos/triagem.yaml"));

        ArchflowClient client = ArchflowClient.builder()
                .baseUrl(System.getenv("ARCHFLOW_URL"))
                .apiKey(System.getenv("ARCHFLOW_API_KEY"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        try {
            PublishResult r = client.publish(doc);
            if (r.idChanged()) {
                System.out.println("Servidor gravou como " + r.assignedId() + " — guarde este id");
            }
            System.out.println(client.execute(r.assignedId(), Map.of("input", "disco cheio em db-01")));
        } catch (ArchflowClientException e) {
            System.err.println("HTTP " + e.getStatusCode() + ": " + e.getResponseBody());
        }
    }
}
```

O passo `gate` suspende o fluxo de forma **durável** — o estado é persistido, e
ele sobrevive a um restart do processo enquanto espera a decisão.
