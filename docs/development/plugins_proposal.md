# Estrutura Maven para Plugins archflow

## 1. Estrutura Proposta

```
archflow/
├── pom.xml
├── archflow-core/
├── archflow-model/
├── archflow-plugin-api/
├── archflow-plugin-loader/
├── archflow-plugins/                  # Novo módulo pai para plugins
│   ├── pom.xml                       # Parent POM para plugins
│   │
│   ├── archflow-plugin-langchain/    # Módulo para plugins LangChain4j
│   │   ├── pom.xml
│   │   ├── archflow-plugin-calculator/
│   │   ├── archflow-plugin-websearch/
│   │   └── archflow-plugin-rag/
│   │
│   ├── archflow-plugin-tools/        # Módulo para ferramentas gerais
│   │   ├── pom.xml
│   │   ├── archflow-plugin-pdf/
│   │   ├── archflow-plugin-csv/
│   │   └── archflow-plugin-json/
│   │
│   ├── archflow-plugin-assistants/   # Módulo para assistentes
│   │   ├── pom.xml
│   │   ├── archflow-plugin-support/
│   │   └── archflow-plugin-writing/
│   │
│   ├── archflow-plugin-agents/       # Módulo para agentes
│   │   ├── pom.xml
│   │   ├── archflow-plugin-research/
│   │   └── archflow-plugin-analysis/
│   │
│   └── archflow-plugins-dist/        # Módulo de distribuição
       ├── pom.xml
       └── src/assembly/
```

## 2. Configuração Maven

### 2.1 Parent POM (archflow-plugins/pom.xml)
```xml
<project>
    <groupId>br.com.archflow.plugins</groupId>
    <artifactId>archflow-plugins</artifactId>
    <packaging>pom</packaging>
    
    <modules>
        <module>archflow-plugin-langchain</module>
        <module>archflow-plugin-tools</module>
        <module>archflow-plugin-assistants</module>
        <module>archflow-plugin-agents</module>
        <module>archflow-plugins-dist</module>
    </modules>

    <properties>
        <plugin.packaging>jar</plugin.packaging>
        <plugin.api.version>${project.version}</plugin.api.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>br.com.archflow</groupId>
            <artifactId>archflow-plugin-api</artifactId>
            <version>${plugin.api.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Plugin-Id>${plugin.id}</Plugin-Id>
                            <Plugin-Version>${project.version}</Plugin-Version>
                            <Plugin-Provider>${project.groupId}</Plugin-Provider>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2.2 Plugin Exemplo (archflow-plugin-calculator/pom.xml)
```xml
<project>
    <parent>
        <groupId>br.com.archflow.plugins</groupId>
        <artifactId>archflow-plugin-langchain</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>archflow-plugin-calculator</artifactId>
    <name>Calculator Plugin</name>

    <properties>
        <plugin.id>calculator</plugin.id>
    </properties>

    <dependencies>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 2.3 Distribuição (archflow-plugins-dist/pom.xml)
```xml
<project>
    <artifactId>archflow-plugins-dist</artifactId>
    <packaging>pom</packaging>

    <dependencies>
        <!-- Dependências para todos os plugins -->
        <dependency>
            <groupId>br.com.archflow.plugins</groupId>
            <artifactId>archflow-plugin-calculator</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- ... outros plugins ... -->
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <artifactId>maven-assembly-plugin</artifactId>
                <configuration>
                    <descriptors>
                        <descriptor>src/assembly/plugins.xml</descriptor>
                    </descriptors>
                </configuration>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>single</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2.4 Assembly (archflow-plugins-dist/src/assembly/plugins.xml)
```xml
<assembly>
    <id>plugins</id>
    <formats>
        <format>zip</format>
    </formats>
    <includeBaseDirectory>false</includeBaseDirectory>
    
    <dependencySets>
        <dependencySet>
            <outputDirectory>plugins</outputDirectory>
            <includes>
                <include>br.com.archflow.plugins:*</include>
            </includes>
            <useProjectArtifact>false</useProjectArtifact>
        </dependencySet>
    </dependencySets>
</assembly>
```

## 3. Organização dos Plugins

### 3.1 Por Categoria
- **langchain**: Plugins que integram funcionalidades LangChain4j
- **tools**: Ferramentas de propósito geral
- **assistants**: Assistentes especializados
- **agents**: Agentes autônomos

### 3.2 Convenções
- Cada plugin em seu próprio módulo
- Nomenclatura consistente (archflow-plugin-*)
- Metadados no MANIFEST.MF
- Documentação no README.md

### 3.3 Dependências
- Dependências comuns no parent POM
- Dependências específicas em cada plugin
- Escopo provided para API do archflow
- Gestão de versões centralizada

## 4. Processo de Build

### 4.1 Build Individual
```bash
# Build de um plugin específico
mvn clean install -pl archflow-plugins/archflow-plugin-langchain/archflow-plugin-calculator
```

### 4.2 Build Completo
```bash
# Build de todos os plugins
mvn clean install -pl archflow-plugins
```

### 4.3 Geração da Distribuição
```bash
# Gera o pacote de distribuição
mvn clean package -pl archflow-plugins-dist
```

## 5. Desenvolvimento de Plugins

### 5.1 Novo Plugin
1. Criar módulo Maven
2. Configurar POM
3. Implementar interfaces
4. Adicionar testes
5. Incluir na distribuição

### 5.2 Estrutura Recomendada
```
plugin-name/
├── pom.xml
├── README.md
├── src/
│   ├── main/java/
│   │   └── br/com/archflow/plugins/
│   │       └── plugin/
│   ├── main/resources/
│   │   └── META-INF/
│   └── test/java/
```

## 6. Próximos Passos

1. **Implementação**
    - Criar estrutura base
    - Migrar plugins existentes
    - Configurar builds

2. **Documentação**
    - Guias de desenvolvimento
    - Exemplos de plugins
    - Documentação de build

3. **Automação**
    - Scripts de build
    - Testes automatizados
    - Deployment automatizado