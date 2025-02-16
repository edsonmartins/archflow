# Guia de Contribuição

## Introdução

Bem-vindo ao guia de contribuição do archflow! Estamos muito felizes que você está interessado em contribuir com o projeto. Este documento fornece as diretrizes e informações necessárias para contribuir efetivamente.

## Índice

1. [Código de Conduta](#código-de-conduta)
2. [Setup do Ambiente](#setup-do-ambiente)
3. [Processo de Contribuição](#processo-de-contribuição)
4. [Padrões de Código](#padrões-de-código)
5. [Testes](#testes)
6. [Documentação](#documentação)
7. [Pull Requests](#pull-requests)

## Código de Conduta

### Nossos Princípios
- Seja respeitoso e inclusivo
- Aceite críticas construtivas
- Foque no que é melhor para a comunidade
- Mostre empatia com outros membros

### Comportamentos Inaceitáveis
- Uso de linguagem ofensiva
- Ataques pessoais
- Trolling ou comentários depreciativos
- Assédio de qualquer natureza

## Setup do Ambiente

### Requisitos
```bash
# Java 17+
java --version

# Maven 3.8+
mvn --version

# Docker
docker --version

# Node.js 18+ (para UI)
node --version
```

### Clone e Build
```bash
# Clone o repositório
git clone https://github.com/archflow/archflow.git
cd archflow

# Build do projeto
mvn clean install

# Executar testes
mvn test

# Iniciar em modo desenvolvimento
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### IDEs Recomendadas
- IntelliJ IDEA
- Eclipse
- VS Code (com extensões Java)

## Processo de Contribuição

### 1. Encontre ou Crie uma Issue
- Verifique as issues existentes
- Procure por issues com label "good first issue"
- Crie uma nova issue se necessário

### 2. Fork e Clone
```bash
# Fork via GitHub UI
# Clone seu fork
git clone https://github.com/SEU-USERNAME/archflow.git

# Adicione o upstream
git remote add upstream https://github.com/archflow/archflow.git
```

### 3. Crie uma Branch
```bash
# Atualize seu fork
git pull upstream main

# Crie uma branch
git checkout -b feature/sua-feature
# ou
git checkout -b fix/seu-fix
```

### 4. Desenvolva
- Siga os padrões de código
- Adicione testes
- Atualize a documentação
- Faça commits pequenos e claros

### 5. Teste
```bash
# Execute todos os testes
mvn test

# Verifique cobertura
mvn jacoco:report
```

### 6. Submeta PR
- Atualize sua branch com upstream
- Resolva conflitos se necessário
- Submeta o PR com descrição clara

## Padrões de Código

### Estilo de Código
- Siga o Google Java Style Guide
- Use 4 espaços para indentação
- Limite de 120 caracteres por linha
- UTF-8 encoding

### Convenções de Commit
Usamos [Conventional Commits](https://www.conventionalcommits.org/):

```bash
feat: adiciona novo componente X
fix: corrige problema em Y
docs: atualiza documentação de Z
test: adiciona testes para W
```

### Nomenclatura
- Classes: PascalCase
- Métodos: camelCase
- Constantes: UPPER_SNAKE_CASE
- Pacotes: lowercase

## Testes

### Estrutura de Testes
```java
class ExampleTest {
    @Test
    void shouldDoSomething() {
        // Arrange
        var input = ...;
        
        // Act
        var result = ...;
        
        // Assert
        assertThat(result).isEqualTo(expected);
    }
}
```

### Cobertura
- Mínimo de 80% de cobertura
- Testes unitários obrigatórios
- Testes de integração quando aplicável
- Testes de performance para componentes críticos

## Documentação

### JavaDoc
```java
/**
 * Executa uma operação específica.
 *
 * @param input entrada para a operação
 * @return resultado da operação
 * @throws OperationException se houver erro
 */
public Output execute(Input input) throws OperationException {
    // Implementação
}
```

### Markdown
- README para cada módulo
- Documentação técnica em `/docs`
- Exemplos práticos
- Diagramas quando necessário

## Pull Requests

### Template de PR
```markdown
## Descrição
Descreva as mudanças feitas

## Tipo de Mudança
- [ ] Bug fix
- [ ] Nova feature
- [ ] Breaking change
- [ ] Documentação

## Checklist
- [ ] Testes adicionados
- [ ] Documentação atualizada
- [ ] Build passa
- [ ] Lint passa
```

### Review Process
1. CI passa
2. Code review aprovado
3. Documentação atualizada
4. Conflitos resolvidos

## Links Úteis

- [Issues](https://github.com/archflow/archflow/issues)
- [Discussions](https://github.com/archflow/archflow/discussions)
- [Discord](https://discord.gg/archflow)
- [Documentation](https://docs.archflow.org)

## Suporte

Precisa de ajuda?
- Abra uma issue
- Participe do Discord
- Envie email para support@archflow.org

## Agradecimentos

Agradecemos sua contribuição para tornar o archflow melhor!