package br.com.archflow.langchain4j.core.filter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.ContainsString;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;

/**
 * In-memory evaluator for LangChain4j {@link Filter} expressions against
 * {@link Metadata} of a stored embedding.
 *
 * <p>Used by vector store adapters that cannot (or choose not to) translate
 * the {@link Filter} tree into a native query — the candidate's metadata is
 * evaluated in-process, after retrieval, guaranteeing that a
 * {@code request.filter()} is never silently ignored.
 *
 * <p>The evaluation itself delegates to {@link Filter#test(Object)}, which is
 * implemented by every filter shipped with LangChain4j. Before delegating,
 * the filter tree is validated against a whitelist of known filter types
 * ({@link IsEqualTo}, {@link IsNotEqualTo}, {@link IsGreaterThan},
 * {@link IsGreaterThanOrEqualTo}, {@link IsLessThan},
 * {@link IsLessThanOrEqualTo}, {@link IsIn}, {@link IsNotIn},
 * {@link ContainsString}, {@link And}, {@link Or}, {@link Not}); any other
 * (custom) {@link Filter} implementation causes a clear
 * {@link UnsupportedOperationException} instead of undefined behavior.
 */
public final class MetadataFilterEvaluator {

    private MetadataFilterEvaluator() {
    }

    /**
     * Tests whether the given metadata satisfies the given filter.
     *
     * @param filter   the filter to evaluate; {@code null} means "no filter" and always matches
     * @param metadata the metadata of the stored embedding; {@code null} is treated as empty metadata
     * @return {@code true} if the metadata satisfies the filter
     * @throws UnsupportedOperationException if the filter tree contains a filter type
     *                                       not shipped with LangChain4j
     */
    public static boolean matches(Filter filter, Metadata metadata) {
        if (filter == null) {
            return true;
        }
        ensureSupported(filter);
        return filter.test(metadata != null ? metadata : new Metadata());
    }

    /**
     * Recursively validates that every node of the filter tree is a known,
     * supported LangChain4j filter type.
     *
     * @throws UnsupportedOperationException for unknown filter implementations
     */
    public static void ensureSupported(Filter filter) {
        if (filter == null) {
            return;
        }
        if (filter instanceof And and) {
            ensureSupported(and.left());
            ensureSupported(and.right());
            return;
        }
        if (filter instanceof Or or) {
            ensureSupported(or.left());
            ensureSupported(or.right());
            return;
        }
        if (filter instanceof Not not) {
            ensureSupported(not.expression());
            return;
        }
        if (filter instanceof IsEqualTo
                || filter instanceof IsNotEqualTo
                || filter instanceof IsGreaterThan
                || filter instanceof IsGreaterThanOrEqualTo
                || filter instanceof IsLessThan
                || filter instanceof IsLessThanOrEqualTo
                || filter instanceof IsIn
                || filter instanceof IsNotIn
                || filter instanceof ContainsString) {
            return;
        }
        throw new UnsupportedOperationException(
                "Unsupported filter type: " + filter.getClass().getName()
                        + ". Supported types: IsEqualTo, IsNotEqualTo, IsGreaterThan, "
                        + "IsGreaterThanOrEqualTo, IsLessThan, IsLessThanOrEqualTo, IsIn, IsNotIn, "
                        + "ContainsString, And, Or, Not");
    }
}
