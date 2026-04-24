package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.Document;
import com.daf360.rh.domain.enums.DocumentType;
import com.daf360.rh.dto.DocumentResponseDto;
import com.daf360.rh.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public List<DocumentResponseDto> findByEmployee(Long employeeId) {
        return documentRepository.findByEmployeeIdAndIsArchivedFalse(employeeId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public DocumentResponseDto upload(Long employeeId, DocumentType docType,
                                      boolean confidential, MultipartFile file,
                                      String actorId) throws IOException {
        // BR07: version management — archive previous version
        Optional<Document> existing = documentRepository
                .findTopByEmployeeIdAndDocumentTypeAndIsArchivedFalseOrderByVersionDesc(employeeId, docType);

        int newVersion = 1;
        if (existing.isPresent()) {
            Document old = existing.get();
            old.setIsArchived(true);
            old.setArchivePath(old.getStoragePath());
            documentRepository.save(old);
            newVersion = old.getVersion() + 1;
        }

        String storagePath = storeFile(employeeId, file);

        Document doc = Document.builder()
                .employeeId(employeeId)
                .documentType(docType)
                .fileName(file.getOriginalFilename())
                .version(newVersion)
                .storagePath(storagePath)
                .confidential(confidential)
                .isArchived(false)
                .build();

        return toDto(documentRepository.save(doc));
    }

    private String storeFile(Long employeeId, MultipartFile file) throws IOException {
        Path dir = Paths.get(appProperties.getStoragePath(), "employees", employeeId.toString());
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path target = dir.resolve(filename);
        Files.copy(file.getInputStream(), target);
        return target.toString();
    }

    public DocumentResponseDto toDto(Document d) {
        DocumentResponseDto dto = new DocumentResponseDto();
        dto.setId(d.getId());
        dto.setEmployeeId(d.getEmployeeId());
        dto.setDocumentType(d.getDocumentType());
        dto.setFileName(d.getFileName());
        dto.setVersion(d.getVersion());
        dto.setConfidential(d.getConfidential());
        dto.setCreatedAt(d.getCreatedAt());
        dto.setCreatedBy(d.getCreatedBy());
        return dto;
    }
}
