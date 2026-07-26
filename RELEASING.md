# Publicando no Maven Central

Os artefatos vão para o [Maven Central](https://central.sonatype.com) pelo
**Sonatype Central Portal**, via `central-publishing-maven-plugin` (profile
`maven-central` no `pom.xml` raiz).

> **groupId:** `br.com.archflow` — verificado pelo domínio `archflow.com.br`.
> A versão vem da tag git, não do que está escrito nos POMs.

## O que é publicado

São **três** dos 38 módulos:

| artefato | para quê |
|---|---|
| `archflow-model` | modelos de domínio; é a única dependência do `archflow-dsl` |
| `archflow-dsl` | escrever workflows em Java e emitir o documento canônico |
| `archflow-sdk-java` | `ArchflowClient` (REST) e `EmbeddedWorkflowRunner` |

```xml
<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-dsl</artifactId>
    <version>1.0.0</version>
</dependency>
```

### O que NÃO é publicado, e a consequência

Todo o resto — inclusive o `archflow-standalone`, que terá instalador próprio.

Isso tem um efeito visível para quem consome do Central: o
**`EmbeddedWorkflowRunner` não funciona**. Ele depende do `archflow-standalone`,
que é uma dependência `optional` do SDK (não quebra a resolução de ninguém) mas
não existe no Central. Quem precisa de execução embarcada hoje constrói o
archflow do fonte (`mvn install`). O próprio runner diz isso ao falhar, em vez
de estourar um `NoClassDefFoundError` cru.

`ArchflowClient` e toda a DSL funcionam normalmente.

### Como um módulo entra ou sai da publicação

É **opt-in por módulo**. O pom raiz define:

```xml
<archflow.publish.skip>true</archflow.publish.skip>
```

e cada módulo publicado vira para `false` no próprio pom. A inversão é
deliberada: com uma lista de exclusão, esquecer de acrescentar um módulo novo o
publicaria — e publicação é irreversível, o Central não aceita remover uma
versão. Assim, esquecer significa "não publicou", que é o erro barato.

O workflow de release ainda restringe o reator com `-pl`. São duas barreiras
independentes de propósito.

## Pré-requisitos (uma vez)

### 1. Namespace no Central Portal

1. Entre em https://central.sonatype.com.
2. **Namespaces → Add Namespace** → `br.com.archflow`.
3. Adicione o registro **TXT** que o portal sugere no DNS de `archflow.com.br`.
4. Aguarde ficar **verified**.

### 2. Token de publicação

Em **Account → Generate User Token**. Gera um par:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_TOKEN`

### 3. Chave GPG

```bash
gpg --gen-key                                                # guarde a passphrase
gpg --list-keys                                              # copie o KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>    # publique a pública
gpg --armor --export-secret-keys <KEY_ID> | base64           # para o secret do CI
```

O Central verifica a assinatura contra a chave pública no keyserver. Se o
`send-keys` não for feito, a validação falha depois do upload — o erro aparece
tarde e é confuso.

### 4. Secrets no GitHub

*Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Conteúdo |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | username do token do Central Portal |
| `MAVEN_CENTRAL_TOKEN` | password do token do Central Portal |
| `GPG_PRIVATE_KEY` | chave privada GPG exportada em base64 (armored) |
| `MAVEN_GPG_PASSPHRASE` | passphrase da chave GPG |

## Release

```bash
git tag v1.0.1
git push origin v1.0.1
```

O job `maven-central` em `.github/workflows/release.yml` ajusta a versão dos
POMs a partir da tag, **roda a suíte de testes**, assina e publica com
`autoPublish=true`.

Os testes rodam aqui mesmo o job `build-backend` usando `-DskipTests`: uma imagem
Docker se sobrescreve, uma versão no Central não.

Acompanhe em https://central.sonatype.com/publishing/deployments.

## Release manual (local)

```bash
export MAVEN_CENTRAL_USERNAME=...
export MAVEN_CENTRAL_TOKEN=...
export MAVEN_GPG_PASSPHRASE=...

mvn versions:set -DnewVersion=1.0.1 -DgenerateBackupPoms=false
mvn clean install                       # popula o repo local (o SDK precisa do standalone)
mvn deploy -Pmaven-central -DskipTests \
    -pl archflow-model,archflow-dsl,archflow-sdk-java \
    -s .maven-settings.xml.template
```

O `install` completo antes não é opcional: o `archflow-sdk-java` depende do
`archflow-standalone`, que não está no reator restringido pelo `-pl` nem no
Central.

## Antes da primeira publicação

O caminho de publicação **não foi exercitado de ponta a ponta** — falta
credencial do Central e chave GPG, que não existem nesta máquina. O que está
verificado é que o profile ativa, que os módulos certos resolvem
`archflow.publish.skip=false` e que os jars de sources e javadoc são gerados.

Para um ensaio sem publicar nada, com as credenciais já configuradas:

```bash
mvn deploy -Pmaven-central -DskipTests -DskipPublishing=true \
    -pl archflow-model,archflow-dsl,archflow-sdk-java \
    -s .maven-settings.xml.template
```

Isso monta e assina o bundle sem enviá-lo.

### Ensaio com upload, mas sem publicar

Mais próximo do real: sobe o bundle, deixa o Central **validar** e para antes de
publicar, para você conferir os artefatos no portal e concluir com um clique.
Recomendado na primeira vez, porque uma versão publicada não se remove.

```bash
mvn deploy -Pmaven-central -DskipTests \
    -Darchflow.publish.auto=false -Darchflow.publish.waitUntil=validated \
    -pl archflow-model,archflow-dsl,archflow-sdk-java \
    -s .maven-settings.xml.template
```

Conclua em https://central.sonatype.com/publishing/deployments. As duas
propriedades existem porque uma `<configuration>` literal no POM venceria o
`-D` da linha de comando — sem elas, não haveria como ensaiar.
