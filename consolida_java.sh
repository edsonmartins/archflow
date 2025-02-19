#!/bin/bash

# Verifica se o diretório foi fornecido
if [ -z "$1" ]; then
    echo "Uso: $0 <diretório>"
    echo "Exemplo: $0 ./src"
    exit 1
fi

# Configurações
DIRETORIO="$1"
ARQUIVO_SAIDA="CodigosJava.txt"
EXTENSAO="java"

# Cria/cria o arquivo de saída
echo "=== CONSOLIDADO DE CÓDIGOS JAVA ===" > "$ARQUIVO_SAIDA"
echo "Gerado em: $(date)" >> "$ARQUIVO_SAIDA"
echo "Diretório base: $DIRETORIO" >> "$ARQUIVO_SAIDA"
echo "===================================" >> "$ARQUIVO_SAIDA"
echo "" >> "$ARQUIVO_SAIDA"

# Encontra e processa arquivos .java
find "$DIRETORIO" -type f -name "*.$EXTENSAO" -not -path '*/.*' | while read -r arquivo; do
    echo "Processando: $arquivo"
    echo "" >> "$ARQUIVO_SAIDA"
    echo "// ==================================================" >> "$ARQUIVO_SAIDA"
    echo "// Arquivo: ${arquivo#$DIRETORIO/}" >> "$ARQUIVO_SAIDA"
    echo "// ==================================================" >> "$ARQUIVO_SAIDA"
    echo "" >> "$ARQUIVO_SAIDA"
    cat "$arquivo" >> "$ARQUIVO_SAIDA"
    echo "" >> "$ARQUIVO_SAIDA"
done

echo "Consolidação concluída! Arquivo gerado: $ARQUIVO_SAIDA"
