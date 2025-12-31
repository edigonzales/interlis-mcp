package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaseType {

  public enum Kind { TEXT, MTEXT, NUMERIC, NUM_RANGE, BOOLEAN, COORD, POLYLINE, SURFACE_SIMPLE }

  @JsonProperty(required = true)
  private Kind kind;
  @JsonProperty(required = false)
  private Integer length;
  @JsonProperty(required = false)
  private Double min;
  @JsonProperty(required = false)
  private Double max;

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = false)
  private String unitFqn;

  @Pattern(regexp = "^([A-Za-z][A-Za-z0-9_]*)(\\.[A-Za-z][A-Za-z0-9_]*)*$", message = "FQN must be dot-separated identifiers")
  @JsonProperty(required = false)
  private String refSysFqn;

  @JsonProperty(required = false)
  private Boolean circular;

  public Kind getKind() { return kind; }
  public void setKind(Kind kind) { this.kind = kind; }

  public Integer getLength() { return length; }
  public void setLength(Integer length) { this.length = length; }

  public Double getMin() { return min; }
  public void setMin(Double min) { this.min = min; }

  public Double getMax() { return max; }
  public void setMax(Double max) { this.max = max; }

  public String getUnitFqn() { return unitFqn; }
  public void setUnitFqn(String unitFqn) { this.unitFqn = unitFqn; }

  public String getRefSysFqn() { return refSysFqn; }
  public void setRefSysFqn(String refSysFqn) { this.refSysFqn = refSysFqn; }

  public Boolean getCircular() { return circular; }
  public void setCircular(Boolean circular) { this.circular = circular; }

  public void validate() {
    if (kind == null) throw new IllegalArgumentException("baseType.kind is required.");
    switch (kind) {
      case TEXT, MTEXT -> {
        if (length != null && length < 1) throw new IllegalArgumentException("TEXT requires 'length' >= 1.");
      }
      case NUMERIC -> {
        if (min != null || max != null) throw new IllegalArgumentException("NUMERIC must not define 'min'/'max'; use NUM_RANGE instead.");
      }
      case NUM_RANGE -> {
        if (min == null || max == null) throw new IllegalArgumentException("NUM_RANGE requires 'min' and 'max'.");
        if (!(min < max)) throw new IllegalArgumentException("NUM_RANGE requires min < max (got " + min + " .. " + max + ").");
        if (refSysFqn != null && !refSysFqn.isBlank()) throw new IllegalArgumentException("NUM_RANGE does not support 'refSysFqn'.");
        if (Boolean.TRUE.equals(circular)) throw new IllegalArgumentException("NUM_RANGE does not support 'circular'.");
      }
      case BOOLEAN, COORD, POLYLINE, SURFACE_SIMPLE -> { /* ok */ }
      default -> throw new IllegalArgumentException("Unsupported baseType.kind: " + kind);
    }
  }
}
