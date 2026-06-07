package br.com.archflow.orchestration;

import java.util.List;

/**
 * Adversarial verification policy: run {@code voters} independent voters and
 * confirm a finding only if at least {@code minAgree} of them do NOT refute it.
 * {@code lenses} are distinct perspectives cycled across voters (e.g.
 * "correctness", "security"); empty means a single default lens.
 */
public record VerifyPolicy(int voters, int minAgree, List<String> lenses) {

    public VerifyPolicy {
        if (voters <= 0) {
            throw new IllegalArgumentException("voters must be > 0");
        }
        if (minAgree <= 0 || minAgree > voters) {
            throw new IllegalArgumentException("minAgree must be in 1..voters");
        }
        lenses = lenses == null ? List.of() : List.copyOf(lenses);
    }

    /** Majority of N voters, no specific lenses. */
    public static VerifyPolicy majority(int voters) {
        return new VerifyPolicy(voters, voters / 2 + 1, List.of());
    }

    String lensFor(int voterIndex) {
        return lenses.isEmpty() ? "default" : lenses.get(voterIndex % lenses.size());
    }
}
