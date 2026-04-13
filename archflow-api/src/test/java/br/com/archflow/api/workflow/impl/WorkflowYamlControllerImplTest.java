package br.com.archflow.api.workflow.impl;

import br.com.archflow.api.workflow.WorkflowYamlDto;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.standalone.model.SerializableFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowYamlControllerImpl")
class WorkflowYamlControllerImplTest {

    @Mock
    FlowRepository repository;

    WorkflowYamlControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowYamlControllerImpl(repository);
    }

    private SerializableFlow sampleFlow(String id) {
        SerializableFlow flow = new SerializableFlow();
        flow.setId(id);
        flow.setMetadata(FlowMetadata.builder()
                .name("Sample")
                .version("1.0.0")
                .description("A sample workflow")
                .build());
        return flow;
    }

    @Test
    @DisplayName("constructor rejects null repository")
    void rejectsNullRepository() {
        assertThatThrownBy(() -> new WorkflowYamlControllerImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getYaml loads the flow and serializes it with the metadata version")
    void getYamlReturnsDto() {
        Flow flow = sampleFlow("wf-1");
        when(repository.findById("wf-1")).thenReturn(Optional.of(flow));

        WorkflowYamlDto dto = controller.getYaml("wf-1");

        assertThat(dto.id()).isEqualTo("wf-1");
        assertThat(dto.version()).isEqualTo("1.0.0");
        assertThat(dto.yaml()).contains("id: wf-1");
        assertThat(dto.yaml()).contains("name: Sample");
    }

    @Test
    @DisplayName("getYaml throws NoSuchElementException when the flow is missing")
    void getYamlMissing() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.getYaml("nope"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("updateYaml parses the body, saves it and returns the round-tripped DTO")
    void updateYamlHappyPath() {
        WorkflowYamlDto initial = new br.com.archflow.api.workflow.WorkflowYamlBridge()
                .toYaml(sampleFlow("wf-1"), "1.0.0");

        WorkflowYamlDto updated = controller.updateYaml("wf-1",
                new WorkflowYamlDto("wf-1", initial.yaml(), "1.0.0"));

        assertThat(updated.id()).isEqualTo("wf-1");
        assertThat(updated.yaml()).contains("id: wf-1");

        ArgumentCaptor<Flow> captor = ArgumentCaptor.forClass(Flow.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("wf-1");
    }

    @Test
    @DisplayName("updateYaml rejects a YAML body whose id does not match the URL")
    void updateYamlIdMismatch() {
        WorkflowYamlDto initial = new br.com.archflow.api.workflow.WorkflowYamlBridge()
                .toYaml(sampleFlow("wf-other"), "1.0.0");

        assertThatThrownBy(() -> controller.updateYaml("wf-1",
                new WorkflowYamlDto("wf-1", initial.yaml(), "1.0.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match URL id");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("updateYaml rejects a blank yaml body")
    void updateYamlBlankBody() {
        assertThatThrownBy(() -> controller.updateYaml("wf-1",
                new WorkflowYamlDto("wf-1", "", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateYaml rejects malformed yaml")
    void updateYamlMalformed() {
        assertThatThrownBy(() -> controller.updateYaml("wf-1",
                new WorkflowYamlDto("wf-1", "not: valid: yaml: :[]", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
