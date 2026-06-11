package com.daf360.rh.lists;

import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for {@link ConfigurableListService}.
 * No Spring context — all collaborators are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class ConfigurableListServiceTest {

    @Mock ConfigurableListTypeRepository  typeRepo;
    @Mock ConfigurableListValueRepository valueRepo;
    @Mock ConfigurableListMapper          mapper;
    @Mock AuditService                    auditService;

    @InjectMocks ConfigurableListService service;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final Long   TYPE_ID  = 1L;
    private static final Long   VALUE_ID = 10L;
    private static final Long   ACTOR_ID = 42L;

    private ConfigurableListType  globalType;
    private ConfigurableListValue systemValue;
    private ConfigurableListValue userValue;

    @BeforeEach
    void setUp() {
        globalType = ConfigurableListType.builder()
                .id(TYPE_ID)
                .code("GENDER")
                .labelFr("Genre")
                .labelEn("Gender")
                .isPerPays(false)
                .isSystem(true)
                .build();

        systemValue = ConfigurableListValue.builder()
                .id(VALUE_ID)
                .listTypeId(TYPE_ID)
                .valueCode("M")
                .labelFr("Masculin")
                .labelEn("Male")
                .sortOrder(0)
                .isActive(true)
                .isSystem(true)
                .createdAt(OffsetDateTime.now())
                .build();

        userValue = ConfigurableListValue.builder()
                .id(20L)
                .listTypeId(TYPE_ID)
                .valueCode("CUSTOM")
                .labelFr("Personnalisé")
                .labelEn("Custom")
                .sortOrder(5)
                .isActive(true)
                .isSystem(false)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    // ── deleteListValue ───────────────────────────────────────────────────────

    @Test
    void deleteListValue_systemValue_throwsInvalidTransition() {
        when(valueRepo.findById(VALUE_ID)).thenReturn(Optional.of(systemValue));

        assertThatThrownBy(() -> service.deleteListValue(VALUE_ID, ACTOR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSITION));

        verify(valueRepo, never()).delete(any());
    }

    @Test
    void deleteListValue_userValue_success() {
        when(valueRepo.findById(20L)).thenReturn(Optional.of(userValue));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(globalType));

        service.deleteListValue(20L, ACTOR_ID);

        verify(valueRepo).delete(userValue);
        verify(auditService).log(
                eq(ACTOR_ID.toString()),
                eq("DELETE_LIST_VALUE"),
                eq("ConfigurableListValue"),
                eq(20L),
                anyString(),
                isNull()
        );
    }

    @Test
    void deleteListValue_notFound_throwsNotFound() {
        when(valueRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteListValue(99L, ACTOR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        verify(valueRepo, never()).delete(any());
    }

    // ── getListValues ─────────────────────────────────────────────────────────

    @Test
    void getListValues_returnsValues() {
        when(typeRepo.findByCode("GENDER")).thenReturn(Optional.of(globalType));
        when(valueRepo.findByListTypeIdAndPaysIdIsNullAndIsActiveTrueOrderBySortOrderAsc(TYPE_ID))
                .thenReturn(List.of(systemValue, userValue));

        List<ListValueResponse> result = service.getListValues("GENDER", null);

        assertThat(result).hasSize(2);
        // Verify the global (non-per-pays) repository method was used
        verify(valueRepo).findByListTypeIdAndPaysIdIsNullAndIsActiveTrueOrderBySortOrderAsc(TYPE_ID);
        verify(valueRepo, never()).findActiveByListTypeAndPays(anyLong(), anyLong());
    }

    @Test
    void getListValues_cacheHitOnSecondCall() {
        when(typeRepo.findByCode("GENDER")).thenReturn(Optional.of(globalType));
        when(valueRepo.findByListTypeIdAndPaysIdIsNullAndIsActiveTrueOrderBySortOrderAsc(TYPE_ID))
                .thenReturn(List.of(systemValue));

        // First call — populates cache
        service.getListValues("GENDER", null);
        // Second call — must return from cache without hitting the repo again
        service.getListValues("GENDER", null);

        verify(typeRepo, times(1)).findByCode("GENDER");
        verify(valueRepo, times(1))
                .findByListTypeIdAndPaysIdIsNullAndIsActiveTrueOrderBySortOrderAsc(TYPE_ID);
    }

    // ── updateListValue ───────────────────────────────────────────────────────

    @Test
    void updateListValue_deactivateSystem_withoutForce_throwsInvalidTransition() {
        when(valueRepo.findById(VALUE_ID)).thenReturn(Optional.of(systemValue));

        UpdateListValueRequest dto = new UpdateListValueRequest();
        dto.setIsActive(false);
        // forceDeactivate is null → treated as false by Boolean.TRUE.equals(null) check

        assertThatThrownBy(() -> service.updateListValue(VALUE_ID, dto, ACTOR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSITION));

        verify(valueRepo, never()).save(any());
    }

    @Test
    void updateListValue_deactivateSystem_withForce_succeeds() {
        when(valueRepo.findById(VALUE_ID)).thenReturn(Optional.of(systemValue));
        when(valueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(globalType));

        UpdateListValueRequest dto = new UpdateListValueRequest();
        dto.setIsActive(false);
        dto.setForceDeactivate(true);

        // Must not throw
        assertThatNoException().isThrownBy(() -> service.updateListValue(VALUE_ID, dto, ACTOR_ID));

        verify(valueRepo).save(argThat(v -> Boolean.FALSE.equals(v.getIsActive())));
        verify(auditService).log(
                eq(ACTOR_ID.toString()),
                eq("UPDATE_LIST_VALUE"),
                eq("ConfigurableListValue"),
                eq(VALUE_ID),
                anyString(),
                anyString()
        );
    }
}
