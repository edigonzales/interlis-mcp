package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Explicit geometry semantics for an attribute. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeometryTypeSpec {

  public enum Provider { INTERLIS, CHBASE }

  public enum InterlisKind {
    POLYLINE,
    SURFACE,
    AREA,
    MULTIPOLYLINE,
    MULTISURFACE,
    MULTIAREA
  }

  @JsonProperty(required = true)
  private Provider provider;
  @JsonProperty(required = false)
  private InterlisKind interlisKind;
  @JsonProperty(required = false)
  private String chBaseType;
  @JsonProperty(required = false)
  private String coordDomainFqn;
  @JsonProperty(required = false)
  private Boolean arcs;
  @JsonProperty(required = false)
  private BigDecimal overlapMm;
  @JsonProperty(required = false)
  private Boolean directed;

  public Provider getProvider() { return provider; }
  public void setProvider(Provider provider) { this.provider = provider; }
  public InterlisKind getInterlisKind() { return interlisKind; }
  public void setInterlisKind(InterlisKind interlisKind) { this.interlisKind = interlisKind; }
  public String getChBaseType() { return chBaseType; }
  public void setChBaseType(String chBaseType) { this.chBaseType = chBaseType; }
  public String getCoordDomainFqn() { return coordDomainFqn; }
  public void setCoordDomainFqn(String coordDomainFqn) { this.coordDomainFqn = coordDomainFqn; }
  public Boolean getArcs() { return arcs; }
  public void setArcs(Boolean arcs) { this.arcs = arcs; }
  public BigDecimal getOverlapMm() { return overlapMm; }
  public void setOverlapMm(BigDecimal overlapMm) { this.overlapMm = overlapMm; }
  public Boolean getDirected() { return directed; }
  public void setDirected(Boolean directed) { this.directed = directed; }
}
