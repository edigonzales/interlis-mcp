package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Unit;
import ch.so.agi.mcp.model.RenameElementKind;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.util.NameValidator;
import java.beans.PropertyVetoException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class RenameTools {

  private final IliCompilerService compilerService;

  public RenameTools(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "renameModelElement",
      description = "Benennt ein INTERLIS-Modellelement robust über ili2c-Metamodell und vollständige Modell-Neugenerierung um. Nicht source-preserving."
  )
  public Map<String, Object> renameModelElement(
      @McpToolParam(description = "Vollständiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Name des umzubenennenden Elements", required = true) String elementFqn,
      @McpToolParam(description = "Optionale Guard-Elementart: MODEL, TOPIC, CLASS_OR_STRUCTURE, ASSOCIATION, DOMAIN, UNIT oder ATTRIBUTE", required = false) @Nullable RenameElementKind expectedKind,
      @McpToolParam(description = "Neuer einfacher Name des Elements", required = true) String newName
  ) {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("Model text is required.");
    }
    if (elementFqn == null || elementFqn.isBlank()) {
      throw new IllegalArgumentException("elementFqn is required.");
    }
    if (newName == null || newName.isBlank()) {
      throw new IllegalArgumentException("newName is required.");
    }

    NameValidator.ascii().validateFqn(elementFqn.trim(), "Element FQN");
    NameValidator.ascii().validateIdent(newName.trim(), "New element name");

    TransferDescription td = compilerService.compileOrThrow(modelText, null, "rename");

    Element element = td.getElement(elementFqn.trim());
    if (element == null) {
      throw new IllegalArgumentException("Element not found: " + elementFqn.trim());
    }
    RenameElementKind actualKind = detectKind(element);
    if (expectedKind != null && !matchesKind(element, expectedKind)) {
      throw new IllegalArgumentException(
          "Element '" + elementFqn.trim() + "' is not of expected kind " + expectedKind + ".");
    }

    String oldScopedName = element.getScopedName();
    renameElement(element, newName.trim());

    String regenerated = compilerService.generateModelsFromLastFile(td);
    TransferDescription validation = compilerService.compileOrThrow(regenerated, null, "validation after rename");

    return Map.of(
        "updatedModelText", regenerated,
        "oldElementFqn", oldScopedName,
        "newElementFqn", resolveRenamedScopedName(validation, oldScopedName, newName.trim()),
        "expectedKind", (expectedKind != null ? expectedKind : actualKind).name(),
        "notes", List.of("Model was fully regenerated with ili2c. Layout and declaration order may differ from the input.")
    );
  }

  private RenameElementKind detectKind(Element element) {
    if (element instanceof Model) {
      return RenameElementKind.MODEL;
    }
    if (element instanceof Topic) {
      return RenameElementKind.TOPIC;
    }
    if (element instanceof AssociationDef) {
      return RenameElementKind.ASSOCIATION;
    }
    if (element instanceof Table) {
      return RenameElementKind.CLASS_OR_STRUCTURE;
    }
    if (element instanceof Domain) {
      return RenameElementKind.DOMAIN;
    }
    if (element instanceof Unit) {
      return RenameElementKind.UNIT;
    }
    if (element instanceof AttributeDef) {
      return RenameElementKind.ATTRIBUTE;
    }

    throw new IllegalArgumentException("Renaming is not supported for element type: " + element.getClass().getSimpleName());
  }

  private boolean matchesKind(Element element, RenameElementKind expectedKind) {
    return switch (expectedKind) {
      case MODEL -> element instanceof Model;
      case TOPIC -> element instanceof Topic;
      case CLASS_OR_STRUCTURE -> element instanceof Table && !(element instanceof AssociationDef);
      case ASSOCIATION -> element instanceof AssociationDef;
      case DOMAIN -> element instanceof Domain;
      case UNIT -> element instanceof Unit;
      case ATTRIBUTE -> element instanceof AttributeDef;
    };
  }

  private void renameElement(Element element, String newName) {
    try {
      if (element instanceof Model model) {
        model.setName(newName);
      } else if (element instanceof Topic topic) {
        topic.setName(newName);
      } else if (element instanceof AssociationDef association) {
        association.setName(newName);
      } else if (element instanceof Table table) {
        table.setName(newName);
      } else if (element instanceof Domain domain) {
        domain.setName(newName);
      } else if (element instanceof Unit unit) {
        unit.setName(newName);
      } else if (element instanceof AttributeDef attribute) {
        attribute.setName(newName);
      } else {
        throw new IllegalArgumentException("Renaming is not supported for element type: " + element.getClass().getSimpleName());
      }
    } catch (PropertyVetoException e) {
      throw new IllegalStateException("Unable to rename element: " + e.getMessage(), e);
    }
  }

  private String resolveRenamedScopedName(TransferDescription td, String oldScopedName, String newName) {
    int split = oldScopedName.lastIndexOf('.');
    if (split < 0) {
      return newName;
    }

    String newScopedName = oldScopedName.substring(0, split + 1) + newName;
    Element renamed = td.getElement(newScopedName);
    return renamed != null ? renamed.getScopedName() : newScopedName;
  }
}
