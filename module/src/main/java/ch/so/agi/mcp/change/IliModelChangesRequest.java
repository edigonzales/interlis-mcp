package ch.so.agi.mcp.change;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class IliModelChangesRequest {
  @JsonProperty(required = true)
  public List<IliModelChangeRequest> changes;

  @JsonProperty(required = false)
  public Boolean allowPotentiallyBreaking;

  public List<IliModelChangeRequest> requireChanges() {
    if (changes == null || changes.isEmpty()) {
      throw new IllegalArgumentException("request.changes must contain at least one operation.");
    }
    if (changes.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("request.changes must not contain null operations.");
    }
    return List.copyOf(changes);
  }

  public boolean allowsPotentiallyBreaking() {
    return Boolean.TRUE.equals(allowPotentiallyBreaking);
  }
}
