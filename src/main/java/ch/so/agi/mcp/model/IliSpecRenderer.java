package ch.so.agi.mcp.model;

import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import ch.so.agi.mcp.tools.AttributeTools;
import ch.so.agi.mcp.tools.DomainTools;
import ch.so.agi.mcp.util.AnnotationRenderer;
import ch.so.agi.mcp.util.NameValidator;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Strict internal renderer. Public MCP tools expose specs, never these fragments. */
@Component
public final class IliSpecRenderer {

  private static final Pattern PATH = Pattern.compile(
      "^[A-Za-z][A-Za-z0-9_]*(?:(?:\\.|->)[A-Za-z][A-Za-z0-9_]*)*$");
  private static final Set<String> COMPARISON_OPERATORS =
      Set.of("==", "!=", "<", "<=", ">", ">=");

  private final AttributeTools attributeTools;
  private final DomainTools domainTools;

  public IliSpecRenderer(AttributeTools attributeTools, DomainTools domainTools) {
    this.attributeTools = attributeTools;
    this.domainTools = domainTools;
  }

  public RenderedModel renderModel(IliModelSpec spec) {
    require(spec != null, "spec is required.");
    String modelName = ident(spec.name, "spec.name");
    String iliVersion = iliVersion(spec.iliVersion);
    String uri = absoluteUri(spec.uri);
    String version = quotedValue(spec.version, "spec.version");
    String language = optionalLanguage(spec.language);

    LinkedHashSet<String> explicitImports = normalizedFqns(spec.imports, "spec.imports", false);
    LinkedHashSet<String> derivedImports = new LinkedHashSet<>();
    StringBuilder body = new StringBuilder();
    appendDefinitions(body, spec.units, spec.domains, iliVersion, modelName, derivedImports, "  ");
    for (IliModelSpec.TopicSpec topic : safe(spec.topics)) {
      appendSeparated(body, indent(
          renderTopic(topic, iliVersion, modelName, derivedImports).text(), "  "));
    }
    derivedImports.remove(modelName);
    derivedImports.remove("INTERLIS");

    LinkedHashSet<String> allImports = new LinkedHashSet<>(explicitImports);
    allImports.addAll(derivedImports);
    StringBuilder result = new StringBuilder();
    result.append("INTERLIS ").append(iliVersion).append(";\n\n")
        .append(AnnotationRenderer.renderAnnotations(spec.iliDoc, spec.metaAttributes))
        .append("MODEL ").append(modelName);
    if (language != null) {
      result.append(" (").append(language).append(")");
    }
    result.append(" AT \"").append(uri).append("\" VERSION \"").append(version).append("\" =\n");
    for (String imported : allImports) {
      result.append("  IMPORTS ").append(imported).append(";\n");
    }
    if (!allImports.isEmpty() && body.length() > 0) {
      result.append('\n');
    }
    result.append(body);
    if (body.length() > 0 && body.charAt(body.length() - 1) != '\n') {
      result.append('\n');
    }
    result.append("END ").append(modelName).append(".\n");
    return new RenderedModel(result.toString(), List.copyOf(derivedImports));
  }

  public RenderedFragment renderTopic(
      IliModelSpec.TopicSpec spec,
      String iliVersion,
      String currentModel,
      Set<String> derivedImports) {
    require(spec != null, "topic is required.");
    String name = ident(spec.name, "topic.name");
    StringBuilder result = new StringBuilder();
    result.append(AnnotationRenderer.renderAnnotations(spec.iliDoc, spec.metaAttributes))
        .append("TOPIC ").append(name);
    if (Boolean.TRUE.equals(spec.isAbstract)) {
      result.append(" (ABSTRACT)");
    }
    result.append(" =\n");
    if (spec.oidDomainFqn != null && !spec.oidDomainFqn.isBlank()) {
      String oid = fqn(spec.oidDomainFqn, "topic.oidDomainFqn");
      deriveFromFqn(oid, currentModel, derivedImports);
      result.append("  OID AS ").append(oid).append(";\n");
    }
    for (String dependency : safe(spec.dependsOn)) {
      String normalized = fqn(dependency, "topic.dependsOn");
      deriveFromFqn(normalized, currentModel, derivedImports);
      result.append("  DEPENDS ON ").append(normalized).append(";\n");
    }
    StringBuilder body = new StringBuilder();
    appendDefinitions(body, spec.units, spec.domains, iliVersion, currentModel, derivedImports, "  ");
    for (IliModelSpec.StructureSpec structure : safe(spec.structures)) {
      appendSeparated(body, indent(renderViewable(
          "STRUCTURE", structure, iliVersion, currentModel, derivedImports, null), "  "));
    }
    for (IliModelSpec.ClassSpec clazz : safe(spec.classes)) {
      appendSeparated(body, indent(renderViewable(
          "CLASS", clazz, iliVersion, currentModel, derivedImports, clazz.oidDomainFqn), "  "));
    }
    for (IliModelSpec.AssociationSpec association : safe(spec.associations)) {
      appendSeparated(body, indent(renderAssociation(
          association, iliVersion, currentModel, derivedImports).text(), "  "));
    }
    if (body.length() > 0 && result.charAt(result.length() - 1) != '\n') {
      result.append('\n');
    }
    result.append(body).append("END ").append(name).append(';');
    return new RenderedFragment(result.toString(), List.copyOf(derivedImports));
  }

  public RenderedFragment renderClass(
      IliModelSpec.ClassSpec spec, String iliVersion, String currentModel, Set<String> imports) {
    return new RenderedFragment(
        renderViewable("CLASS", spec, iliVersion, currentModel, imports, spec.oidDomainFqn),
        List.copyOf(imports));
  }

  public RenderedFragment renderStructure(
      IliModelSpec.StructureSpec spec, String iliVersion, String currentModel, Set<String> imports) {
    return new RenderedFragment(
        renderViewable("STRUCTURE", spec, iliVersion, currentModel, imports, null),
        List.copyOf(imports));
  }

  public RenderedFragment renderAssociation(
      IliModelSpec.AssociationSpec spec,
      String iliVersion,
      String currentModel,
      Set<String> imports) {
    require(spec != null, "association is required.");
    String name = ident(spec.name, "association.name");
    validateExtends(spec.extendsFqn, currentModel, imports, "association.extendsFqn");
    require(spec.roles != null && spec.roles.size() >= 2,
        "association.roles requires at least two roles.");
    LinkedHashSet<String> roleNames = new LinkedHashSet<>();
    StringBuilder result = new StringBuilder();
    result.append(AnnotationRenderer.renderAnnotations(spec.iliDoc, spec.metaAttributes))
        .append("ASSOCIATION ").append(name);
    if (Boolean.TRUE.equals(spec.isAbstract)) result.append(" (ABSTRACT)");
    if (spec.extendsFqn != null && !spec.extendsFqn.isBlank()) {
      result.append(" EXTENDS ").append(spec.extendsFqn.trim());
    }
    result.append(" =\n");
    for (int i = 0; i < spec.roles.size(); i++) {
      IliModelSpec.AssociationRoleSpec role = spec.roles.get(i);
      require(role != null, "association.roles[" + i + "] is required.");
      String roleName = ident(role.name, "association.roles[" + i + "].name");
      require(roleNames.add(roleName), "Duplicate association role name: " + roleName);
      String target = fqn(role.classFqn, "association.roles[" + i + "].classFqn");
      deriveFromFqn(target, currentModel, imports);
      result.append("  ").append(roleName);
      if (Boolean.TRUE.equals(role.external)) result.append(" (EXTERNAL)");
      result.append(" -- ").append(cardinality(role.cardinality, i)).append(' ')
          .append(target).append(";\n");
    }
    appendAttributesAndConstraints(result, spec.attributes, spec.constraints,
        iliVersion, currentModel, imports, "  ", true);
    result.append("END ").append(name).append(';');
    return new RenderedFragment(result.toString(), List.copyOf(imports));
  }

  public RenderedFragment renderUnit(IliModelSpec.UnitSpec spec) {
    require(spec != null, "unit is required.");
    Map<String, Object> rendered = domainTools.createUnit(
        spec.name, spec.factor, spec.baseUnitFqn, spec.iliDoc, spec.metaAttributes);
    return new RenderedFragment(String.valueOf(rendered.get("iliSnippet")), List.of());
  }

  public RenderedFragment renderDomain(IliModelSpec.DomainSpec spec) {
    require(spec != null && spec.kind != null, "domain.kind is required.");
    Map<String, Object> rendered = switch (spec.kind) {
      case NUMERIC -> domainTools.createNumericDomain(
          spec.name, spec.min, spec.max, spec.unitFqn, spec.iliDoc, spec.metaAttributes);
      case ENUM -> domainTools.createEnumDomain(
          spec.name, null, spec.enumItems, spec.iliDoc, spec.metaAttributes);
      case ENUM_TREE -> domainTools.createEnumTreeDomainSnippet(
          spec.name, spec.enumTreeItems, spec.iliDoc, spec.metaAttributes);
      case COORD -> domainTools.createCoordDomainSnippet(
          spec.name, spec.axes, spec.rotationFrom, spec.rotationTo, spec.iliDoc, spec.metaAttributes);
    };
    return new RenderedFragment(String.valueOf(rendered.get("iliSnippet")), List.of());
  }

  public RenderedAttribute renderAttribute(AttributeLineRequest attribute, String iliVersion) {
    AttributeTools.RenderedAttribute rendered = attributeTools.renderAttribute(attribute, iliVersion);
    return new RenderedAttribute(
        rendered.response().getIliSnippet(), rendered.requiredImports());
  }

  public RenderedAttribute renderAttribute(
      AttributeLineRequest attribute, String iliVersion, String currentModel) {
    RenderedAttribute rendered = renderAttribute(attribute, iliVersion);
    LinkedHashSet<String> imports = new LinkedHashSet<>(rendered.derivedImports());
    collectTypeImports(attribute.getTypeSpec(), currentModel, imports);
    return new RenderedAttribute(rendered.text(), List.copyOf(imports));
  }

  public RenderedFragment renderConstraint(
      IliConstraintSpec spec,
      String iliVersion,
      String currentModel,
      Set<String> imports) {
    require(spec != null, "constraint and kind are required.");
    String name = ident(spec.name, "constraint.name");
    ConstraintExpression.IliVersion version = expressionVersion(iliVersion);
    String annotation = constraintAnnotations(spec, name);
    String statement = switch (spec) {
      case IliConstraintSpec.Unique unique ->
          renderUnique(unique, version, currentModel, imports);
      case IliConstraintSpec.Mandatory mandatory -> "MANDATORY CONSTRAINT "
          + expression(mandatory.condition, version, currentModel, imports) + ";";
      case IliConstraintSpec.Existence existence ->
          renderExistence(existence, currentModel, imports);
      case IliConstraintSpec.Plausibility plausibility ->
          renderPlausibility(plausibility, version, currentModel, imports);
      case IliConstraintSpec.Set set -> renderSet(set, version, currentModel, imports);
    };
    return new RenderedFragment(annotation + statement, List.copyOf(imports));
  }

  public String renderExternalConstraintBlock(
      String contextFqn,
      IliConstraintSpec spec,
      String iliVersion,
      String currentModel,
      Set<String> imports) {
    String context = fqn(contextFqn, "constraint.contextFqn");
    RenderedFragment inline = renderConstraint(spec, iliVersion, currentModel, imports);
    return "CONSTRAINTS OF " + context + " =\n"
        + indent(inline.text(), "  ") + "\nEND;";
  }

  /** Canonical surface rendering used by kind-specific AST round-trip adapters. */
  public String renderExpression(
      IliConstraintSpec.ExpressionSpec expression,
      String iliVersion,
      String currentModel,
      Set<String> imports) {
    return expression(
        expression,
        expressionVersion(iliVersion),
        currentModel,
        imports == null ? new LinkedHashSet<>() : imports);
  }

  private String renderViewable(
      String keyword,
      IliModelSpec.ViewableSpec spec,
      String iliVersion,
      String currentModel,
      Set<String> imports,
      @Nullable String oidDomainFqn) {
    require(spec != null, keyword.toLowerCase(Locale.ROOT) + " is required.");
    String name = ident(spec.name, keyword.toLowerCase(Locale.ROOT) + ".name");
    validateExtends(spec.extendsFqn, currentModel, imports,
        keyword.toLowerCase(Locale.ROOT) + ".extendsFqn");
    StringBuilder result = new StringBuilder();
    result.append(AnnotationRenderer.renderAnnotations(spec.iliDoc, spec.metaAttributes))
        .append(keyword).append(' ').append(name);
    if (Boolean.TRUE.equals(spec.isAbstract)) result.append(" (ABSTRACT)");
    if (spec.extendsFqn != null && !spec.extendsFqn.isBlank()) {
      result.append(" EXTENDS ").append(spec.extendsFqn.trim());
    }
    result.append(" =\n");
    if (oidDomainFqn != null && !oidDomainFqn.isBlank()) {
      require("CLASS".equals(keyword), "Only CLASS may declare an OID domain.");
      String oid = fqn(oidDomainFqn, "class.oidDomainFqn");
      deriveFromFqn(oid, currentModel, imports);
      result.append("  OID AS ").append(oid).append(";\n");
    }
    appendAttributesAndConstraints(result, spec.attributes, spec.constraints,
        iliVersion, currentModel, imports, "  ", false);
    result.append("END ").append(name).append(';');
    return result.toString();
  }

  private void appendAttributesAndConstraints(
      StringBuilder result,
      @Nullable List<AttributeLineRequest> attributes,
      @Nullable List<IliConstraintSpec> constraints,
      String iliVersion,
      String currentModel,
      Set<String> imports,
      String indent,
      boolean association) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    List<AttributeLineRequest> attrs = safe(attributes);
    if (association && !attrs.isEmpty()) result.append(indent).append("ATTRIBUTE\n");
    String attributeIndent = association ? indent + "  " : indent;
    for (AttributeLineRequest attribute : attrs) {
      require(attribute != null, "attribute entry is required.");
      String name = ident(attribute.getName(), "attribute.name");
      require(names.add(name), "Duplicate attribute name: " + name);
      RenderedAttribute rendered = renderAttribute(attribute, iliVersion, currentModel);
      imports.addAll(rendered.derivedImports());
      result.append(indent(rendered.text(), attributeIndent)).append("\n");
    }
    for (IliConstraintSpec constraint : safe(constraints)) {
      RenderedFragment rendered = renderConstraint(
          constraint, iliVersion, currentModel, imports);
      result.append(indent(rendered.text(), indent)).append("\n");
    }
  }

  private void appendDefinitions(
      StringBuilder body,
      @Nullable List<IliModelSpec.UnitSpec> units,
      @Nullable List<IliModelSpec.DomainSpec> domains,
      String iliVersion,
      String currentModel,
      Set<String> imports,
      String indent) {
    for (IliModelSpec.UnitSpec unit : safe(units)) {
      appendSeparated(body, indent(renderUnit(unit).text(), indent));
      deriveFromFqn(unit.baseUnitFqn, currentModel, imports);
    }
    for (IliModelSpec.DomainSpec domain : safe(domains)) {
      appendSeparated(body, indent(renderDomain(domain).text(), indent));
      if (domain.unitFqn != null) deriveFromFqn(domain.unitFqn, currentModel, imports);
      for (DomainTools.CoordinateAxis axis : safe(domain.axes)) {
        if (axis != null) deriveFromFqn(axis.getUnitFqn(), currentModel, imports);
      }
    }
  }

  private String renderUnique(
      IliConstraintSpec.Unique spec,
      ConstraintExpression.IliVersion version,
      String currentModel,
      Set<String> imports) {
    require(spec.keyPaths != null && !spec.keyPaths.isEmpty(),
        "UNIQUE constraint requires keyPaths.");
    require(spec.scope != null,
        "UNIQUE constraint requires explicit uniqueScope GLOBAL, BASKET or LOCAL.");
    IliConstraintSpec.UniqueScope scope = spec.scope;
    List<String> paths = spec.keyPaths.stream()
        .map(path -> semanticPath(path, "constraint.keyPaths"))
        .toList();
    StringBuilder result = new StringBuilder("UNIQUE");
    if (scope == IliConstraintSpec.UniqueScope.BASKET) result.append(" (BASKET)");
    if (spec.where != null) {
      result.append(" WHERE ")
          .append(expression(spec.where, version, currentModel, imports)).append(" :");
    }
    if (scope == IliConstraintSpec.UniqueScope.LOCAL) {
      require(spec.localPrefix != null && !spec.localPrefix.isBlank(),
          "LOCAL UNIQUE requires localPrefix.");
      result.append(" (LOCAL) ")
          .append(semanticPath(spec.localPrefix, "constraint.localPrefix"))
          .append(" : ");
    } else {
      require(spec.localPrefix == null || spec.localPrefix.isBlank(),
          "GLOBAL/BASKET UNIQUE must not define localPrefix.");
      result.append(' ');
    }
    return result.append(String.join(", ", paths)).append(';').toString();
  }

  private String renderExistence(
      IliConstraintSpec.Existence spec, String currentModel, Set<String> imports) {
    String restricted = semanticPath(spec.restrictedPath, "constraint.restrictedPath");
    require(spec.requiredIn != null && !spec.requiredIn.isEmpty(),
        "EXISTENCE constraint requires requiredIn targets.");
    List<String> targets = new ArrayList<>();
    for (int i = 0; i < spec.requiredIn.size(); i++) {
      IliConstraintSpec.ExistenceTargetSpec target = spec.requiredIn.get(i);
      require(target != null, "constraint.requiredIn[" + i + "] is required.");
      String viewable = fqn(target.viewableFqn,
          "constraint.requiredIn[" + i + "].viewableFqn");
      deriveFromFqn(viewable, currentModel, imports);
      targets.add(viewable + " : "
          + semanticPath(target.attributePath,
              "constraint.requiredIn[" + i + "].attributePath"));
    }
    return "EXISTENCE CONSTRAINT " + restricted + " REQUIRED IN "
        + String.join(" OR ", targets) + ";";
  }

  private String renderPlausibility(
      IliConstraintSpec.Plausibility spec,
      ConstraintExpression.IliVersion version,
      String currentModel,
      Set<String> imports) {
    require(spec.direction != null, "PLAUSIBILITY constraint requires direction.");
    require(spec.percentage != null
        && spec.percentage.compareTo(BigDecimal.ZERO) >= 0
        && spec.percentage.compareTo(BigDecimal.valueOf(100)) <= 0,
        "PLAUSIBILITY percentage must be between 0 and 100.");
    String operator = spec.direction == IliConstraintSpec.PlausibilityDirection.AT_LEAST
        ? ">=" : "<=";
    return "CONSTRAINT " + operator + " "
        + spec.percentage.stripTrailingZeros().toPlainString() + "% "
        + expression(spec.condition, version, currentModel, imports) + ";";
  }

  private String renderSet(
      IliConstraintSpec.Set spec,
      ConstraintExpression.IliVersion version,
      String currentModel,
      Set<String> imports) {
    StringBuilder result = new StringBuilder("SET CONSTRAINT");
    require(spec.scope != null, "SET constraint requires explicit scope GLOBAL or BASKET.");
    if (spec.scope == IliConstraintSpec.SetScope.BASKET) result.append(" (BASKET)");
    if (spec.where != null) {
      result.append(" WHERE ")
          .append(expression(spec.where, version, currentModel, imports)).append(':');
    }
    require(spec.condition != null, "SET constraint requires condition.");
    String condition = switch (spec.condition) {
      case IliConstraintSpec.ObjectCountSetConditionSpec count -> {
        String operator = comparisonOperator(count.operator, "SET objectCount operator");
        require(count.threshold != null, "SET objectCount threshold is required.");
        yield "INTERLIS.objectCount(" + objectSet(count.objects) + ") "
            + operator + " " + count.threshold.stripTrailingZeros().toPlainString();
      }
      case IliConstraintSpec.BooleanSetConditionSpec expression ->
          expression(expression.expression, version, currentModel, imports);
    };
    if (condition.startsWith("(") && condition.endsWith(")")) {
      condition = condition.substring(1, condition.length() - 1);
    }
    return result.append(' ').append(condition).append(';').toString();
  }

  private String expression(
      IliConstraintSpec.ExpressionSpec spec,
      ConstraintExpression.IliVersion version,
      String currentModel,
      Set<String> imports) {
    require(spec != null && spec.kind != null, "constraint expression and kind are required.");
    List<IliConstraintSpec.ExpressionSpec> children = safe(spec.children);
    return switch (spec.kind) {
      case ATTRIBUTE -> {
        arity(spec.kind, children, 0, 0);
        yield ident(spec.name, "ATTRIBUTE.name");
      }
      case PATH -> {
        arity(spec.kind, children, 0, 0);
        yield semanticPath(spec.name, "PATH.name");
      }
      case NUMERIC -> {
        arity(spec.kind, children, 0, 0);
        require(spec.value != null, "NUMERIC.value is required.");
        try {
          yield new BigDecimal(String.valueOf(spec.value)).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
          throw new IllegalArgumentException("NUMERIC.value must be a number.");
        }
      }
      case BOOLEAN -> {
        arity(spec.kind, children, 0, 0);
        require(spec.value instanceof Boolean, "BOOLEAN.value must be true or false.");
        yield Boolean.TRUE.equals(spec.value) ? "#true" : "#false";
      }
      case ENUM -> {
        arity(spec.kind, children, 0, 0);
        String value = semanticPath(String.valueOf(spec.value), "ENUM.value");
        yield "#" + value;
      }
      case TEXT, MTEXT -> {
        arity(spec.kind, children, 0, 0);
        require(spec.value != null, spec.kind + ".value is required.");
        yield quote(String.valueOf(spec.value));
      }
      case DEFINED -> {
        arity(spec.kind, children, 1, 1);
        yield "DEFINED(" + expression(children.getFirst(), version, currentModel, imports) + ")";
      }
      case NOT -> {
        arity(spec.kind, children, 1, 1);
        yield "NOT(" + expression(children.getFirst(), version, currentModel, imports) + ")";
      }
      case AND, OR -> {
        arity(spec.kind, children, 1, Integer.MAX_VALUE);
        String delimiter = spec.kind == IliConstraintSpec.ExpressionKind.AND ? " AND " : " OR ";
        yield "(" + String.join(delimiter, children.stream()
            .map(child -> expression(child, version, currentModel, imports)).toList()) + ")";
      }
      case IMPLIES -> {
        arity(spec.kind, children, 2, 2);
        yield "(" + expression(children.get(0), version, currentModel, imports) + " IMPLIES "
            + expression(children.get(1), version, currentModel, imports) + ")";
      }
      case COMPARE -> {
        arity(spec.kind, children, 2, 2);
        String operator = spec.operator == null ? "" : spec.operator.trim();
        require(COMPARISON_OPERATORS.contains(operator),
            "COMPARE.operator is unsupported: " + operator);
        yield "(" + expression(children.get(0), version, currentModel, imports) + " " + operator + " "
            + expression(children.get(1), version, currentModel, imports) + ")";
      }
      case FUNCTION -> renderFunction(spec, children, version, currentModel, imports);
      case OBJECT_COUNT -> {
        arity(spec.kind, children, 0, 0);
        yield "INTERLIS.objectCount(" + objectSet(spec.objects) + ")";
      }
    };
  }

  private String renderFunction(
      IliConstraintSpec.ExpressionSpec spec,
      List<IliConstraintSpec.ExpressionSpec> children,
      ConstraintExpression.IliVersion version,
      String currentModel,
      Set<String> imports) {
    require(spec.functionOrigin != null, "FUNCTION.functionOrigin is required.");
    String functionName = requiredText(spec.name, "FUNCTION.name");
    if (spec.functionOrigin != IliConstraintSpec.FunctionOrigin.STANDARD) {
      String functionFqn = fqn(functionName, "FUNCTION.name");
      deriveFromFqn(functionFqn, currentModel, imports);
      return functionFqn + "(" + String.join(", ", children.stream()
          .map(child -> expression(child, version, currentModel, imports)).toList()) + ")";
    }
    String semanticId = functionName;
    StandardFunctionRegistry.StandardFunction function =
        StandardFunctionRegistry.findBySemanticId(semanticId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown standard function semanticId: " + semanticId));
    require(children.size() == function.parameters().size(),
        "FUNCTION " + semanticId + " expects " + function.parameters().size()
            + " children, got " + children.size() + ".");
    List<String> arguments = new ArrayList<>();
    for (int i = 0; i < children.size(); i++) {
      IliConstraintSpec.ExpressionSpec child = children.get(i);
      String rendered = expression(child, version, currentModel, imports);
      if (function.parameters().get(i).semantics()
          == ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH) {
        require(child.kind == IliConstraintSpec.ExpressionKind.PATH,
            "FUNCTION " + semanticId + " argument " + i + " requires PATH.");
        rendered = quote(rendered);
      }
      arguments.add(rendered);
    }
    ConstraintExpression.SurfaceSyntax syntax = function.definition().syntax(version);
    if (syntax instanceof ConstraintExpression.InfixSyntax infix) {
      require(arguments.size() == 2, "Infix function requires two arguments.");
      return "(" + arguments.get(0) + " " + infix.symbol() + " " + arguments.get(1) + ")";
    }
    imports.add(function.modelName(version));
    return function.qualifiedName(version) + "(" + String.join(", ", arguments) + ")";
  }

  private String objectSet(IliConstraintSpec.ObjectSetSpec spec) {
    require(spec != null, "object set is required.");
    return switch (spec) {
      case IliConstraintSpec.AllObjectsSpec ignored -> "ALL";
      case IliConstraintSpec.PathObjectsSpec path -> semanticPath(path.path, "objectSet.path");
    };
  }

  private String comparisonOperator(@Nullable String raw, String label) {
    String operator = raw == null ? "" : raw.trim();
    require(COMPARISON_OPERATORS.contains(operator), label + " is unsupported: " + operator);
    return operator;
  }

  private String constraintAnnotations(IliConstraintSpec spec, String name) {
    MetaAttributeSpec nameMeta = new MetaAttributeSpec();
    nameMeta.setName("name");
    nameMeta.setValue(name);
    List<MetaAttributeSpec> meta = AnnotationRenderer.mergeMetaAttributes(
        List.of(nameMeta), spec.metaAttributes);
    return AnnotationRenderer.renderAnnotations(spec.iliDoc, meta);
  }

  private void collectTypeImports(
      @Nullable TypeSpec type, String currentModel, Set<String> imports) {
    if (type == null) return;
    if (type.getDomainFqn() != null) deriveFromFqn(type.getDomainFqn(), currentModel, imports);
    if (type.getStructureFqn() != null) deriveFromFqn(type.getStructureFqn(), currentModel, imports);
    if (type.getReferenceType() != null) {
      deriveFromFqn(type.getReferenceType().getTargetClassFqn(), currentModel, imports);
    }
    if (type.getBasketType() != null) {
      deriveFromFqn(type.getBasketType().getTopicFqn(), currentModel, imports);
    }
    if (type.getObjectType() != null) {
      deriveFromFqn(type.getObjectType().getTargetClassFqn(), currentModel, imports);
    }
    if (type.getMetaobjectType() != null) {
      deriveFromFqn(type.getMetaobjectType().getTableFqn(), currentModel, imports);
    }
    if (type.getEnumTreeValueType() != null) {
      deriveFromFqn(type.getEnumTreeValueType().getEnumTreeDomainFqn(), currentModel, imports);
    }
    if (type.getBaseType() != null) {
      deriveFromFqn(type.getBaseType().getUnitFqn(), currentModel, imports);
      deriveFromFqn(type.getBaseType().getRefSysFqn(), currentModel, imports);
    }
  }

  private void validateExtends(
      @Nullable String extendsFqn, String currentModel, Set<String> imports, String label) {
    if (extendsFqn == null || extendsFqn.isBlank()) return;
    String normalized = fqn(extendsFqn, label);
    deriveFromFqn(normalized, currentModel, imports);
  }

  private String cardinality(IliModelSpec.CardinalitySpec spec, int roleIndex) {
    require(spec != null && spec.min != null && spec.max != null,
        "association.roles[" + roleIndex + "].cardinality requires min and max.");
    require(spec.min >= 0, "cardinality.min must be >= 0.");
    String max = spec.max.trim();
    require(max.equals("*") || max.matches("\\d+"),
        "cardinality.max must be a non-negative integer or '*'.");
    if (!max.equals("*")) {
      require(Integer.parseInt(max) >= spec.min, "cardinality.max must be >= min.");
    }
    return "{" + spec.min + ".." + max + "}";
  }

  private LinkedHashSet<String> normalizedFqns(
      @Nullable List<String> values, String label, boolean allowQualified) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : safe(values)) {
      String normalized = allowQualified ? fqn(value, label) : ident(value, label);
      require(result.add(normalized), "Duplicate value in " + label + ": " + normalized);
    }
    return result;
  }

  private void deriveFromFqn(@Nullable String value, String currentModel, Set<String> imports) {
    if (value == null || value.isBlank() || !value.contains(".")) return;
    String normalized = fqn(value, "referenced FQN");
    String model = normalized.substring(0, normalized.indexOf('.'));
    if (!model.equals(currentModel) && !model.equals("INTERLIS")) imports.add(model);
  }

  private String ident(@Nullable String value, String label) {
    String normalized = requiredText(value, label);
    NameValidator.ascii().validateIdent(normalized, label);
    return normalized;
  }

  private String fqn(@Nullable String value, String label) {
    String normalized = requiredText(value, label);
    NameValidator.ascii().validateFqn(normalized, label);
    return normalized;
  }

  private String semanticPath(@Nullable String value, String label) {
    String normalized = requiredText(value, label);
    require(PATH.matcher(normalized).matches(),
        label + " must be a dot/association-arrow separated path.");
    return normalized;
  }

  private String iliVersion(@Nullable String value) {
    String normalized = requiredText(value, "spec.iliVersion");
    require(normalized.equals("2.3") || normalized.equals("2.4"),
        "spec.iliVersion must be '2.3' or '2.4'.");
    return normalized;
  }

  private ConstraintExpression.IliVersion expressionVersion(String value) {
    return "2.4".equals(iliVersion(value))
        ? ConstraintExpression.IliVersion.ILI_24
        : ConstraintExpression.IliVersion.ILI_23;
  }

  private String absoluteUri(@Nullable String value) {
    String normalized = quotedValue(value, "spec.uri");
    try {
      require(URI.create(normalized).isAbsolute(), "spec.uri must be an absolute URI.");
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("spec.uri must be an absolute URI.");
    }
    return normalized;
  }

  private @Nullable String optionalLanguage(@Nullable String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    require(normalized.matches("[a-z]{2}"),
        "spec.language must be a two-letter lowercase language code.");
    return normalized;
  }

  private String quotedValue(@Nullable String value, String label) {
    String normalized = requiredText(value, label);
    require(!normalized.contains("\"") && !normalized.contains("\r") && !normalized.contains("\n"),
        label + " must not contain quotes or line breaks.");
    return normalized;
  }

  private String requiredText(@Nullable String value, String label) {
    require(value != null && !value.isBlank(), label + " is required.");
    return value.trim();
  }

  private String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n") + "\"";
  }

  private void arity(
      IliConstraintSpec.ExpressionKind kind,
      List<IliConstraintSpec.ExpressionSpec> children,
      int min,
      int max) {
    require(children.size() >= min && children.size() <= max,
        kind + " requires " + (min == max ? min : min + ".." + max) + " children.");
  }

  private String indent(String text, String indent) {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    return normalized.lines().map(line -> indent + line)
        .reduce((left, right) -> left + "\n" + right).orElse("");
  }

  private void appendSeparated(StringBuilder target, String text) {
    if (text == null || text.isBlank()) return;
    if (target.length() > 0 && target.charAt(target.length() - 1) != '\n') target.append('\n');
    if (target.length() > 0) target.append('\n');
    target.append(text).append('\n');
  }

  private <T> List<T> safe(@Nullable List<T> values) {
    return values == null ? List.of() : values;
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  public record RenderedModel(String modelText, List<String> derivedImports) {}
  public record RenderedFragment(String text, List<String> derivedImports) {}
  public record RenderedAttribute(String text, List<String> derivedImports) {}
}
