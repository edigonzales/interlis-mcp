package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Compiles and resolves constraints once for reuse by review/authoring/proof stages. */
@Service
public class ConstraintContextService {

  private final IliCompilerService compilerService;

  public ConstraintContextService(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  public Resolution compileAndResolve(
      String modelText,
      String requestedConstraint,
      @Nullable String modelRepositories,
      String tempPrefix) {
    requireText(modelText, "modelText");
    requireText(requestedConstraint, "requestedConstraint");
    String prefix = requireText(tempPrefix, "tempPrefix");

    IliCompilerService.CompilationResult compilation =
        compilerService.compile(modelText, modelRepositories, prefix);
    return resolveCompiled(modelText, requestedConstraint, modelRepositories, compilation);
  }

  public Resolution resolveCompiled(
      String modelText,
      String requestedConstraint,
      @Nullable String modelRepositories,
      IliCompilerService.CompilationResult compilation) {
    requireText(modelText, "modelText");
    String requested = requireText(requestedConstraint, "requestedConstraint");
    Objects.requireNonNull(compilation, "compilation");

    if (!compilation.valid() || compilation.transferDescription() == null) {
      return new Resolution(
          compilation,
          null,
          "MODEL_COMPILATION_FAILED",
          "The model must compile before the constraint can be resolved.");
    }

    TransferDescription td = compilation.transferDescription();
    List<Constraint> matches = findConstraints(td, requested);
    if (matches.isEmpty()) {
      return new Resolution(
          compilation,
          null,
          "CONSTRAINT_LOOKUP_FAILED",
          "Constraint not found: " + requested);
    }
    if (matches.size() > 1) {
      return new Resolution(
          compilation,
          null,
          "CONSTRAINT_LOOKUP_AMBIGUOUS",
          "Constraint name is ambiguous; use the fully qualified constraint name: " + requested);
    }

    Constraint constraint = matches.getFirst();
    SemanticConstraint semantics;
    try {
      semantics = ConstraintSemanticTranslator.translate(constraint);
    } catch (ConstraintSemanticTranslator.TranslationException ex) {
      return new Resolution(compilation, null, ex.reasonCode(), ex.getMessage());
    } catch (IllegalArgumentException ex) {
      return new Resolution(
          compilation,
          null,
          "UNSUPPORTED_CONSTRAINT_SEMANTICS",
          ex.getMessage());
    }

    return new Resolution(
        compilation,
        new CompiledConstraintContext(
            modelText,
            modelRepositories,
            compilation,
            td,
            constraint,
            semantics),
        null,
        null);
  }

  private List<Constraint> findConstraints(TransferDescription td, String requestedName) {
    List<Constraint> matches = new ArrayList<>();
    for (Model model : td.getModelsFromLastFile()) {
      collectConstraints(model, requestedName, matches);
    }
    return matches;
  }

  private void collectConstraints(
      Container<?> container,
      String requestedName,
      List<Constraint> sink) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object child = iterator.next();
      if (child instanceof Constraint constraint) {
        if (requestedName.equals(constraint.getName())
            || requestedName.equals(constraint.getScopedName())) {
          sink.add(constraint);
        }
      } else if (child instanceof Container<?> nested) {
        collectConstraints(nested, requestedName, sink);
      }
    }
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return value.trim();
  }

  public record Resolution(
      IliCompilerService.CompilationResult compilation,
      @Nullable CompiledConstraintContext context,
      @Nullable String reasonCode,
      @Nullable String reason) {

    public Resolution {
      Objects.requireNonNull(compilation, "compilation");
      if (context == null && (reasonCode == null || reasonCode.isBlank())) {
        throw new IllegalArgumentException("Unavailable resolution requires reasonCode.");
      }
      if (context != null && reasonCode != null) {
        throw new IllegalArgumentException("Available resolution must not have reasonCode.");
      }
    }

    public boolean available() {
      return context != null;
    }
  }
}
