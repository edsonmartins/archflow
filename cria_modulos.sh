#!/bin/bash

# Diretório base onde os módulos serão criados
BASE_DIR="archflow-langchain4j"

# Array com nomes dos módulos
MODULES=(
    "core"
    "model"
    "memory"
    "chain"
    "agent"
    "embedding"
    "document"
)

# Cria diretório base se não existir
mkdir -p $BASE_DIR
cd $BASE_DIR

# Cria parent POM
cat > pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.archflow</groupId>
        <artifactId>archflow-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>archflow-langchain4j</artifactId>
    <packaging>pom</packaging>

    <properties>
        <langchain4j.version>0.24.0</langchain4j.version>
        <openai.version>3.6.0</openai.version>
        <anthropic.version>2.0.0</anthropic.version>
    </properties>

    <modules>
EOF

# Adiciona módulos no parent POM
for module in "${MODULES[@]}"; do
    echo "        <module>archflow-langchain4j-${module}</module>" >> pom.xml
done

# Fecha o parent POM
cat >> pom.xml << 'EOF'
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j</artifactId>
                <version>${langchain4j.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
EOF

# Função para criar estrutura do módulo
create_module() {
    local module=$1
    local module_dir="archflow-langchain4j-${module}"

    echo "Criando módulo: $module_dir"

    # Cria estrutura de diretórios
    mkdir -p "$module_dir/src/main/java/br/com/archflow/langchain4j/$module"
    mkdir -p "$module_dir/src/test/java/br/com/archflow/langchain4j/$module"

    # Cria POM do módulo
    cat > "$module_dir/pom.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.archflow</groupId>
        <artifactId>archflow-langchain4j</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>archflow-langchain4j-${module}</artifactId>

    <dependencies>
        <!-- Core dependency -->
        <dependency>
            <groupId>br.com.archflow</groupId>
            <artifactId>archflow-langchain4j-core</artifactId>
            <version>\${project.version}</version>
        </dependency>

        <!-- LangChain4j -->
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j</artifactId>
        </dependency>

        <!-- Test dependencies -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
EOF

    # Cria package-info.java
    cat > "$module_dir/src/main/java/br/com/archflow/langchain4j/$module/package-info.java" << EOF
/**
 * Package for archflow-langchain4j-${module} adapters.
 */
package br.com.archflow.langchain4j.${module};
EOF

}

# Cria cada módulo
for module in "${MODULES[@]}"; do
    create_module "$module"
done

echo "Estrutura de módulos criada com sucesso!"
echo "Execute 'mvn install' no diretório $BASE_DIR para construir os módulos"