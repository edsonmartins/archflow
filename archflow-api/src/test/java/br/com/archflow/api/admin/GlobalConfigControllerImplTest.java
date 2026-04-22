package br.com.archflow.api.admin;

import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalConfigControllerImpl")
class GlobalConfigControllerImplTest {

    private GlobalConfigControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new GlobalConfigControllerImpl();
    }

    @Test
    @DisplayName("should export usage rows as csv")
    void shouldExportUsageRowsAsCsv() {
        String csv = controller.exportUsageCsv("2026-04");

        assertThat(csv).contains("tenantId,tenantName,executions,tokensInput,tokensOutput,estimatedCost,percentOfTotal,planLimit");
        assertThat(csv).contains("tenant_rio_quality,Rio Quality,8400");
        assertThat(csv.lines().count()).isGreaterThan(1);
    }
}
