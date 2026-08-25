package ch.so.agi.mcp.model;

import ch.so.agi.mcp.util.NameValidator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaseType {

  public enum Kind { TEXT, MTEXT, NUMERIC, NUM_RANGE, BOOLEAN }

  @JsonProperty(required = true)
  private Kind kind;
  @JsonProperty(required = false)
  private Integer length;
  @JsonProperty(required = false)
  private BigDecimal min;
  @JsonProperty(required = false)
  private BigDecimal max;

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

  public BigDecimal getMin() { return min; }
  public void setMin(BigDecimal min) { this.min = min; }

  public BigDecimal getMax() { return max; }
  public void setMax(BigDecimal max) { this.max = max; }

  public String getUnitFqn() { return unitFqn; }
  public void setUnitFqn(String unitFqn) { this.unitFqn = unitFqn; }

  public String getRefSysFqn() { return refSysFqn; }
  public void setRefSysFqn(String refSysFqn) { this.refSysFqn = refSysFqn; }

  public Boolean getCircular() { return circular; }
  public void setCircular(Boolean circular) { this.circular = circular; }

  public void validate() {
    if (kind == null) throw new IllegalArgumentException("baseType.kind is required.");
    NameValidator validator = NameValidator.ascii();
    if (unitFqn != null && !unitFqn.isBlank()) {
      validator.validateFqn(unitFqn.trim(), "baseType.unitFqn");
    }
    if (refSysFqn != null && !refSysFqn.isBlank()) {
      validator.validateFqn(refSysFqn.trim(), "baseType.refSysFqn");
    }
    switch (kind) {
      case TEXT, MTEXT -> {
        if (length != null && length < 1) throw new IllegalArgumentException("TEXT requires 'length' >= 1.");
        rejectNumericOptions(kind.name());
      }
      case NUMERIC -> {
        if (length != null) throw new IllegalArgumentException("NUMERIC does not support 'length'.");
        if (min != null || max != null) throw new IllegalArgumentException("NUMERIC must not define 'min'/'max'; use NUM_RANGE instead.");
      }
      case NUM_RANGE -> {
        if (length != null) throw new IllegalArgumentException("NUM_RANGE does not support 'length'.");
        if (min == null || max == null) throw new IllegalArgumentException("NUM_RANGE requires 'min' and 'max'.");
        if (min.compareTo(max) >= 0) throw new IllegalArgumentException("NUM_RANGE requires min < max (got " + min + " .. " + max + ").");
        if (refSysFqn != null && !refSysFqn.isBlank()) throw new IllegalArgumentException("NUM_RANGE does not support 'refSysFqn'.");
        if (circular != null) throw new IllegalArgumentException("NUM_RANGE does not support 'circular'.");
      }
      case BOOLEAN -> rejectAllOptions(kind.name());
      default -> throw new IllegalArgumentException("Unsupported baseType.kind: " + kind);
    }
  }

  private void rejectNumericOptions(String typeName) {
    if (min != null || max != null || unitFqn != null || refSysFqn != null || circular != null) {
      throw new IllegalArgumentException(typeName + " only supports the optional 'length' field.");
    }
  }

  private void rejectAllOptions(String typeName) {
    if (length != null || min != null || max != null || unitFqn != null || refSysFqn != null || circular != null) {
      throw new IllegalArgumentException(typeName + " does not support additional baseType fields.");
    }
  }
}
