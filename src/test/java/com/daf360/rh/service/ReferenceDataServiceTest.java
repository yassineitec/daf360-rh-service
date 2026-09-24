package com.daf360.rh.service;

import com.daf360.rh.domain.ContractType;
import com.daf360.rh.dto.ref.RefDataItemDto;
import com.daf360.rh.repository.ContractTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for {@link ReferenceDataService}'s contract-types methods.
 * No Spring context — all collaborators are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class ReferenceDataServiceTest {

    @Mock ContractTypeRepository contractTypeRepo;

    @InjectMocks ReferenceDataService service;

    @Test
    void getContractTypes_withPaysIds_filtersByPaysAndMapsToDto() {
        ContractType cdi = ContractType.builder()
                .id(1L).paysId(179L).code("CDI").labelFr("CDI").labelEn("Permanent contract")
                .displayOrder(1).isActive(true).build();
        ContractType civp = ContractType.builder()
                .id(2L).paysId(179L).code("CIVP").labelFr("CIVP").labelEn("CIVP (Tunisia)")
                .displayOrder(3).isActive(true).build();
        when(contractTypeRepo.findByPaysIdInAndIsActiveTrueOrderByDisplayOrderAsc(List.of(179L)))
                .thenReturn(List.of(cdi, civp));

        List<RefDataItemDto> result = service.getContractTypes(List.of(179L));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCode()).isEqualTo("CDI");
        assertThat(result.get(0).getLabelEn()).isEqualTo("Permanent contract");
        assertThat(result.get(1).getCode()).isEqualTo("CIVP");
        verify(contractTypeRepo).findByPaysIdInAndIsActiveTrueOrderByDisplayOrderAsc(List.of(179L));
        verifyNoMoreInteractions(contractTypeRepo);
    }

    @Test
    void getContractTypes_nullPaysIds_fetchesUnfiltered() {
        when(contractTypeRepo.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());

        List<RefDataItemDto> result = service.getContractTypes(null);

        assertThat(result).isEmpty();
        verify(contractTypeRepo).findByIsActiveTrueOrderByDisplayOrderAsc();
        verifyNoMoreInteractions(contractTypeRepo);
    }
}
