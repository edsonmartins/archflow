package br.com.archflow.dsl;

import java.util.List;

/**
 * O código Java gerado a partir de um documento, e o que ficou de fora.
 *
 * <p><b>Por que {@link #unrepresented()} existe.</b> Nem todo documento é
 * expressável na DSL: um fluxo vindo do servidor carrega campos de
 * infraestrutura ({@code status}, {@code updatedAt}), e um documento escrito à
 * mão pode ter chaves que a DSL não modela. Gerar código que descarta isso em
 * silêncio produziria um arquivo Java que <i>parece</i> equivalente ao original
 * e não é — o pior resultado possível para uma ferramenta de migração, porque o
 * usuário só descobre a perda depois de apagar a fonte.
 *
 * <p>Então o gerador declara o que não soube representar, e
 * {@link #isLossless()} responde à única pergunta que importa antes de trocar o
 * JSON pelo código: dá para jogar o original fora?
 *
 * @param source        o código Java
 * @param packageName   pacote da classe gerada, ou {@code null} para o default
 * @param className     nome simples da classe gerada
 * @param unrepresented descrições do que o código não reproduz
 * @since 1.1.0
 */
public record GeneratedCode(String source, String packageName, String className,
                            List<String> unrepresented) {

    public GeneratedCode {
        unrepresented = List.copyOf(unrepresented);
    }

    /**
     * Nome qualificado — o que {@code Class.forName} e um classloader esperam.
     * O nome simples não basta assim que há pacote.
     */
    public String qualifiedName() {
        return packageName == null || packageName.isBlank() ? className : packageName + "." + className;
    }

    /**
     * Caminho relativo do arquivo-fonte, com os diretórios do pacote:
     * {@code com/acme/flows/RagDocQaFlow.java}.
     */
    public String relativePath() {
        String dir = packageName == null || packageName.isBlank()
                ? "" : packageName.replace('.', '/') + "/";
        return dir + className + ".java";
    }

    /**
     * {@code true} quando o código gerado reproduz o documento inteiro — e,
     * portanto, quando é seguro passar a tratá-lo como a fonte da verdade.
     */
    public boolean isLossless() {
        return unrepresented.isEmpty();
    }
}
