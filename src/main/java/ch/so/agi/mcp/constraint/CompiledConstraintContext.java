package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One successfully compiled model plus one resolved constraint and its typed semantic IR.
 *
 * <p>The context is deliberately immutable so review, proof planning, XTF synthesis and validator
 * verification can share the same ili2c metamodel without recompiling the unchanged model.</p>
 */
public record CompiledConstraintContext(
    String modelText,
    @Nullable String modelRepositories,
    IliCompilerService.CompilationResult compilation,
    TransferDescription transferDescription,
    Constraint constraint,
    SemanticConstraint semantics) {

  public CompiledConstraintContext {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("modelText is required.");
    }
    Objects.requireNonNull(compilation, "compilation");
    Objects.requireNonNull(transferDescription, "transferDescription");
    Objects.requireNonNull(constraint, "constraint");
    Objects.requireNonNull(semantics, "semantics");
    if (!compilation.valid()) {
      throw new IllegalArgumentException("CompiledConstraintContext requires a valid compilation.");
    }
    if (compilation.transferDescription() != transferDescription) {
      throw new IllegalArgumentException("transferDescription must be the one owned by compilation.");
    }
  }

  public String contextFqn() {
    return semantics.contextFqn();
  }

  public String constraintFqn() {
    return semantics.constraintScopedName();
  }
}
