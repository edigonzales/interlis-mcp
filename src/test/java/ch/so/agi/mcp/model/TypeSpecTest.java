package ch.so.agi.mcp.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
