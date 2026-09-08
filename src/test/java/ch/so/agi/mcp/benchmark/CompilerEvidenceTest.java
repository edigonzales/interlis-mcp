package ch.so.agi.mcp.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import ch.interlis.ili2c.metamodel.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CompilerEvidenceTest {
  @TempDir Path directory;
  Path model(String file, String body) throws Exception {
    Path p=directory.resolve(file);
    Files.writeString(p,"INTERLIS 2.4; MODEL Test (en) AT \"https://example.org\" VERSION \"1\" = TOPIC Data = " + body + " END Data; END Test.");
    return p;
  }
  Object ast(Path path, String name) {
    var evidence=CompilerEvidence.inspect(path,directory.toString(),name);
    assertThat(evidence).as(evidence.toString()).containsEntry("valid",true);
    return evidence.get("ast");
  }
  @Test void orderedUndefinedComparisonsRetainTheirOrderButNamesDoNotMatter() throws Exception {
    String prefix="CLASS Item = A : BOOLEAN; B : BOOLEAN; ";
    var one=model("one.ili",prefix+"MANDATORY CONSTRAINT One: A AND B; END Item;");
    var two=model("two.ili",prefix+"MANDATORY CONSTRAINT Two: B AND A; END Item;");
    var same=model("same.ili",prefix+"MANDATORY CONSTRAINT Same: (A AND B); END Item;");
    assertThat(ast(one,"Test.Data.Item.One")).isNotEqualTo(ast(two,"Test.Data.Item.Two"));
    assertThat(ast(one,"Test.Data.Item.One")).isEqualTo(ast(same,"Test.Data.Item.Same"));
  }
  @Test void detectsChangedExistingAttributeAndAdditionalConstraint() throws Exception {
    var before=model("before.ili","CLASS Item = number : 0..10; END Item;");
    var clean=model("clean.ili","CLASS Item = number : 0..10; MANDATORY CONSTRAINT Rule: number > 2; END Item;");
    var changed=model("changed.ili","CLASS Item = number : 0..20; MANDATORY CONSTRAINT Rule: number > 2; END Item;");
    var extra=model("extra.ili","CLASS Item = number : 0..10; MANDATORY CONSTRAINT Rule: number > 2; MANDATORY CONSTRAINT Other: number < 8; END Item;");
    assertThat(CompilerEvidence.compare(before,clean,directory.toString())).containsEntry("noCollateralChanges",true);
    assertThat(CompilerEvidence.compare(before,changed,directory.toString())).containsEntry("noCollateralChanges",false);
    assertThat(CompilerEvidence.compare(before,extra,directory.toString())).containsEntry("noCollateralChanges",false);
  }
  @Test void insertionBeforeAnonymousConstraintsDoesNotFabricateCollateralChanges() throws Exception {
    var before=model("before.ili","CLASS Item = number : 0..10; MANDATORY CONSTRAINT number < 9; END Item;");
    var after=model("after.ili","CLASS Item = number : 0..10; MANDATORY CONSTRAINT Rule: number > 2; MANDATORY CONSTRAINT number < 9; END Item;");
    assertThat(CompilerEvidence.compare(before,after,directory.toString())).containsEntry("noCollateralChanges",true);
  }
  @Test void ili23MetadataNamesAreExplicitWhileAnonymousSiblingNumbersAreNot() throws Exception {
    var before=model("before.ili","CLASS Item = number : 0..10; MANDATORY CONSTRAINT number < 9; END Item;");
    var after=model("after.ili","CLASS Item = number : 0..10;\n!!@ name = Rule\nMANDATORY CONSTRAINT number > 2; MANDATORY CONSTRAINT number < 9; END Item;");
    for(var p:List.of(before,after)) Files.writeString(p,Files.readString(p).replace("INTERLIS 2.4", "INTERLIS 2.3"));
    assertThat(CompilerEvidence.compare(before,after,directory.toString())).containsEntry("noCollateralChanges",true)
        .containsEntry("addedConstraints",List.of("Test.Data.Item.Rule"));
  }
  @Test void goldenObjectSetsHaveNoOpaqueIdentityStringsAndAreRepeatable() throws Exception {
    var suite=Path.of("evals/constraint-reconstruction/v2").toAbsolutePath();
    var mapper=new ObjectMapper();
    for(String cid:List.of("P10","N12")) {
      var expected=mapper.readTree(Files.readString(suite.resolve("oracle/"+cid+"/expected.json")));
      String selector=expected.path("originalConstraintSelector").asText();
      if(!selector.contains(".")) selector=expected.path("gold").path("contextFqn").asText()+"."+selector;
      var evidence=CompilerEvidence.inspect(suite.resolve("oracle/"+cid+"/gold-model.ili"),suite.resolve("dependencies").toString(),selector);
      assertThat(evidence).containsEntry("astComplete",true);
      var serialized=mapper.valueToTree(evidence.get("ast"));
      assertThat(serialized).isEqualTo(mapper.readTree(Files.readString(suite.resolve("oracle/"+cid+"/compiler-gold.json"))).path("ast"));
      assertThat(serialized.toString()).doesNotContain("UNSUPPORTED","Objects@");
    }
  }
  @Test void exhaustiveP01ProofDomainReallyIsFiniteMandatoryAndIntegral() {
    var suite=Path.of("evals/constraint-reconstruction/v2").toAbsolutePath();
    var compiled=CompilerEvidence.compile(suite.resolve("public/P01/model.ili"),suite.resolve("dependencies").toString());
    String context="SO_AFU_Bodeneinheiten_20251210.Bodeneinheiten.Unterboden_Landwirtschaft.";
    var numeric=(AttributeDef)compiled.td().getElement(context+"Tongehalt");
    var enumeration=(AttributeDef)compiled.td().getElement(context+"Koernungsklasse");
    var type=(NumericType)numeric.getDomainResolvingAliases();
    assertThat(type.getMinimum().toString()).isEqualTo("0");
    assertThat(type.getMaximum().toString()).isEqualTo("100");
    assertThat(type.getMinimum().getAccuracy()).isZero();
    assertThat(numeric.getDomain().isMandatoryConsideringAliases()).isTrue();
    assertThat(enumeration.getDomain().isMandatoryConsideringAliases()).isTrue();
    assertThat(((EnumerationType)enumeration.getDomainResolvingAliases()).getValues()).hasSize(13);
  }
}
