package br.com.archflow.api.agui;

import com.fasterxml.jackson.annotation.JsonAnyGetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One AG-UI protocol event (design-0006). Serializes to {@code {"type": "...", ...fields}}
 * — the AG-UI wire format. Hand-rolled (no SDK dependency): the
 * {@link AgUiEventMapper} is the only AG-UI-coupled seam, so swapping to the
 * official {@code com.ag-ui:core} later touches only this package.
 */
public final class AgUiEvent {

    private final String type;
    private final Map<String, Object> fields;

    private AgUiEvent(String type, Map<String, Object> fields) {
        this.type = type;
        this.fields = fields == null ? Map.of() : fields;
    }

    /** {@code type} is the AG-UI event name, e.g. RUN_STARTED, TEXT_MESSAGE_CHUNK. */
    public static AgUiEvent of(String type, Map<String, Object> fields) {
        return new AgUiEvent(type, fields);
    }

    public static AgUiEvent of(String type) {
        return new AgUiEvent(type, Map.of());
    }

    public String getType() {
        return type;
    }

    /** Spreads the event-specific fields at the JSON top level. */
    @JsonAnyGetter
    public Map<String, Object> getFields() {
        return fields;
    }

    /** Small helper to build the fields map preserving insertion order. */
    static Map<String, Object> fields(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] != null && kv[i + 1] != null) {
                map.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return map;
    }
}
