package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.View;
import ch.interlis.ili2c.metamodel.Viewable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Bounded, breadth-first discovery of declared paths, with the compiler as authority. */
public final class ConstraintPathSearch {
  public static final int MAX_PREFIXES = 2_000;
  private ConstraintPathSearch() {}

  public static int depth(@Nullable Integer value) { return bounded(value, 3, 8, "maxDepth"); }
  public static int limit(@Nullable Integer value) { return bounded(value, 10, 50, "limit"); }

  private static int bounded(@Nullable Integer value, int fallback, int max, String field) {
    if (value == null) return fallback;
    if (value < 1 || value > max) throw new IllegalArgumentException(field + " must be between 1 and " + max + ".");
    return value;
  }

  public static Map<String, Object> search(TransferDescription td, String context, String targetFqn,
      int maxDepth, int limit) {
    depth(maxDepth);
    limit(limit);
    if (!(td.getElement(context) instanceof Viewable<?> root)) {
      return unavailable("CONTEXT_NOT_VIEWABLE", "Unknown or non-viewable context: " + context);
    }
    Target target = target(td, targetFqn);
    if (target == null) return unavailable("TARGET_NOT_FOUND_OR_UNSUPPORTED",
        "targetFqn must identify an attribute or viewable object type: " + targetFqn);

    var pending = new TreeSet<Prefix>(Comparator.comparingInt(Prefix::depth).thenComparing(Prefix::path));
    var members = new HashMap<Viewable<?>, List<Map<String, Object>>>();
    var reasons = new LinkedHashSet<String>();
    var unsupported = new ArrayList<Map<String, Object>>();
    var paths = new ArrayList<Map<String, Object>>();
    int examined = 0, unsupportedCount = 0;
    boolean discarded = enqueue(pending, members, root, "", 0);
    if (root instanceof View) reasons.add("VIEW_BASE_ALIASES_NOT_SEARCHED");
    while (!pending.isEmpty() && examined < MAX_PREFIXES) {
      Prefix prefix = pending.pollFirst();
      examined++;
      ObjectPath parsed;
      try {
        parsed = ConstraintPathAnalysis.parse(td, root, prefix.path());
      } catch (ch.interlis.ili2c.Ili2cException | IllegalArgumentException ex) {
        unsupportedCount++;
        reasons.add("PARSER_REJECTED_PREFIX");
        if (unsupported.size() < 20) unsupported.add(Map.of("path", prefix.path(), "reasonCode", "PARSER_REJECTED_PREFIX"));
        continue;
      }
      var shapeLimits = ConstraintPathAnalysis.unsupported(parsed.getLastPathEl());
      if (!shapeLimits.isEmpty()) {
        unsupportedCount++;
        reasons.addAll(shapeLimits);
        if (unsupported.size() < 20) unsupported.add(Map.of("path", prefix.path(), "reasonCodes", shapeLimits));
        continue;
      }
      if (matches(target, parsed, prefix.owner())) {
        // Look for an actual additional result before reporting a result truncation.
        if (paths.size() == limit) {
          reasons.add("RESULT_LIMIT_REACHED");
          break;
        }
        paths.add(ConstraintPathAnalysis.describe(parsed));
      }
      Viewable<?> next = ConstraintPathAnalysis.navigationTarget(parsed.getLastPathEl());
      if (next != null) {
        if (next instanceof View) reasons.add("VIEW_BASE_ALIASES_NOT_SEARCHED");
        if (prefix.depth() < maxDepth) {
          discarded |= enqueue(pending, members, next, prefix.path(), prefix.depth());
        } else if (!members.computeIfAbsent(next, ConstraintPathAnalysis::candidates).isEmpty()) {
          reasons.add("DEPTH_LIMIT_REACHED");
        }
      }
    }
    if (examined == MAX_PREFIXES && (!pending.isEmpty() || discarded)) reasons.add("SEARCH_BUDGET_EXCEEDED");
    var result = new LinkedHashMap<String, Object>();
    result.put("status", "AVAILABLE");
    result.put("context", root.getScopedName());
    result.put("targetFqn", targetFqn);
    result.put("resolvedTargetFqn", target.element().getScopedName());
    result.put("paths", paths);
    result.put("search", Map.of("maxDepth", maxDepth, "limit", limit, "maxPrefixes", MAX_PREFIXES,
        "examinedPrefixes", examined, "completeWithinBounds", reasons.stream().allMatch("DEPTH_LIMIT_REACHED"::equals),
        "truncated", !reasons.isEmpty(), "reasonCodes", List.copyOf(reasons),
        "unsupportedCount", unsupportedCount, "unsupportedExamples", unsupported));
    result.put("limitations", List.of(
        "Declared plain -> paths only; no downcasts, subclass-only members, view base aliases or indexed structure navigation.",
        "Object targets match the declared destination exactly; inherited attributes use the effective declaration in the requested owner.",
        "No paths is not a proof of unreachability. completeWithinBounds applies only to the supported search shape and depth.",
        "Usage hints are type/cardinality guidance, not a proof. Use authoring and validator tests for the complete expression."));
    return result;
  }

  /** Keep only the earliest prefixes that could still fit the execution budget. */
  private static boolean enqueue(TreeSet<Prefix> pending, Map<Viewable<?>, List<Map<String, Object>>> members,
      Viewable<?> owner, String path, int depth) {
    boolean discarded = false;
    for (var member : members.computeIfAbsent(owner, ConstraintPathAnalysis::candidates)) {
      String name = (String) member.get("name");
      var prefix = new Prefix(path.isEmpty() ? name : path + "->" + name, depth + 1, owner);
      // Members are sorted; later siblings cannot enter the retained earliest prefix window.
      if (pending.size() == MAX_PREFIXES + 1 && pending.comparator().compare(prefix, pending.last()) > 0) {
        return true;
      }
      pending.add(prefix);
      if (pending.size() > MAX_PREFIXES + 1) {
        pending.pollLast();
        discarded = true;
      }
    }
    return discarded;
  }

  private static @Nullable Target target(TransferDescription td, String fqn) {
    Element exact = td.getElement(fqn);
    if (exact instanceof Viewable<?> viewable) return new Target(viewable, null);
    int separator = fqn.lastIndexOf('.');
    if (separator > 0 && td.getElement(fqn.substring(0, separator)) instanceof Viewable<?> owner) {
      AttributeDef attribute = owner.findAttribute(fqn.substring(separator + 1));
      if (attribute != null) return new Target(attribute, owner);
    }
    return null;
  }

  private static boolean matches(Target target, ObjectPath parsed, Viewable<?> owner) {
    if (target.element() instanceof AttributeDef attribute) {
      // An inherited FQN such as Child.value must not accidentally match a sibling's value.
      return ConstraintPathAnalysis.attribute(parsed.getLastPathEl()) == attribute
          && (owner == target.owner() || owner.isExtending(target.owner()));
    }
    return ConstraintPathAnalysis.navigationTarget(parsed.getLastPathEl()) == target.element();
  }

  public static Map<String, Object> unavailable(String code, String message) {
    return Map.of("status", "UNAVAILABLE", "reasonCode", code, "message", message, "paths", List.of());
  }

  private record Prefix(String path, int depth, Viewable<?> owner) {}
  private record Target(Element element, @Nullable Viewable<?> owner) {}
}
