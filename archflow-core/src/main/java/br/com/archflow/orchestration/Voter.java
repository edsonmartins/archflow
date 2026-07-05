package br.com.archflow.orchestration;

/**
 * A single adversarial verifier: given a finding and a lens (perspective),
 * returns {@code true} if it REFUTES the finding. Implementations capture the
 * model/context in their closure.
 */
@FunctionalInterface
public interface Voter<F> {
    boolean refutes(F finding, String lens);
}
