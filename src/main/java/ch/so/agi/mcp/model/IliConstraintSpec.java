package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Closed, discriminated authoring contract for the five INTERLIS constraint kinds.
 *
 * <p>The type discriminator is deliberately part of the JSON contract. It prevents combinations
 * such as EXISTENCE fields on a UNIQUE constraint from reaching a renderer.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IliConstraintSpec.Mandatory.class, name = "MANDATORY"),
    @JsonSubTypes.Type(value = IliConstraintSpec.Unique.class, name = "UNIQUE"),
    @JsonSubTypes.Type(value = IliConstraintSpec.Existence.class, name = "EXISTENCE"),
    @JsonSubTypes.Type(value = IliConstraintSpec.Plausibility.class, name = "PLAUSIBILITY"),
    @JsonSubTypes.Type(value = IliConstraintSpec.Set.class, name = "SET")
})
public sealed abstract class IliConstraintSpec
    permits IliConstraintSpec.Mandatory,
        IliConstraintSpec.Unique,
        IliConstraintSpec.Existence,
        IliConstraintSpec.Plausibility,
        IliConstraintSpec.Set {

  public enum Kind { MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY, SET }
  public enum UniqueScope { GLOBAL, BASKET, LOCAL }
  public enum SetScope { GLOBAL, BASKET }
  public enum PlausibilityDirection { AT_LEAST, AT_MOST }
  public enum FunctionOrigin { STANDARD, MODEL, VALIDATOR_EXTENSION }

  @JsonProperty(required = true) public String name;
  @JsonProperty(required = false) public @Nullable String iliDoc;
  @JsonProperty(required = false) public @Nullable List<MetaAttributeSpec> metaAttributes;

  public abstract Kind kind();

  /** True when proof would require semantics not executable from an INTERLIS declaration. */
  public final boolean hasExternalFunctionSemantics() {
    return switch (this) {
      case Mandatory mandatory -> hasExternalFunction(mandatory.condition);
      case Unique unique -> hasExternalFunction(unique.where);
      case Existence ignored -> false;
      case Plausibility plausibility -> hasExternalFunction(plausibility.condition);
      case Set set -> hasExternalFunction(set.where)
          || (set.condition instanceof BooleanSetConditionSpec bool
              && hasExternalFunction(bool.expression));
    };
  }

  private static boolean hasExternalFunction(@Nullable ExpressionSpec expression) {
    if (expression == null) return false;
    if (expression.kind == ExpressionKind.FUNCTION
        && expression.functionOrigin != FunctionOrigin.STANDARD) return true;
    return expression.children != null
        && expression.children.stream().anyMatch(IliConstraintSpec::hasExternalFunction);
  }

  public static final class Mandatory extends IliConstraintSpec {
    @JsonProperty(required = true) public ExpressionSpec condition;

    @Override
    public Kind kind() { return Kind.MANDATORY; }
  }

  public static final class Unique extends IliConstraintSpec {
    @JsonProperty(required = true) public UniqueScope scope;
    @JsonProperty(required = true) public List<String> keyPaths;
    @JsonProperty(required = false) public @Nullable String localPrefix;
    @JsonProperty(required = false) public @Nullable ExpressionSpec where;

    @Override
    public Kind kind() { return Kind.UNIQUE; }
  }

  public static final class Existence extends IliConstraintSpec {
    @JsonProperty(required = true) public String restrictedPath;
    @JsonProperty(required = true) public List<ExistenceTargetSpec> requiredIn;

    @Override
    public Kind kind() { return Kind.EXISTENCE; }
  }

  public static final class Plausibility extends IliConstraintSpec {
    @JsonProperty(required = true) public PlausibilityDirection direction;
    @JsonProperty(required = true) public BigDecimal percentage;
    @JsonProperty(required = true) public ExpressionSpec condition;

    @Override
    public Kind kind() { return Kind.PLAUSIBILITY; }
  }

  public static final class Set extends IliConstraintSpec {
    @JsonProperty(required = true) public SetScope scope;
    @JsonProperty(required = false) public @Nullable ExpressionSpec where;
    @JsonProperty(required = true) public SetConditionSpec condition;

    @Override
    public Kind kind() { return Kind.SET; }
  }

  public static final class ExistenceTargetSpec {
    @JsonProperty(required = true) public String viewableFqn;
    @JsonProperty(required = true) public String attributePath;
  }

  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.PROPERTY,
      property = "kind")
  @JsonSubTypes({
      @JsonSubTypes.Type(
          value = ObjectCountSetConditionSpec.class,
          name = "OBJECT_COUNT"),
      @JsonSubTypes.Type(
          value = BooleanSetConditionSpec.class,
          name = "BOOLEAN_EXPRESSION")
  })
  public sealed interface SetConditionSpec
      permits ObjectCountSetConditionSpec, BooleanSetConditionSpec {}

  public static final class ObjectCountSetConditionSpec implements SetConditionSpec {
    @JsonProperty(required = true) public ObjectSetSpec objects;
    @JsonProperty(required = true) public String operator;
    @JsonProperty(required = true) public BigDecimal threshold;
  }

  public static final class BooleanSetConditionSpec implements SetConditionSpec {
    @JsonProperty(required = true) public ExpressionSpec expression;
  }

  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.PROPERTY,
      property = "kind")
  @JsonSubTypes({
      @JsonSubTypes.Type(value = AllObjectsSpec.class, name = "ALL"),
      @JsonSubTypes.Type(value = PathObjectsSpec.class, name = "PATH")
  })
  public sealed interface ObjectSetSpec permits AllObjectsSpec, PathObjectsSpec {}

  public static final class AllObjectsSpec implements ObjectSetSpec {}

  public static final class PathObjectsSpec implements ObjectSetSpec {
    @JsonProperty(required = true) public String path;
  }

  public enum ExpressionKind {
    ATTRIBUTE,
    PATH,
    NUMERIC,
    BOOLEAN,
    ENUM,
    TEXT,
    MTEXT,
    FUNCTION,
    DEFINED,
    NOT,
    AND,
    OR,
    IMPLIES,
    COMPARE,
    OBJECT_COUNT
  }

  /** Recursive semantic expression; raw INTERLIS syntax is intentionally not accepted. */
  public static final class ExpressionSpec {
    @JsonProperty(required = true) public ExpressionKind kind;
    @JsonProperty(required = false) public @Nullable String name;
    @JsonProperty(required = false) public @Nullable String operator;
    @JsonProperty(required = false) public @Nullable Object value;
    @JsonProperty(required = false) public @Nullable FunctionOrigin functionOrigin;
    @JsonProperty(required = false) public @Nullable ObjectSetSpec objects;
    @JsonProperty(required = false) public @Nullable List<ExpressionSpec> children;
  }
}
