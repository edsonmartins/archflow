package br.com.archflow.orchestration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference "harness on the substrate" example (ADR-0002 P2) — a product-style
 * catalog de-para (e.g. mapping a supplier's product descriptions to an internal
 * SKU catalog) expressed as <b>fan-out → verify</b> over the generic
 * {@link Orchestrator} primitives.
 *
 * <p><b>The boundary it demonstrates (ADR-0001 / ADR-0002):</b>
 * <ul>
 *   <li><b>Substrate (archflow):</b> {@link Orchestrator#fanOut} (parallel,
 *       budget-bounded map) and {@link Orchestrator#verify} (adversarial,
 *       majority-vote confirmation) — fully generic.</li>
 *   <li><b>Harness (the product, e.g. gestor-rq / integrall):</b> the matching
 *       rule ({@link #jaccard} token similarity + thresholds) and what counts as
 *       a confident mapping. This is domain logic and stays in the product; only
 *       the orchestration mechanism comes from the framework.</li>
 * </ul>
 *
 * <p>No LLM is involved: a real product would swap the deterministic matcher for
 * pg_trgm / an embedding lookup / an LLM judge, but the <i>shape</i> — fan out the
 * source items to a worker, adversarially verify each candidate mapping, keep the
 * confirmed ones — is exactly this. Copy this class as a starting point.
 */
class CatalogDeparaHarnessExampleTest {

    // ── Domain types (the product's, not the framework's) ────────────────────
    record SourceItem(String code, String description) {}
    record Mapping(String sourceCode, String targetSku, double score) {}

    /** Internal catalog: SKU → normalized product name. */
    private static final Map<String, String> TARGET_CATALOG = Map.of(
            "SKU-A", "alpha beta gamma",
            "SKU-B", "delta epsilon");

    private static final double MATCH_THRESHOLD = 0.30;   // worker keeps a candidate
    private static final double CONFIDENCE_THRESHOLD = 0.50; // verifier confirms

    @Test
    void mapsSupplierCatalogViaFanOutThenVerify() {
        List<SourceItem> sources = List.of(
                new SourceItem("S1", "alpha beta gamma"),              // exact -> confirmed
                new SourceItem("S2", "delta epsilon zeta theta omega"), // weak match -> refuted by verify
                new SourceItem("S3", "nothing matches here"));          // no candidate -> worker failure

        Orchestrator orchestrator = new DefaultOrchestrator(4);

        // HARNESS: best-target matcher by Jaccard token similarity.
        Worker<SourceItem, Mapping> matcher = item -> {
            String bestSku = null;
            double bestScore = 0.0;
            for (Map.Entry<String, String> entry : TARGET_CATALOG.entrySet()) {
                double score = jaccard(item.description(), entry.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    bestSku = entry.getKey();
                }
            }
            return bestScore >= MATCH_THRESHOLD
                    ? Result.success(new Mapping(item.code(), bestSku, bestScore))
                    : Result.fail("no confident match for " + item.code());
        };

        // HARNESS: a candidate mapping is refuted if its confidence is too low.
        Voter<Mapping> confidenceVoter = (mapping, lens) -> mapping.score() < CONFIDENCE_THRESHOLD;

        // SUBSTRATE: fan out, then adversarially verify each successful candidate.
        List<Result<Mapping>> results = orchestrator.fanOut(sources, matcher, BudgetLedger.unlimited());

        Map<String, Mapping> confirmed = new LinkedHashMap<>();
        List<Mapping> rejected = new ArrayList<>();
        for (Result<Mapping> result : results) {
            if (!result.ok()) {
                continue; // unmatched source — left for human review in a real harness
            }
            Verdict verdict = orchestrator.verify(result.value(), confidenceVoter, VerifyPolicy.majority(3));
            if (verdict.confirmed()) {
                confirmed.put(result.value().sourceCode(), result.value());
            } else {
                rejected.add(result.value());
            }
        }

        // S1 confirmed to SKU-A; S2 matched-but-refuted; S3 never matched.
        assertThat(results).hasSize(3);
        assertThat(results.stream().filter(Result::ok).count()).isEqualTo(2);
        assertThat(confirmed).containsOnlyKeys("S1");
        assertThat(confirmed.get("S1").targetSku()).isEqualTo("SKU-A");
        assertThat(rejected).extracting(Mapping::sourceCode).containsExactly("S2");
    }

    /** Jaccard similarity over whitespace tokens — the product's match rule. */
    private static double jaccard(String a, String b) {
        Set<String> ta = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\s+")));
        Set<String> tb = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\s+")));
        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}
