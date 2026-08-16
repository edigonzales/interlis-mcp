package ch.so.agi.mcp.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeSpecTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void domainFqn_acceptsDotSeparated() {
        TypeSpec typeSpec = new TypeSpec();
        typeSpec.setDomainFqn("Model.Part");

        assertTrue(VALIDATOR.validate(typeSpec).isEmpty());
    }

    @Test
    void domainFqn_rejectsBackslashSeparator() {
        TypeSpec typeSpec = new TypeSpec();
        typeSpec.setDomainFqn("Model\\Part");

        assertFalse(VALIDATOR.validate(typeSpec).isEmpty());
    }

    @Test
    void requireSingleType_returnsSelectedType() {
        TypeSpec typeSpec = new TypeSpec();
        BaseType baseType = new BaseType();
        typeSpec.setBaseType(baseType);

        assertSame(baseType, typeSpec.requireSingleType());
    }

    @Test
    void requireSingleType_rejectsMissingType() {
        assertThrows(IllegalArgumentException.class, new TypeSpec()::requireSingleType);
    }

    @Test
    void requireSingleType_rejectsMultipleTypes() {
        TypeSpec typeSpec = new TypeSpec();
        typeSpec.setDomainFqn("Model.Part");
        typeSpec.setBaseType(new BaseType());

        assertThrows(IllegalArgumentException.class, typeSpec::requireSingleType);
    }
}
