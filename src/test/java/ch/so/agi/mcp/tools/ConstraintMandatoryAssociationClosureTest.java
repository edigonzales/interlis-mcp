package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintMandatoryAssociationClosureTest {

  private static final String SAME_AND_CROSS_TOPIC_MODEL = """
      INTERLIS 2.3;

      MODEL RequiredAssociationClosure (en)
      AT "https://example.org"
      VERSION "2026-09-06" =
        TOPIC ReferenceData =
          OID AS INTERLIS.UUIDOID;

          CLASS Team =
            name : MANDATORY TEXT*20;
          END Team;
        END ReferenceData;

        TOPIC Data =
          DEPENDS ON ReferenceData;

          CLASS Lot =
            code : MANDATORY TEXT*20;
          END Lot;

          CLASS Main =
            weight : MANDATORY 0..100;
          END Main;

          CLASS Secondary =
            weight : MANDATORY 0..100;
          END Secondary;

          ASSOCIATION MainLot =
            main_r -- {*} Main;
            lot -- {1} Lot;
          END MainLot;

          ASSOCIATION MainTeam =
            main_team_r -- {*} Main;
            team (EXTERNAL) -- {1} RequiredAssociationClosure.ReferenceData.Team;
          END MainTeam;

          ASSOCIATION SecondaryMain =
            secondary_r -- {*} Secondary;
            main -- {1} Main;
          END SecondaryMain;

          CONSTRAINTS OF RequiredAssociationClosure.Data.Secondary =
            !!@ name = "MainWeightGreater"
            MANDATORY CONSTRAINT main->weight > weight;
          END;
        END Data;
      END RequiredAssociationClosure.
      """;

  private static final String CYCLIC_MODEL = """
      INTERLIS 2.4;

      MODEL CyclicRequiredAssociations (en)
      AT "https://example.org"
      VERSION "2026-09-06" =
        TOPIC Data =
          CLASS A =
            value : MANDATORY 0..10;
          END A;

          CLASS B =
          END B;

          ASSOCIATION AToB =
            a_r -- {*} A;
            b -- {1} B;
          END AToB;

          ASSOCIATION BToA =
            b_r -- {*} B;
            a -- {1} A;
          END BToA;

          CONSTRAINTS OF CyclicRequiredAssociations.Data.A =
            !!@ name = "NonNegative"
            MANDATORY CONSTRAINT value >= 0;
          END;
        END Data;
      END CyclicRequiredAssociations.
      """;

  private static final String AMBIGUOUS_ABSTRACT_TARGET_MODEL = """
      INTERLIS 2.4;

      MODEL AmbiguousRequiredTarget (en)
      AT "https://example.org"
      VERSION "2026-09-06" =
        TOPIC Data =
          CLASS AbstractTarget (ABSTRACT) =
          END AbstractTarget;

          CLASS TargetA EXTENDS AbstractTarget =
          END TargetA;

          CLASS TargetB EXTENDS AbstractTarget =
          END TargetB;

          CLASS Source =
            value : MANDATORY 0..10;
          END Source;

          ASSOCIATION SourceTarget =
            source_r -- {*} Source;
            target -- {1} AbstractTarget;
          END SourceTarget;

          CONSTRAINTS OF AmbiguousRequiredTarget.Data.Source =
            !!@ name = "NonNegative"
            MANDATORY CONSTRAINT value >= 0;
          END;
        END Data;
      END AmbiguousRequiredTarget.
      """;

  private static final String ASSOCIATION_LIMIT_MODEL = """
      INTERLIS 2.4;

      MODEL RequiredAssociationLimit (en)
      AT "https://example.org"
      VERSION "2026-09-06" =
        TOPIC Data =
          CLASS Target =
          END Target;

          CLASS Source =
            value : MANDATORY 0..10;
          END Source;

          ASSOCIATION SourceTargets =
            source_r -- {*} Source;
            target -- {33} Target;
          END SourceTargets;

          CONSTRAINTS OF RequiredAssociationLimit.Data.Source =
            !!@ name = "NonNegative"
            MANDATORY CONSTRAINT value >= 0;
          END;
        END Data;
      END RequiredAssociationLimit.
      """;

  @Test
  void completesSameTopicAndExternalMandatoryAssociations() {
    Map<String, Object> result = tools().generateIliConstraintCases(
        SAME_AND_CROSS_TOPIC_MODEL,
        "MainWeightGreater");

    assertThat(result.get("generationVerified")).isEqualTo(true);
    Map<String, Object> verification = map(result.get("verification"));
    assertThat(maps(verification.get("cases")))
        .allSatisfy(item -> assertThat(item.get("fixtureValid")).isEqualTo(true))
        .anySatisfy(item -> {
          String xtf = String.valueOf(item.get("xtfText"));
          assertThat(xtf).contains("<lot REF=");
          assertThat(xtf).contains("<team EXTREF=").contains("BID=");
          assertThat(xtf).contains("<RequiredAssociationClosure.Data.Lot");
          assertThat(xtf).contains("<RequiredAssociationClosure.ReferenceData.Team");
        });
  }

  @Test
  void closesMandatoryAssociationCyclesByReusingCompatibleObjects() {
    Map<String, Object> result = tools().generateIliConstraintCases(
        CYCLIC_MODEL,
        "NonNegative");

    assertThat(result.get("generationVerified")).isEqualTo(true);
    assertThat(String.valueOf(result)).doesNotContain("MANDATORY_ASSOCIATION_FIXTURE_LIMIT");
  }

  @Test
  void reportsAmbiguousAbstractMandatoryTargetAsIncomplete() {
    Map<String, Object> result = tools().generateIliConstraintCases(
        AMBIGUOUS_ABSTRACT_TARGET_MODEL,
        "NonNegative");

    assertThat(result.get("generationVerified")).isEqualTo(false);
    assertThat(result.get("proofIncomplete")).isEqualTo(true);
    assertThat(result.get("reasonCode")).isEqualTo("MANDATORY_ASSOCIATION_TARGET_AMBIGUOUS");
  }

  @Test
  void stopsMandatoryAssociationExpansionAtTheFixtureLimit() {
    Map<String, Object> result = tools().generateIliConstraintCases(
        ASSOCIATION_LIMIT_MODEL,
        "NonNegative");

    assertThat(result.get("generationVerified")).isEqualTo(false);
    assertThat(result.get("proofIncomplete")).isEqualTo(true);
    assertThat(result.get("reasonCode")).isEqualTo("MANDATORY_ASSOCIATION_FIXTURE_LIMIT");
  }

  private ConstraintCaseGenerationTools tools() {
    IliCompilerService compiler = new IliCompilerService();
    return new ConstraintCaseGenerationTools(
        new ConstraintContextService(compiler),
        new ConstraintTestTools(compiler));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
