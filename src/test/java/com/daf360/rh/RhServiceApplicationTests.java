package com.daf360.rh;

import org.junit.jupiter.api.Test;

/**
 * Placeholder smoke test — no Spring context.
 *
 * Loading the full context requires SQL Server + RabbitMQ + mail.
 * Real integration tests (with Testcontainers) live in:
 *   src/test/.../profiles/EmployeeProfileIntegrationTest.java
 *
 * Run full stack with: mvn test -Dtest=EmployeeProfileIntegrationTest
 */
class RhServiceApplicationTests {

    @Test
    void applicationClassIsInstantiable() {
        new RhServiceApplication();
    }
}
