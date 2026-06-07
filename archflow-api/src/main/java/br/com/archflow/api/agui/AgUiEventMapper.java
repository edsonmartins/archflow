package br.com.archflow.api.agui;

import br.com.archflow.agent.streaming.ArchflowEvent;

import java.util.List;

/** Maps a native {@link ArchflowEvent} to zero-or-more AG-UI events (design-0006). */
public interface AgUiEventMapper {
    List<AgUiEvent> toAgUi(ArchflowEvent event);
}
