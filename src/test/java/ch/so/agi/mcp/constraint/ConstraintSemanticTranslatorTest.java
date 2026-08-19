package ch.so.agi.mcp.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.service.IliCompilerService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConstraintSemanticTranslatorTest {

  private static final String MODEL = """
      INTERLIS 2.4;

      MODEL ModelA AT "mailto:test@localhost" VERSION "2026-08-19" =
        TOPIC TopicA =
          CLASS ClassOther =
            attrOther : 0..10;
          END ClassOther;

          CLASS RefEntity =
          END RefEntity;

          CLASS RefTarget =
            ref : REFERENCE TO RefEntity;
          END RefTarget;

          STRUCTURE StructA =
            structAttr : TEXT*100;
          END StructA;

          CLASS ClassA =
            attr1 : MANDATORY 0..999;
            attr2 : BAG {1..*} OF StructA;
            ref : REFERENCE TO RefEntity;
            MANDATORY CONSTRAINT NamedMandatory: attr1 > 1;
            CONSTRAINT NamedPlausibility: <= 80% attr1 > 2;
            EXISTENCE CONSTRAINT NamedExistence: attr1 REQUIRED IN ClassOther : attrOther;
            EXISTENCE CONSTRAINT NamedReferenceExistence: ref REQUIRED IN RefTarget : ref;
            UNIQUE (BASKET) NamedUniqueBasket: attr1;
            UNIQUE NamedUniqueLocal: (LOCAL) attr2 : structAttr;
            SET CONSTRAINT (BASKET) NamedSetBasket: INTERLIS.objectCount(ALL) >= 0;
          END ClassA;
        END TopicA;
      END ModelA.
      """;

  private final IliCompilerService compiler = new IliCompilerService();

  @Test
  void mandatoryWrapsExistingExpressionIrWithoutChangingSemantics() {
    Constraint compiled = constraint("NamedMandatory");

    SemanticConstraint.Mandatory semantic = assertKind(
        ConstraintSemanticTranslator.translate(compiled),
        SemanticConstraint.Mandatory.class);
    ConstraintAstTranslator.Translation previous = ConstraintAstTranslator.translate(compiled);

    assertThat(semantic.kind()).isEqualTo(SemanticConstraint.Kind.MANDATORY);
    assertThat(semantic.constraintName()).isEqualTo("NamedMandatory");
    assertThat(semantic.contextFqn()).isEqualTo("ModelA.TopicA.ClassA");
    assertThat(semantic.version()).isEqualTo(ConstraintExpression.IliVersion.ILI_24);
    assertThat(semantic.condition().toInterlis(semantic.version()))
        .isEqualTo(previous.expression().toInterlis(previous.version()));
  }

  @Test
  void translatesGlobalBasketUniqueSemantics() {
    SemanticConstraint.Unique semantic = assertKind(
        ConstraintSemanticTranslator.translate(constraint("NamedUniqueBasket")),
        SemanticConstraint.Unique.class);

    assertThat(semantic.kind()).isEqualTo(SemanticConstraint.Kind.UNIQUE);
    assertThat(semantic.local()).isFalse();
    assertThat(semantic.perBasket()).isTrue();
    assertThat(semantic.preCondition()).isNull();
    assertThat(semantic.prefix()).isNull();
    assertThat(semantic.elements()).singleElement().satisfies(path -> {
      assertThat(path.rootFqn()).isEqualTo("ModelA.TopicA.ClassA");
      assertThat(path.path()).isEqualTo("attr1");
      assertThat(path.attributePath()).isTrue();
      assertThat(path.targetViewableFqn()).isNull();
      assertThat(path.endpointType().scalarKind())
          .isEqualTo(ConstraintExpression.ScalarKind.NUMERIC);
    });
  }

  @Test
  void translatesLocalUniquePrefixSeparatelyFromUniqueElements() {
    SemanticConstraint.Unique semantic = assertKind(
        ConstraintSemanticTranslator.translate(constraint("NamedUniqueLocal")),
        SemanticConstraint.Unique.class);

    assertThat(semantic.local()).isTrue();
    assertThat(semantic.perBasket()).isFalse();
    assertThat(semantic.prefix()).isNotNull();
    assertThat(semantic.prefix().path()).isEqualTo("attr2");
    assertThat(semantic.prefix().rootFqn()).isEqualTo("ModelA.TopicA.ClassA");
    assertThat(semantic.prefix().targetViewableFqn()).isEqualTo("ModelA.TopicA.StructA");
    assertThat(semantic.elements()).singleElement().satisfies(path -> {
      assertThat(path.path()).isEqualTo("structAttr");
      assertThat(path.rootFqn()).isEqualTo("ModelA.TopicA.StructA");
      assertThat(path.targetViewableFqn()).isNull();
    });
  }

  @Test
  void translatesExistenceWithSourceAndTargetViewableRoots() {
    SemanticConstraint.Existence semantic = assertKind(
        ConstraintSemanticTranslator.translate(constraint("NamedExistence")),
        SemanticConstraint.Existence.class);

    assertThat(semantic.kind()).isEqualTo(SemanticConstraint.Kind.EXISTENCE);
    assertThat(semantic.restrictedAttribute().rootFqn()).isEqualTo("ModelA.TopicA.ClassA");
    assertThat(semantic.restrictedAttribute().path()).isEqualTo("attr1");
    assertThat(semantic.restrictedAttribute().targetViewableFqn()).isNull();
    assertThat(semantic.requiredIn()).singleElement().satisfies(path -> {
      assertThat(path.rootFqn()).isEqualTo("ModelA.TopicA.ClassOther");
      assertThat(path.path()).isEqualTo("attrOther");
      assertThat(path.attributePath()).isTrue();
      assertThat(path.targetViewableFqn()).isNull();
      assertThat(path.endpointType().scalarKind())
          .isEqualTo(ConstraintExpression.ScalarKind.NUMERIC);
    });
  }

  @Test
  void referenceToEndpointStillTranslatesAsExistenceAttributePath() {
    SemanticConstraint.Existence semantic = assertKind(
        ConstraintSemanticTranslator.translate(constraint("NamedReferenceExistence")),
        SemanticConstraint.Existence.class);

    assertThat(semantic.restrictedAttribute()).satisfies(path -> {
      assertThat(path.rootFqn()).isEqualTo("ModelA.TopicA.ClassA");
      assertThat(path.path()).isEqualTo("ref");
      assertThat(path.attributePath()).isTrue();
      assertThat(path.targetViewableFqn()).isEqualTo("ModelA.TopicA.RefEntity");
      assertThat(path.endpointType().scalarKind()).isEqualTo(ConstraintExpression.ScalarKind.UNKNOWN);
    });
    assertThat(semantic.requiredIn()).singleElement().satisfies(path -> {
      assertThat(path.rootFqn()).isEqualTo("ModelA.TopicA.RefTarget");
      assertThat(path.path()).isEqualTo("ref");
      assertThat(path.attributePath()).isTrue();
      assertThat(path.targetViewableFqn()).isEqualTo("ModelA.TopicA.RefEntity");
      assertThat(path.endpointType().scalarKind()).isEqualTo(ConstraintExpression.ScalarKind.UNKNOWN);
    });
  }

  @Test
  void translatesPlausibilityDirectionPercentageAndBooleanCondition() {
    SemanticConstraint.Plausibility semantic = assertKind(
        ConstraintSemanticTranslator.translate(constraint("NamedPlausibility")),
        SemanticConstraint.Plausibility.class);

    assertThat(semantic.kind()).isEqualTo(SemanticConstraint.Kind.PLAUSIBILITY);
    assertThat(semantic.direction()).isEqualTo(SemanticConstraint.PlausibilityDirection.AT_MOST);
    assertThat(semantic.percentage()).isEqualByComparingTo(new BigDecimal("80"));
    assertThat(semantic.condition().type().scalarKind())
        .isEqualTo(ConstraintExpression.ScalarKind.BOOLEAN);
    assertThat(semantic.condition().toInterlis(semantic.version()))
        .contains("attr1")
        .contains("2");
  }

  @Test
  void setConstraintGetsTypedEnvelopeWithoutPretendingAllIsAlreadySupported() {
    SemanticConstraint.Set semantic = assertKind(
        ConstraintSemanticTranslator.translate(constraint("NamedSetBasket")),
        SemanticConstraint.Set.class);

    assertThat(semantic.kind()).isEqualTo(SemanticConstraint.Kind.SET);
    assertThat(semantic.perBasket()).isTrue();
    assertThat(semantic.preCondition()).isNull();
    assertThat(semantic.condition())
        .isInstanceOf(SemanticConstraint.UntranslatedSetCondition.class);
    SemanticConstraint.UntranslatedSetCondition condition =
        (SemanticConstraint.UntranslatedSetCondition) semantic.condition();
    assertThat(condition.reasonCode()).isEqualTo("UNSUPPORTED_AST_NODE");
    assertThat(condition.metamodelType()).isNotBlank();
  }

  @Test
  void allConstraintKindsShareStableCommonMetadata() {
    List<SemanticConstraint> constraints = List.of(
        ConstraintSemanticTranslator.translate(constraint("NamedMandatory")),
        ConstraintSemanticTranslator.translate(constraint("NamedUniqueBasket")),
        ConstraintSemanticTranslator.translate(constraint("NamedExistence")),
        ConstraintSemanticTranslator.translate(constraint("NamedPlausibility")),
        ConstraintSemanticTranslator.translate(constraint("NamedSetBasket")));

    assertThat(constraints).extracting(SemanticConstraint::kind)
        .containsExactlyInAnyOrder(
            SemanticConstraint.Kind.MANDATORY,
            SemanticConstraint.Kind.UNIQUE,
            SemanticConstraint.Kind.EXISTENCE,
            SemanticConstraint.Kind.PLAUSIBILITY,
            SemanticConstraint.Kind.SET);
    assertThat(constraints).allSatisfy(constraint -> {
      assertThat(constraint.constraintScopedName()).contains(constraint.constraintName());
      assertThat(constraint.contextFqn()).isEqualTo("ModelA.TopicA.ClassA");
      assertThat(constraint.version()).isEqualTo(ConstraintExpression.IliVersion.ILI_24);
    });
  }

  private Constraint constraint(String name) {
    IliCompilerService.CompilationResult compilation =
        compiler.compile(MODEL, null, "ili2c_constraint_semantic_ir_");
    assertThat(compilation.valid()).as(compilation.messages().toString()).isTrue();
    TransferDescription td = compilation.transferDescription();
    assertThat(td).isNotNull();

    List<Constraint> matches = new ArrayList<>();
    for (Model model : td.getModelsFromLastFile()) {
      collect(model, name, matches);
    }
    assertThat(matches).hasSize(1);
    return matches.getFirst();
  }

  private void collect(Container<?> container, String name, List<Constraint> matches) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object child = iterator.next();
      if (child instanceof Constraint constraint && name.equals(constraint.getName())) {
        matches.add(constraint);
      }
      if (child instanceof Container<?> nested) {
        collect(nested, name, matches);
      }
    }
  }

  private <T extends SemanticConstraint> T assertKind(
      SemanticConstraint semantic,
      Class<T> type) {
    assertThat(semantic).isInstanceOf(type);
    return type.cast(semantic);
  }
}
