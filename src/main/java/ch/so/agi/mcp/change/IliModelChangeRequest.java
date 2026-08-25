package ch.so.agi.mcp.change;

import ch.so.agi.mcp.model.IliModelSpec;
import ch.so.agi.mcp.model.IliConstraintSpec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class IliModelChangeRequest {

  @JsonProperty(required = true)
  private IliModelChangeOperation operation;

  @JsonProperty(required = false)
  private AddAttributeChange addAttribute;

  @JsonProperty(required = false) private AddImportChange addImport;
  @JsonProperty(required = false) private AddTopicChange addTopic;
  @JsonProperty(required = false) private AddDomainChange addDomain;
  @JsonProperty(required = false) private AddUnitChange addUnit;
  @JsonProperty(required = false) private AddClassChange addClass;
  @JsonProperty(required = false) private AddStructureChange addStructure;
  @JsonProperty(required = false) private AddAssociationChange addAssociation;
  @JsonProperty(required = false) private UpdateAttributeChange updateAttribute;
  @JsonProperty(required = false) private RemoveAttributeChange removeAttribute;
  @JsonProperty(required = false) private AddConstraintChange addConstraint;

  public static class AddImportChange {
    @JsonProperty(required = true) public String modelFqn;
    @JsonProperty(required = true) public String importModel;
  }
  public static class AddTopicChange {
    @JsonProperty(required = true) public String modelFqn;
    @JsonProperty(required = true) public IliModelSpec.TopicSpec topic;
  }
  public static class AddDomainChange {
    @JsonProperty(required = true) public String containerFqn;
    @JsonProperty(required = true) public IliModelSpec.DomainSpec domain;
  }
  public static class AddUnitChange {
    @JsonProperty(required = true) public String containerFqn;
    @JsonProperty(required = true) public IliModelSpec.UnitSpec unit;
  }
  public static class AddClassChange {
    @JsonProperty(required = true) public String topicFqn;
    @JsonProperty(required = true) public IliModelSpec.ClassSpec clazz;
  }
  public static class AddStructureChange {
    @JsonProperty(required = true) public String topicFqn;
    @JsonProperty(required = true) public IliModelSpec.StructureSpec structure;
  }
  public static class AddAssociationChange {
    @JsonProperty(required = true) public String topicFqn;
    @JsonProperty(required = true) public IliModelSpec.AssociationSpec association;
  }
  public static class AddConstraintChange {
    @JsonProperty(required = true) public String containerFqn;
    @JsonProperty(required = true) public IliConstraintSpec constraint;
  }

  public IliModelChangeOperation getOperation() {
    return operation;
  }

  public void setOperation(IliModelChangeOperation operation) {
    this.operation = operation;
  }

  public AddAttributeChange getAddAttribute() {
    return addAttribute;
  }

  public void setAddAttribute(AddAttributeChange addAttribute) {
    this.addAttribute = addAttribute;
  }

  public AddImportChange getAddImport() { return addImport; }
  public void setAddImport(AddImportChange value) { addImport = value; }
  public AddTopicChange getAddTopic() { return addTopic; }
  public void setAddTopic(AddTopicChange value) { addTopic = value; }
  public AddDomainChange getAddDomain() { return addDomain; }
  public void setAddDomain(AddDomainChange value) { addDomain = value; }
  public AddUnitChange getAddUnit() { return addUnit; }
  public void setAddUnit(AddUnitChange value) { addUnit = value; }
  public AddClassChange getAddClass() { return addClass; }
  public void setAddClass(AddClassChange value) { addClass = value; }
  public AddStructureChange getAddStructure() { return addStructure; }
  public void setAddStructure(AddStructureChange value) { addStructure = value; }
  public AddAssociationChange getAddAssociation() { return addAssociation; }
  public void setAddAssociation(AddAssociationChange value) { addAssociation = value; }
  public UpdateAttributeChange getUpdateAttribute() { return updateAttribute; }
  public void setUpdateAttribute(UpdateAttributeChange value) { updateAttribute = value; }
  public RemoveAttributeChange getRemoveAttribute() { return removeAttribute; }
  public void setRemoveAttribute(RemoveAttributeChange value) { removeAttribute = value; }
  public AddConstraintChange getAddConstraint() { return addConstraint; }
  public void setAddConstraint(AddConstraintChange value) { addConstraint = value; }

  public IliModelChangeOperation requireOperation() {
    if (operation == null) {
      throw new IllegalArgumentException("Change operation is required.");
    }
    return operation;
  }

  public AddAttributeChange requireAddAttribute() {
    if (addAttribute == null) {
      throw new IllegalArgumentException("addAttribute payload is required for ADD_ATTRIBUTE.");
    }
    return addAttribute;
  }

  public Object requirePayload() {
    return switch (requireOperation()) {
      case ADD_IMPORT -> require(addImport, "addImport");
      case ADD_TOPIC -> require(addTopic, "addTopic");
      case ADD_DOMAIN -> require(addDomain, "addDomain");
      case ADD_UNIT -> require(addUnit, "addUnit");
      case ADD_CLASS -> require(addClass, "addClass");
      case ADD_STRUCTURE -> require(addStructure, "addStructure");
      case ADD_ASSOCIATION -> require(addAssociation, "addAssociation");
      case ADD_ATTRIBUTE -> requireAddAttribute();
      case UPDATE_ATTRIBUTE -> require(updateAttribute, "updateAttribute");
      case REMOVE_ATTRIBUTE -> require(removeAttribute, "removeAttribute");
      case ADD_CONSTRAINT -> require(addConstraint, "addConstraint");
    };
  }

  private <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(
          field + " payload is required for " + operation + ".");
    }
    return value;
  }
}
