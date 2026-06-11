package com.daf360.rh.profiles;

import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.profile.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full lifecycle integration test — Testcontainers SQL Server 2022.
 * Requires Docker. Runs: mvn test -Dtest=EmployeeProfileIntegrationTest
 *
 * Uses MockMvcBuilders.webAppContextSetup() instead of @AutoConfigureMockMvc
 * (removed in Spring Boot 4.0).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeProfileIntegrationTest {

    @SuppressWarnings({"resource", "unused"})
    @Container
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense()
                    .withPassword("TestStr0ng!Pass1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        r.add("spring.datasource.username", MSSQL::getUsername);
        r.add("spring.datasource.password", MSSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration," +
                      "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration");
        r.add("app.storage-path", () -> System.getProperty("java.io.tmpdir") + "/hr-test");
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper          objectMapper;
    @Autowired JdbcTemplate          jdbcTemplate;

    MockMvc mockMvc;
    Long    createdId;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeAll
    void seedSchema() {
        jdbcTemplate.execute("""
            IF OBJECT_ID('dbo.pays', 'U') IS NULL
            CREATE TABLE [dbo].[pays] (
                id            BIGINT NOT NULL IDENTITY(1,1),
                iso_code      VARCHAR(3)   NOT NULL,
                french_label  VARCHAR(255) NOT NULL,
                english_label VARCHAR(255) NOT NULL,
                deleted       BIT NOT NULL DEFAULT 0,
                created_at    DATETIME2,
                updated_at    DATETIME2,
                deleted_at    DATETIME2,
                CONSTRAINT PK_pays PRIMARY KEY (id)
            )
            """);

        jdbcTemplate.execute("""
            IF OBJECT_ID('dbo.Users', 'U') IS NULL
            CREATE TABLE [dbo].[Users] (
                id          BIGINT NOT NULL IDENTITY(1,1),
                username    VARCHAR(255) NOT NULL,
                email       VARCHAR(255),
                fullName    VARCHAR(255),
                password    VARCHAR(255),
                isActive    BIT,
                pays_id     BIGINT NOT NULL DEFAULT 1,
                role_id     BIGINT,
                employee_id VARCHAR(30),
                CONSTRAINT PK_Users PRIMARY KEY (id)
            )
            """);

        jdbcTemplate.execute("""
            IF NOT EXISTS (SELECT 1 FROM [dbo].[pays])
            INSERT INTO [dbo].[pays] (iso_code, french_label, english_label, deleted, created_at)
            VALUES ('TN', 'Tunisie', 'Tunisia', 0, GETDATE())
            """);

        jdbcTemplate.execute("""
            IF NOT EXISTS (SELECT 1 FROM [dbo].[Users])
            BEGIN
              SET IDENTITY_INSERT [dbo].[Users] ON;
              INSERT INTO [dbo].[Users] (id, username, email, fullName, isActive, pays_id)
              VALUES (1, 'test.user', 'test@arx.local', 'Test User', 1, 1);
              SET IDENTITY_INSERT [dbo].[Users] OFF;
            END
            """);
    }

    @BeforeEach
    void setupMockMvc() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EmployeeProfileCreateDto validCreateDto() {
        EmployeeProfileCreateDto dto = new EmployeeProfileCreateDto();
        dto.setUserId(1L);
        dto.setPaysId(1L);
        dto.setEmployeeId("ARX-26-0001");
        dto.setHireDate(LocalDate.of(2024, 1, 15));
        dto.setContractType("CDI");
        // department/grade are now FK IDs after V23 — use setDepartmentId/setGradeId in real tests
        dto.setDepartmentId(null);
        dto.setGradeId(null);
        dto.setPersonalEmail("test@arx.local");
        dto.setPhone("+21611111111");
        return dto;
    }

    private void transition(LifecycleStatus next, String reason) throws Exception {
        LifecycleTransitionDto dto = new LifecycleTransitionDto();
        dto.setNewStatus(next);
        dto.setReason(reason);
        mockMvc.perform(post("/api/hr/profiles/{id}/lifecycle", createdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test @Order(1)
    @WithMockUser(roles = "HR_MANAGER")
    void createProfile_returns201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/hr/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateDto()))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lifecycleStatus").value("PRE_ONBOARDING"))
                .andExpect(jsonPath("$.department").value("Ingénierie"))
                .andReturn();

        EmployeeProfileResponseDto resp = objectMapper.readValue(
                result.getResponse().getContentAsString(), EmployeeProfileResponseDto.class);
        createdId = resp.getId();
        assertThat(createdId).isNotNull();
    }

    @Test @Order(2)
    @WithMockUser(roles = "HR_VIEWER")
    void getProfile_viewerSeesNoSensitiveFields() throws Exception {
        assertThat(createdId).isNotNull();
        mockMvc.perform(get("/api/hr/profiles/{id}", createdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId))
                .andExpect(jsonPath("$.iban").doesNotExist())
                .andExpect(jsonPath("$.socialSecurityNumber").doesNotExist());
    }

    @Test @Order(3)
    @WithMockUser(roles = "HR_MANAGER")
    void patchProfile_onlyNonNullFieldsUpdated() throws Exception {
        assertThat(createdId).isNotNull();
        EmployeeProfileUpdateDto dto = new EmployeeProfileUpdateDto();
        // grade is now a FK ID after V23 — use gradeId; text assertions use label resolved by backend
        dto.setIban("TN5904001000013901023456");
        dto.setReason("Promotion");

        mockMvc.perform(patch("/api/hr/profiles/{id}", createdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value("TN5904001000013901023456"));
    }

    @Test @Order(4)
    @WithMockUser(roles = "HR_MANAGER")
    void lifecycle_preOnboarding_to_active() throws Exception {
        transition(LifecycleStatus.ACTIVE, "Onboarding done");
        mockMvc.perform(get("/api/hr/profiles/{id}", createdId))
                .andExpect(jsonPath("$.lifecycleStatus").value("ACTIVE"));
    }

    @Test @Order(5)
    @WithMockUser(roles = "HR_MANAGER")
    void lifecycle_active_to_offboarding() throws Exception {
        transition(LifecycleStatus.OFFBOARDING, "Resignation");
        mockMvc.perform(get("/api/hr/profiles/{id}", createdId))
                .andExpect(jsonPath("$.lifecycleStatus").value("OFFBOARDING"));
    }

    @Test @Order(6)
    @WithMockUser(roles = "HR_MANAGER")
    void lifecycle_offboarding_to_terminated_to_archived_pseudonymisesPii() throws Exception {
        transition(LifecycleStatus.TERMINATED, "Fin contrat");
        transition(LifecycleStatus.ARCHIVED, "RGPD");

        mockMvc.perform(get("/api/hr/profiles/{id}", createdId))
                .andExpect(jsonPath("$.lifecycleStatus").value("ARCHIVED"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.personalEmail").doesNotExist());
    }

    @Test @Order(7)
    @WithMockUser(roles = "HR_MANAGER")
    void lifecycle_archivedToActive_returns422() throws Exception {
        LifecycleTransitionDto dto = new LifecycleTransitionDto();
        dto.setNewStatus(LifecycleStatus.ACTIVE);
        dto.setReason("Illegal");
        mockMvc.perform(post("/api/hr/profiles/{id}/lifecycle", createdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test @Order(8)
    @WithMockUser(roles = "HR_MANAGER")
    void createProfile_badEmployeeIdFormat_returns400() throws Exception {
        EmployeeProfileCreateDto dto = validCreateDto();
        dto.setUserId(99L);
        dto.setEmployeeId("invalid-format");
        mockMvc.perform(post("/api/hr/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(9)
    @WithMockUser(roles = "HR_MANAGER")
    void createProfile_cddNoEndDate_returns400() throws Exception {
        EmployeeProfileCreateDto dto = validCreateDto();
        dto.setUserId(99L);
        dto.setEmployeeId("ARX-26-0099");
        dto.setContractType("CDD");
        dto.setContractEndDate(null);
        mockMvc.perform(post("/api/hr/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(10)
    @WithMockUser(roles = "HR_VIEWER")
    void createProfile_viewerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/hr/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateDto()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
