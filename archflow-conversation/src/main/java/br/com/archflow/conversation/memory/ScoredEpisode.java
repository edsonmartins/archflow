package br.com.archflow.conversation.memory;

/**
 * An episode with its composite retrieval score.
 *
 * @param episode The episode
 * @param score The composite score (0..1)
 * @param similarityScore The semantic similarity component
 * @param recencyScore The recency component
 * @param importanceScore The importance component
 */
public record ScoredEpisode(
        Episode episode,
        double score,
        double similarityScore,
        double recencyScore,
        double importanceScore
) implements Comparable<ScoredEpisode> {

    @Override
    public int compareTo(ScoredEpisode other) {
        return Double.compare(other.score, this.score); // Descending
    }
}
