package com.daf360.rh.sharepoint;

import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeFolderResolverTest {

    @Mock JdbcTemplate jdbc;
    @InjectMocks EmployeeFolderResolver resolver;

    @Test
    void normalize_collapsesMultipleSpacesAndTrims() {
        assertThat(resolver.normalize("  Abir   ESSAYEM  ")).isEqualTo("Abir ESSAYEM");
    }

    @Test
    void normalize_returnsNullForBlankOrNullInput() {
        assertThat(resolver.normalize("   ")).isNull();
        assertThat(resolver.normalize(null)).isNull();
    }

    @Test
    void isAmbiguous_trueWhenTwoEmployeesShareSameFullNameInSamePays() {
        when(jdbc.queryForObject(eq(
                "SELECT COUNT(DISTINCT ep.id) FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.pays_id = ? AND UPPER(LTRIM(RTRIM(u.fullName))) = UPPER(?)"),
                eq(Integer.class), eq(3L), eq("Jean DUPONT")))
                .thenReturn(2);

        assertThat(resolver.isAmbiguous("Jean DUPONT", 3L)).isTrue();
    }

    @Test
    void isAmbiguous_falseWhenOnlyOneMatch() {
        when(jdbc.queryForObject(eq(
                "SELECT COUNT(DISTINCT ep.id) FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.pays_id = ? AND UPPER(LTRIM(RTRIM(u.fullName))) = UPPER(?)"),
                eq(Integer.class), eq(3L), eq("Jean DUPONT")))
                .thenReturn(1);

        assertThat(resolver.isAmbiguous("Jean DUPONT", 3L)).isFalse();
    }

    @Test
    void isAmbiguous_falseWhenEmployeeFolderIsNull_withoutQueryingJdbc() {
        assertThat(resolver.isAmbiguous(null, 3L)).isFalse();

        verifyNoInteractions(jdbc);
    }
}
