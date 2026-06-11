package com.daf360.rh.mapper;

import com.daf360.rh.domain.EmployeeDocument;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EmployeeDocumentMapper {
    DocumentUploadResponseDto toDto(EmployeeDocument doc);
}
