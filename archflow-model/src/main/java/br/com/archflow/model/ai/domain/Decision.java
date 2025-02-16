package br.com.archflow.model.ai.domain;

import java.util.List;

/**
 * Representa uma decisão tomada por um agente.
 */
public record Decision(
    String action,
    String reasoning,
    double confidence,
    List<String> alternatives
) {}