package br.com.archflow.api.workflow;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * YAML de workflow rejeitado, com uma mensagem para quem editou o texto — e não para quem escreveu o parser.
 *
 * <p>Antes a ponte devolvia {@code "Malformed YAML: " + e.getMessage()} e a aba Código mostrava o que o
 * Jackson diz a um desenvolvedor: a mensagem do SnakeYAML com o trecho do texto e quebras de linha, e no
 * erro de mapeamento a cadeia de referências com nomes de classes Java
 * ({@code br.com.archflow.standalone.model.SerializableFlow["steps"]->java.util.ArrayList[0]}) — RCHFLW-94.
 * Aqui a mensagem é montada a partir das partes estruturadas da exceção (problema + marca do SnakeYAML, caminho
 * de campos + tipo esperado do Jackson); o texto do Jackson nunca é repassado, porque é nele que os nomes de
 * classes moram.</p>
 *
 * <p>Continua sendo um {@link IllegalArgumentException} para que quem chama a ponte fora do Spring (e o
 * {@code GlobalExceptionHandler}) siga tratando como 400. {@link #getLine()}/{@link #getColumn()} são 1-based,
 * como o gutter do editor, e {@code null} quando o erro não tem posição (ex.: {@code id} ausente).</p>
 *
 * <p>{@link #getReason()} é só o problema, sem o prefixo em inglês nem a posição: a UI monta a frase no idioma
 * do usuário ("YAML inválido na linha 10, coluna 3: …") a partir de {@code line}/{@code column}/{@code reason}.
 * O próprio problema continua no texto do parser — não há como traduzir as frases do SnakeYAML.</p>
 */
public class InvalidYamlException extends IllegalArgumentException {

    private final String reason;
    private final Integer line;
    private final Integer column;

    public InvalidYamlException(String message, String reason, Integer line, Integer column, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.line = line;
        this.column = column;
    }

    /** O problema sem prefixo nem posição (ex.: {@code field 'metadata': Version is required}). */
    public String getReason() {
        return reason;
    }

    public Integer getLine() {
        return line;
    }

    public Integer getColumn() {
        return column;
    }

    /** Traduz a falha do parser/mapeador numa mensagem curta com linha/coluna. */
    public static InvalidYamlException from(IOException e) {
        MarkedYAMLException marcada = causaMarcada(e);
        if (marcada != null) {
            // A marca do problema aponta onde o parser desistiu; a do contexto, onde a estrutura começou.
            Mark marca = marcada.getProblemMark() != null ? marcada.getProblemMark() : marcada.getContextMark();
            String problema = umaLinha(marcada.getProblem());
            if (problema.isEmpty()) {
                problema = umaLinha(marcada.getContext());
            }
            Integer linha = marca != null ? marca.getLine() + 1 : null;
            Integer coluna = marca != null ? marca.getColumn() + 1 : null;
            return montar("Invalid YAML", problema.isEmpty() ? "syntax error" : problema, linha, coluna, e);
        }
        if (e instanceof JsonMappingException mapeamento) {
            Integer[] pos = posicao(mapeamento.getLocation());
            String campo = caminho(mapeamento.getPath());
            String motivo = motivoDoMapeamento(mapeamento);
            String detalhe = campo.isEmpty() ? motivo.replaceFirst("^:", "").trim() : "field '" + campo + "'" + motivo;
            return montar("Invalid workflow YAML", detalhe, pos[0], pos[1], e);
        }
        if (e instanceof JsonProcessingException processamento) {
            Integer[] pos = posicao(processamento.getLocation());
            // Erros de sintaxe do próprio Jackson (sem SnakeYAML por baixo) não citam classes; só a 1ª linha.
            String motivo = umaLinha(processamento.getOriginalMessage());
            return montar("Invalid YAML", motivo.isEmpty() ? "syntax error" : motivo, pos[0], pos[1], e);
        }
        String motivo = umaLinha(e.getMessage());
        return montar("Invalid YAML", motivo.isEmpty() ? "syntax error" : motivo, null, null, e);
    }

    private static InvalidYamlException montar(String prefixo, String motivo, Integer linha, Integer coluna,
                                               IOException e) {
        return new InvalidYamlException(texto(prefixo, linha, coluna, motivo), motivo, linha, coluna, e);
    }

    private static MarkedYAMLException causaMarcada(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof MarkedYAMLException m) {
                return m;
            }
        }
        return null;
    }

    private static String texto(String prefixo, Integer linha, Integer coluna, String detalhe) {
        if (linha == null) {
            return prefixo + ": " + detalhe;
        }
        return prefixo + " at line " + linha + (coluna != null ? ", column " + coluna : "") + ": " + detalhe;
    }

    private static Integer[] posicao(JsonLocation local) {
        if (local == null || local.getLineNr() < 1) {
            return new Integer[] {null, null};
        }
        return new Integer[] {local.getLineNr(), local.getColumnNr() >= 1 ? local.getColumnNr() : null};
    }

    /** {@code steps[0].type} — o caminho como o usuário escreve no YAML, sem os tipos Java de cada nível. */
    private static String caminho(List<JsonMappingException.Reference> referencias) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : referencias) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    /** O motivo já com o separador inicial (" has ..." / ": ..."), para seguir o nome do campo. */
    private static String motivoDoMapeamento(JsonMappingException e) {
        if (e instanceof InvalidFormatException formato && formato.getValue() != null) {
            String esperado = esperado(formato.getTargetType());
            return " has an invalid value '" + umaLinha(String.valueOf(formato.getValue())) + "'"
                    + (esperado != null ? " (expected " + esperado + ")" : "");
        }
        if (e instanceof MismatchedInputException entrada) {
            String esperado = esperado(entrada.getTargetType());
            return esperado != null ? " must be " + esperado : " has an unexpected value";
        }
        // O modelo validou o valor no próprio construtor (ex.: FlowMetadata exige version) e o Jackson embrulhou
        // o IllegalArgumentException. A frase do modelo é texto de domínio ("Version is required"), sem nomes de
        // classes, e é ela que diz o que falta; sem isso o usuário só leria "has an invalid value". Outras causas
        // (NPE, ISE de bibliotecas) podem trazer detalhe de implementação e continuam com a frase genérica.
        if (e instanceof ValueInstantiationException instanciacao
                && instanciacao.getCause() instanceof IllegalArgumentException causa) {
            String motivo = umaLinha(causa.getMessage());
            if (!motivo.isEmpty()) {
                return ": " + motivo;
            }
        }
        return " has an invalid value";
    }

    /** O tipo esperado em palavras de YAML; nunca o nome da classe. */
    private static String esperado(Class<?> tipo) {
        if (tipo == null) {
            return null;
        }
        if (tipo.isArray() || Collection.class.isAssignableFrom(tipo)) {
            return "a list";
        }
        if (CharSequence.class.isAssignableFrom(tipo) || tipo == Character.class || tipo == char.class) {
            return "text";
        }
        if (tipo == Boolean.class || tipo == boolean.class) {
            return "true or false";
        }
        if (Number.class.isAssignableFrom(tipo) || (tipo.isPrimitive() && tipo != void.class)) {
            return "a number";
        }
        if (tipo.isEnum()) {
            Object[] valores = tipo.getEnumConstants();
            StringBuilder sb = new StringBuilder("one of ");
            for (int i = 0; i < valores.length; i++) {
                sb.append(i == 0 ? "" : ", ").append(valores[i]);
            }
            return sb.toString();
        }
        if (Map.class.isAssignableFrom(tipo) || !tipo.getName().startsWith("java.")) {
            return "a mapping (key: value)";
        }
        return null;
    }

    private static String umaLinha(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
