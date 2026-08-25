package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AreaType;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.EnumTreeValueType;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.Extendable;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.LineType;
import ch.interlis.ili2c.metamodel.MultiAreaType;
import ch.interlis.ili2c.metamodel.MultiCoordType;
import ch.interlis.ili2c.metamodel.MultiPolylineType;
import ch.interlis.ili2c.metamodel.MultiSurfaceType;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.PolylineType;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.SurfaceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Metamodel-driven source of two distinct valid fixture values.
 *
 * <p>Aliases, including CHBase domains, are deliberately resolved through {@link Type#findReal}.
 * No type name or geometry syntax is guessed.</p>
 */
final class TypedValueFixtureFactory {

  sealed interface GeometryValue
      permits CoordinateValue, PolylineValue, SurfaceValue,
          MultiCoordinateValue, MultiPolylineValue, MultiSurfaceValue {
  }

  record CoordinateValue(List<BigDecimal> ordinates) implements GeometryValue {
    CoordinateValue {
      ordinates = List.copyOf(ordinates);
      if (ordinates.isEmpty() || ordinates.size() > 3) {
        throw new IllegalArgumentException("A coordinate fixture requires one to three ordinates.");
      }
    }
  }

  record PolylineValue(List<CoordinateValue> points) implements GeometryValue {
    PolylineValue {
      points = List.copyOf(points);
      if (points.size() < 2) throw new IllegalArgumentException(
          "A polyline fixture requires at least two coordinates.");
    }
  }

  record SurfaceValue(List<PolylineValue> boundaries) implements GeometryValue {
    SurfaceValue {
      boundaries = List.copyOf(boundaries);
      if (boundaries.isEmpty()) throw new IllegalArgumentException(
          "A surface fixture requires at least one boundary.");
    }
  }

  record MultiCoordinateValue(List<CoordinateValue> coordinates) implements GeometryValue {
    MultiCoordinateValue { coordinates = List.copyOf(coordinates); }
  }

  record MultiPolylineValue(List<PolylineValue> polylines) implements GeometryValue {
    MultiPolylineValue { polylines = List.copyOf(polylines); }
  }

  record MultiSurfaceValue(List<SurfaceValue> surfaces) implements GeometryValue {
    MultiSurfaceValue { surfaces = List.copyOf(surfaces); }
  }

  record ReferenceValue(String targetOid) {
    ReferenceValue {
      if (targetOid == null || targetOid.isBlank()) {
        throw new IllegalArgumentException("Reference fixture target OID is required.");
      }
    }
  }

  record ValuePair(Object same, @Nullable Object different, String kind) {
    boolean distinguishable() { return different != null; }
  }

  private TypedValueFixtureFactory() {
  }

  static @Nullable ValuePair pair(Type declaredType) {
    if (declaredType == null) return null;
    Type real = Type.findReal(declaredType);
    if (real instanceof TextType text) {
      int maximum = text.getMaxLength();
      if (maximum == 0) return null;
      return new ValuePair("a", maximum < 1 ? null : "b",
          text.isNormalized() ? "TEXT" : "MTEXT");
    }
    if (real instanceof NumericType numeric) {
      return numericPair(numeric);
    }
    if (real.isBoolean()) return new ValuePair(Boolean.TRUE, Boolean.FALSE, "BOOLEAN");
    if (real instanceof EnumerationType enumeration) {
      return enumerationPair(enumeration.getValues(), "ENUM");
    }
    if (real instanceof EnumTreeValueType enumeration) {
      return enumerationPair(enumeration.getValues(), "ENUM_TREE");
    }
    if (real instanceof ReferenceType) {
      return new ValuePair(
          new ReferenceValue("fixture_reference_same"),
          new ReferenceValue("fixture_reference_different"),
          "REFERENCE");
    }
    if (real instanceof MultiCoordType multiCoord) {
      ValuePair coordinate = coordinatePair(multiCoord);
      if (coordinate == null) return null;
      return new ValuePair(
          new MultiCoordinateValue(List.of((CoordinateValue) coordinate.same())),
          coordinate.different() == null ? null
              : new MultiCoordinateValue(List.of((CoordinateValue) coordinate.different())),
          "MULTICOORD");
    }
    if (real instanceof AbstractCoordType coordinate) return coordinatePair(coordinate);
    if (real instanceof MultiPolylineType multiPolyline) {
      ValuePair polyline = polylinePair(multiPolyline);
      if (polyline == null) return null;
      return new ValuePair(
          new MultiPolylineValue(List.of((PolylineValue) polyline.same())),
          polyline.different() == null ? null
              : new MultiPolylineValue(List.of((PolylineValue) polyline.different())),
          "MULTIPOLYLINE");
    }
    if (real instanceof PolylineType polyline) return polylinePair(polyline);
    if (real instanceof MultiSurfaceType multiSurface) return multiSurfacePair(multiSurface, "MULTISURFACE");
    if (real instanceof MultiAreaType multiArea) return multiSurfacePair(multiArea, "MULTIAREA");
    if (real instanceof SurfaceType surface) return surfacePair(surface, "SURFACE");
    if (real instanceof AreaType area) return surfacePair(area, "AREA");
    if (real instanceof CompositionType composition) return compositionPair(composition);
    if (real instanceof LineType) return null;
    return null;
  }

  private static ValuePair numericPair(NumericType numeric) {
    BigDecimal minimum = decimal(numeric.getMinimum());
    BigDecimal maximum = decimal(numeric.getMaximum());
    BigDecimal same = minimum != null ? minimum : maximum != null ? maximum : BigDecimal.ZERO;
    BigDecimal different = null;
    if (maximum != null && maximum.compareTo(same) != 0) different = maximum;
    else if (minimum != null && minimum.compareTo(same) != 0) different = minimum;
    else if (minimum == null && maximum == null) different = BigDecimal.ONE;
    return new ValuePair(same, different, "NUMERIC");
  }

  private static @Nullable ValuePair enumerationPair(List<String> values, String kind) {
    if (values == null || values.isEmpty()) return null;
    return new ValuePair(values.getFirst(), values.size() > 1 ? values.get(1) : null, kind);
  }

  private static @Nullable ValuePair coordinatePair(AbstractCoordType coordinate) {
    var dimensions = coordinate.getDimensions();
    if (dimensions.length < 1 || dimensions.length > 3) return null;
    List<BigDecimal> same = new ArrayList<>();
    List<BigDecimal> different = new ArrayList<>();
    boolean changed = false;
    for (var dimension : dimensions) {
      if (!(dimension instanceof NumericType numeric)) return null;
      ValuePair pair = numericPair(numeric);
      same.add((BigDecimal) pair.same());
      BigDecimal alternative = pair.different() instanceof BigDecimal decimal
          ? decimal : (BigDecimal) pair.same();
      different.add(alternative);
      changed |= alternative.compareTo((BigDecimal) pair.same()) != 0;
    }
    return new ValuePair(
        new CoordinateValue(same),
        changed ? new CoordinateValue(different) : null,
        "COORD");
  }

  private static @Nullable ValuePair polylinePair(LineType line) {
    AbstractCoordType controlPoint = controlPointType(line);
    if (controlPoint == null) return null;
    ValuePair coordinate = coordinatePair(controlPoint);
    if (coordinate == null || coordinate.different() == null) return null;
    CoordinateValue first = (CoordinateValue) coordinate.same();
    CoordinateValue second = (CoordinateValue) coordinate.different();
    CoordinateValue elbow = elbow(first, second);
    PolylineValue different = elbow == null
        ? new PolylineValue(List.of(second, first))
        : new PolylineValue(List.of(first, elbow, second));
    return new ValuePair(
        new PolylineValue(List.of(first, second)),
        different,
        "POLYLINE");
  }

  private static @Nullable ValuePair surfacePair(LineType line, String kind) {
    AbstractCoordType controlPoint = controlPointType(line);
    if (controlPoint == null) return null;
    ValuePair coordinate = coordinatePair(controlPoint);
    if (coordinate == null || coordinate.different() == null) return null;
    CoordinateValue lower = (CoordinateValue) coordinate.same();
    CoordinateValue upper = (CoordinateValue) coordinate.different();
    List<CoordinateValue> firstRing = rectangle(lower, upper);
    if (firstRing == null) return null;
    List<CoordinateValue> secondRing = List.of(
        firstRing.get(0), firstRing.get(1), firstRing.get(2), firstRing.get(0));
    return new ValuePair(
        new SurfaceValue(List.of(new PolylineValue(firstRing))),
        new SurfaceValue(List.of(new PolylineValue(secondRing))),
        kind);
  }

  private static @Nullable ValuePair multiSurfacePair(LineType line, String kind) {
    ValuePair surface = surfacePair(line, kind);
    if (surface == null) return null;
    return new ValuePair(
        new MultiSurfaceValue(List.of((SurfaceValue) surface.same())),
        surface.different() == null ? null
            : new MultiSurfaceValue(List.of((SurfaceValue) surface.different())),
        kind);
  }

  private static @Nullable List<CoordinateValue> rectangle(
      CoordinateValue lower, CoordinateValue upper) {
    if (lower.ordinates().size() < 2) return null;
    int firstChanged = -1;
    int secondChanged = -1;
    for (int index = 0; index < lower.ordinates().size(); index++) {
      if (lower.ordinates().get(index).compareTo(upper.ordinates().get(index)) == 0) continue;
      if (firstChanged < 0) firstChanged = index;
      else if (secondChanged < 0) secondChanged = index;
    }
    if (secondChanged < 0) return null;
    List<BigDecimal> a = lower.ordinates();
    List<BigDecimal> c = upper.ordinates();
    List<BigDecimal> b = new ArrayList<>(a);
    List<BigDecimal> d = new ArrayList<>(a);
    b.set(firstChanged, c.get(firstChanged));
    d.set(secondChanged, c.get(secondChanged));
    return List.of(
        new CoordinateValue(a),
        new CoordinateValue(b),
        new CoordinateValue(c),
        new CoordinateValue(d),
        new CoordinateValue(a));
  }

  private static @Nullable CoordinateValue elbow(
      CoordinateValue first, CoordinateValue second) {
    List<BigDecimal> result = new ArrayList<>(first.ordinates());
    for (int index = 0; index < result.size(); index++) {
      if (first.ordinates().get(index).compareTo(second.ordinates().get(index)) != 0) {
        result.set(index, second.ordinates().get(index));
        CoordinateValue candidate = new CoordinateValue(result);
        if (!candidate.equals(first) && !candidate.equals(second)) return candidate;
      }
    }
    return null;
  }

  private static @Nullable ValuePair compositionPair(CompositionType composition) {
    Table component = composition.getComponentType();
    Map<String, Object> same = new LinkedHashMap<>();
    Map<String, Object> different = new LinkedHashMap<>();
    boolean changed = false;
    Iterator<Extendable> attributes = component.getAttributes();
    while (attributes.hasNext()) {
      Extendable element = attributes.next();
      if (!(element instanceof AttributeDef attribute)) continue;
      ValuePair pair = pair(attribute.getDomainOrDerivedDomain());
      boolean mandatory = attribute.getDomainOrDerivedDomain().isMandatoryConsideringAliases();
      if (pair == null) {
        if (mandatory) return null;
        continue;
      }
      if (mandatory || same.isEmpty()) {
        same.put(attribute.getName(), pair.same());
        different.put(attribute.getName(), pair.same());
      }
      if (!changed && pair.different() != null) {
        same.put(attribute.getName(), pair.same());
        different.put(attribute.getName(), pair.different());
        changed = true;
      }
    }
    if (same.isEmpty()) return null;
    return new ValuePair(Map.copyOf(same), changed ? Map.copyOf(different) : null, "STRUCTURE");
  }

  private static @Nullable AbstractCoordType controlPointType(LineType line) {
    if (line.getControlPointDomain() == null) return null;
    Type real = Type.findReal(line.getControlPointDomain().getType());
    return real instanceof AbstractCoordType coordinate ? coordinate : null;
  }

  private static @Nullable BigDecimal decimal(@Nullable Object value) {
    return value == null ? null : new BigDecimal(String.valueOf(value));
  }
}
