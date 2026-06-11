package com.daf360.rh.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates employee ID format: {@code <ENTITY>-<YY>-<NNNN>}
 * Examples: ARX-26-0001, ITEC-26-0142
 */
@Documented
@Constraint(validatedBy = EmployeeIdValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmployeeId {
    String message() default "Format d'identifiant invalide — attendu: <ENTITE>-<AA>-<NNNN> (ex: ARX-26-0001)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
