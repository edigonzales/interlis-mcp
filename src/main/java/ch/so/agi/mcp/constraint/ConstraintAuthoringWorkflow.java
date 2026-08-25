package ch.so.agi.mcp.constraint;

import ch.so.agi.mcp.service.IliCompilerService;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Shared two-compile and source-edit workflow for typed constraint authoring. */
@Component
public final class ConstraintAuthoringWorkflow {

  private final IliCompilerService compilerService;
  private final ConstraintContextService contextService;
  private final ConstraintSourceEditService sourceEditService;

  @Autowired
  public ConstraintAuthoringWorkflow(
      IliCompilerService compilerService,
      ConstraintContextService contextService,
      ConstraintSourceEditService sourceEditService) {
    this.compilerService = compilerService;
    this.contextService = contextService;
    this.sourceEditService = sourceEditService;
  }

  public ConstraintAuthoringWorkflow(IliCompilerService compilerService) {
    this(
        compilerService,
        new ConstraintContextService(compilerService),
        new ConstraintSourceEditService());
  }

  public IliCompilerService.CompilationResult compileBefore(String modelText, String tempPrefix) {
    return compilerService.compile(modelText, null, tempPrefix);
  }

  public IliCompilerService compilerService() {
    return compilerService;
  }

  public PreparedConstraint insertAndResolve(
      String modelText,
      IliCompilerService.CompilationResult beforeCompilation,
      String contextFqn,
      String constraintBlock,
      String constraintFqn,
      String afterTempPrefix) {
    return insertAndResolve(
        modelText,
        beforeCompilation,
        contextFqn,
        constraintBlock,
        constraintFqn,
        afterTempPrefix,
        Set.of());
  }

  public PreparedConstraint insertAndResolve(
      String modelText,
      IliCompilerService.CompilationResult beforeCompilation,
      String contextFqn,
      String constraintBlock,
      String constraintFqn,
      String afterTempPrefix,
      Set<String> requiredImports) {
    ConstraintSourceEditService.PreparedInsertion insertion = sourceEditService.insertConstraintBlock(
        modelText,
        beforeCompilation,
        contextFqn,
        constraintBlock,
        requiredImports);
    ConstraintContextService.Resolution resolution = contextService.compileAndResolve(
        insertion.updatedModelText(),
        constraintFqn,
        null,
        afterTempPrefix);
    return new PreparedConstraint(insertion, resolution);
  }

  public record PreparedConstraint(
      ConstraintSourceEditService.PreparedInsertion insertion,
      ConstraintContextService.Resolution resolution) {
  }
}
