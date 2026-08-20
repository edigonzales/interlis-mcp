package ch.so.agi.mcp.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseTypeTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validate_allowsTextWithoutLength() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.TEXT);
        assertDoesNotThrow(baseType::validate);
    }

    @Test
    void validate_rejectsTextWithInvalidLength() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.TEXT);
        baseType.setLength(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("length"));
    }

    @Test
    void validate_numericRangeRequiresBounds() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(new BigDecimal("5.0"));
        baseType.setMax(new BigDecimal("10.0"));
        assertDoesNotThrow(baseType::validate);
    }

    @Test
    void validate_numericAllowsBare() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);
        assertDoesNotThrow(baseType::validate);
    }

    @Test
    void validate_numericRejectsBounds() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);
        baseType.setMin(new BigDecimal("0.0"));
        baseType.setMax(new BigDecimal("10.0"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("NUMERIC must not define 'min'/'max'"));
    }

    @Test
    void validate_numericRangeRejectsCircular() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(new BigDecimal("0.0"));
        baseType.setMax(new BigDecimal("5.0"));
        baseType.setCircular(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("circular"));
    }

    @Test
    void validate_numericRangeRejectsRefSys() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(new BigDecimal("0.0"));
        baseType.setMax(new BigDecimal("5.0"));
        baseType.setRefSysFqn("Model.Topic.RefSys");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("refSysFqn"));
    }

    @Test
    void validate_numericRangeRejectsInvalidOrder() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUM_RANGE);
        baseType.setMin(new BigDecimal("10.0"));
        baseType.setMax(new BigDecimal("5.0"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("min < max"));
    }

    @Test
    void validate_requiresKind() {
        BaseType baseType = new BaseType();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("baseType.kind"));
    }

    @Test
    void validate_unitFqnAcceptsDotSeparated() {
        BaseType baseType = new BaseType();
        baseType.setUnitFqn("Model.Part");

        assertTrue(VALIDATOR.validate(baseType).isEmpty());
    }

    @Test
    void validate_unitFqnRejectsBackslashSeparator() {
        BaseType baseType = new BaseType();
        baseType.setUnitFqn("Model\\Part");

        assertFalse(VALIDATOR.validate(baseType).isEmpty());
    }

    @Test
    void validate_rejectsIrrelevantFieldsForBoolean() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.BOOLEAN);
        baseType.setLength(4);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("does not support"));
    }

    @Test
    void validate_rejectsUnitForText() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.TEXT);
        baseType.setUnitFqn("INTERLIS.m");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, baseType::validate);
        assertTrue(ex.getMessage().contains("only supports"));
    }

    @Test
    void validate_rejectsMalformedFqnWithoutBeanValidation() {
        BaseType baseType = new BaseType();
        baseType.setKind(BaseType.Kind.NUMERIC);
        baseType.setUnitFqn("Model\\Part");

        assertThrows(IllegalArgumentException.class, baseType::validate);
    }
}
