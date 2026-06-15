# RH DAF360 — Recrutement, Cycle de vie & Demandes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implémenter 3 modules RH complets : pipeline de recrutement, cycle de vie employé (essai/titulaire/démission) et gestion des demandes — backend Spring Boot 4 (port 8081) + frontend Angular 21 shell.

**Architecture:** Le backend suit les patterns existants de `daf360-rh-service` : entités JPA étendant `AuditableEntity`, repositories Spring Data, couche service avec règles métier, contrôleurs REST avec sécurité basée sur les rôles. Le frontend suit les composants standalone Angular 21 avec Signals, CSS plain aligné sur le design system DAF360 existant (Manrope, `--color-primary: #1C4E5C`).

**Tech Stack:** Java 17, Spring Boot 4.0.5, SQL Server, Hibernate JPA, Spring Mail (Office365), Angular 21, TypeScript, HttpClient, Angular Signals.

**Sprint :** 15 jours ouvrés — du 26 mai au 13 juin 2026

---

## Fichiers à créer / modifier

### Backend — Nouveaux fichiers

```
src/main/java/com/daf360/rh/
├── domain/
│   ├── Candidate.java
│   ├── Interview.java
│   ├── JobOffer.java
│   ├── EmployeeRequest.java
│   └── enums/
│       ├── CandidateStatus.java
│       ├── InterviewType.java
│       ├── InterviewOutcome.java
│       ├── OfferStatus.java
│       ├── EmploymentLifecycleStage.java
│       ├── RequestType.java
│       └── RequestStatus.java
├── repository/
│   ├── CandidateRepository.java
│   ├── InterviewRepository.java
│   ├── JobOfferRepository.java
│   └── EmployeeRequestRepository.java
├── dto/
│   ├── CandidateRequestDto.java
│   ├── CandidateResponseDto.java
│   ├── InterviewCreateDto.java
│   ├── InterviewResponseDto.java
│   ├── OfferCreateDto.java
│   ├── OfferResponseDto.java
│   ├── LifecycleTransitionDto.java
│   ├── EmployeeRequestCreateDto.java
│   └── EmployeeRequestResponseDto.java
├── service/
│   ├── CandidateService.java
│   ├── InterviewService.java
│   ├── OfferService.java
│   ├── EmployeeLifecycleService.java
│   ├── EmployeeRequestService.java
│   └── HrNotificationService.java
└── controller/
    ├── RecruitmentController.java
    ├── EmployeeLifecycleController.java
    └── EmployeeRequestController.java
```

### Backend — Fichiers à modifier

- `domain/Employee.java` — ajouter `lifecycleStage`, `trialEndDate`, `resignedAt`
- `scheduler/HrScheduler.java` — ajouter job détection fin période d'essai
- `config/SecurityConfig.java` — ouvrir les nouveaux endpoints

### Frontend — Nouveaux fichiers

```
src/app/
├── core/rh/
│   ├── rh.model.ts
│   └── rh.service.ts
└── pages/rh/
    ├── recruitment/
    │   ├── candidate-list/  (.ts .html .css)
    │   ├── candidate-detail/ (.ts .html .css)
    │   ├── candidate-form/  (.ts .html .css)
    │   ├── interview-form/  (.ts .html .css)
    │   └── offer-form/      (.ts .html .css)
    ├── lifecycle/
    │   └── employee-lifecycle/ (.ts .html .css)
    └── requests/
        ├── request-list/   (.ts .html .css)
        ├── request-form/   (.ts .html .css)
        └── request-detail/ (.ts .html .css)
```

### Frontend — Fichiers à modifier

- `app.routes.ts` — ajouter routes `/rh/*`
- `layout/sidebar/sidebar.html` — liens de navigation RH

---

## JOUR 1-2 (26-27 mai) — Task 1 : Entités Recrutement Backend

**Files:**
- Create: `src/main/java/com/daf360/rh/domain/enums/CandidateStatus.java`
- Create: `src/main/java/com/daf360/rh/domain/enums/InterviewType.java`
- Create: `src/main/java/com/daf360/rh/domain/enums/InterviewOutcome.java`
- Create: `src/main/java/com/daf360/rh/domain/enums/OfferStatus.java`
- Create: `src/main/java/com/daf360/rh/domain/Candidate.java`
- Create: `src/main/java/com/daf360/rh/domain/Interview.java`
- Create: `src/main/java/com/daf360/rh/domain/JobOffer.java`
- Create: `src/main/java/com/daf360/rh/repository/CandidateRepository.java`
- Create: `src/main/java/com/daf360/rh/repository/InterviewRepository.java`
- Create: `src/main/java/com/daf360/rh/repository/JobOfferRepository.java`
- Test: `src/test/java/com/daf360/rh/repository/CandidateRepositoryTest.java`

- [ ] **Step 1 : Créer les enums**

```java
// CandidateStatus.java
package com.daf360.rh.domain.enums;
public enum CandidateStatus {
    NEW, IN_PROGRESS, OFFERED, HIRED, REJECTED
}

// InterviewType.java
package com.daf360.rh.domain.enums;
public enum InterviewType {
    RH, TECHNICAL, MANAGER, FINAL
}

// InterviewOutcome.java
package com.daf360.rh.domain.enums;
public enum InterviewOutcome {
    PENDING, PASSED, FAILED
}

// OfferStatus.java
package com.daf360.rh.domain.enums;
public enum OfferStatus {
    SENT, ACCEPTED, REJECTED
}
```

- [ ] **Step 2 : Créer l'entité Candidate**

```java
// Candidate.java
package com.daf360.rh.domain;

import com.daf360.rh.audit.AuditableEntity;
import com.daf360.rh.domain.enums.CandidateStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@Entity
@Table(name = "candidates")
public class Candidate extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true, length = 200)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "applied_position", nullable = false, length = 200)
    private String appliedPosition;

    @Column(name = "cv_url", length = 500)
    private String cvUrl;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CandidateStatus status = CandidateStatus.NEW;
}
```

- [ ] **Step 3 : Créer l'entité Interview**

```java
// Interview.java
package com.daf360.rh.domain;

import com.daf360.rh.audit.AuditableEntity;
import com.daf360.rh.domain.enums.InterviewOutcome;
import com.daf360.rh.domain.enums.InterviewType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity
@Table(name = "interviews")
public class Interview extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_type", nullable = false, length = 20)
    private InterviewType interviewType;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "interviewer_id")
    private Long interviewerId;

    @Column(name = "round", nullable = false)
    private int round = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private InterviewOutcome outcome = InterviewOutcome.PENDING;

    @Column(name = "feedback", length = 2000)
    private String feedback;
}
```

- [ ] **Step 4 : Créer l'entité JobOffer**

```java
// JobOffer.java
package com.daf360.rh.domain;

import com.daf360.rh.audit.AuditableEntity;
import com.daf360.rh.domain.enums.OfferStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter
@Entity
@Table(name = "job_offers")
public class JobOffer extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false, unique = true)
    private Candidate candidate;

    @Column(name = "expected_hire_date")
    private LocalDate expectedHireDate;

    @Column(name = "proposed_salary", precision = 10, scale = 2)
    private BigDecimal proposedSalary;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OfferStatus status = OfferStatus.SENT;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
}
```

- [ ] **Step 5 : Créer les repositories**

```java
// CandidateRepository.java
package com.daf360.rh.repository;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.enums.CandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {
    List<Candidate> findByStatusOrderByCreatedAtDesc(CandidateStatus status);
    List<Candidate> findAllByOrderByCreatedAtDesc();
    Optional<Candidate> findByEmail(String email);
}

// InterviewRepository.java
package com.daf360.rh.repository;

import com.daf360.rh.domain.Interview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InterviewRepository extends JpaRepository<Interview, Long> {
    List<Interview> findByCandidateIdOrderByRoundAsc(Long candidateId);
    int countByCandidateId(Long candidateId);
}

// JobOfferRepository.java
package com.daf360.rh.repository;

import com.daf360.rh.domain.JobOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface JobOfferRepository extends JpaRepository<JobOffer, Long> {
    Optional<JobOffer> findByCandidateId(Long candidateId);
}
```

- [ ] **Step 6 : Écrire le test repository**

```java
// CandidateRepositoryTest.java
package com.daf360.rh.repository;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.enums.CandidateStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CandidateRepositoryTest {

    @Autowired CandidateRepository repo;

    @Test
    void findByStatus_returnsOnlyMatching() {
        Candidate c = new Candidate();
        c.setFirstName("Ali"); c.setLastName("Test");
        c.setEmail("ali@test.com"); c.setAppliedPosition("Dev");
        c.setStatus(CandidateStatus.IN_PROGRESS);
        repo.save(c);

        var results = repo.findByStatusOrderByCreatedAtDesc(CandidateStatus.IN_PROGRESS);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getEmail()).isEqualTo("ali@test.com");
    }
}
```

- [ ] **Step 7 : Lancer les tests**

```
cd c:\Users\ITEC2\OneDrive\Documents\projects\daf360-rh-service\rh-service
mvn test -Dtest=CandidateRepositoryTest -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8 : Commit**

```
git add src/main/java/com/daf360/rh/domain/enums/CandidateStatus.java
git add src/main/java/com/daf360/rh/domain/enums/InterviewType.java
git add src/main/java/com/daf360/rh/domain/enums/InterviewOutcome.java
git add src/main/java/com/daf360/rh/domain/enums/OfferStatus.java
git add src/main/java/com/daf360/rh/domain/Candidate.java
git add src/main/java/com/daf360/rh/domain/Interview.java
git add src/main/java/com/daf360/rh/domain/JobOffer.java
git add src/main/java/com/daf360/rh/repository/
git add src/test/java/com/daf360/rh/repository/CandidateRepositoryTest.java
git commit -m "feat(recruitment): add Candidate, Interview, JobOffer entities and repositories"
```

---

## JOUR 3-4 (28-29 mai) — Task 2 : Service et Controller Recrutement

**Files:**
- Create: `src/main/java/com/daf360/rh/dto/CandidateRequestDto.java`
- Create: `src/main/java/com/daf360/rh/dto/CandidateResponseDto.java`
- Create: `src/main/java/com/daf360/rh/dto/InterviewCreateDto.java`
- Create: `src/main/java/com/daf360/rh/dto/InterviewResponseDto.java`
- Create: `src/main/java/com/daf360/rh/dto/OfferCreateDto.java`
- Create: `src/main/java/com/daf360/rh/dto/OfferResponseDto.java`
- Create: `src/main/java/com/daf360/rh/service/CandidateService.java`
- Create: `src/main/java/com/daf360/rh/service/InterviewService.java`
- Create: `src/main/java/com/daf360/rh/service/OfferService.java`
- Create: `src/main/java/com/daf360/rh/controller/RecruitmentController.java`
- Test: `src/test/java/com/daf360/rh/service/CandidateServiceTest.java`

- [ ] **Step 1 : Créer les DTOs**

```java
// CandidateRequestDto.java
package com.daf360.rh.dto;
import lombok.Data;

@Data
public class CandidateRequestDto {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String appliedPosition;
    private String cvUrl;
    private String notes;
}

// CandidateResponseDto.java
package com.daf360.rh.dto;
import com.daf360.rh.domain.enums.CandidateStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class CandidateResponseDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String appliedPosition;
    private String cvUrl;
    private String notes;
    private CandidateStatus status;
    private LocalDateTime createdAt;
    private List<InterviewResponseDto> interviews;
    private OfferResponseDto offer;
}

// InterviewCreateDto.java
package com.daf360.rh.dto;
import com.daf360.rh.domain.enums.InterviewOutcome;
import com.daf360.rh.domain.enums.InterviewType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class InterviewCreateDto {
    private Long candidateId;
    private InterviewType interviewType;
    private LocalDateTime scheduledAt;
    private Long interviewerId;
}

// InterviewResponseDto.java
package com.daf360.rh.dto;
import com.daf360.rh.domain.enums.InterviewOutcome;
import com.daf360.rh.domain.enums.InterviewType;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
public class InterviewResponseDto {
    private Long id;
    private InterviewType interviewType;
    private LocalDateTime scheduledAt;
    private Long interviewerId;
    private int round;
    private InterviewOutcome outcome;
    private String feedback;
}

// OfferCreateDto.java
package com.daf360.rh.dto;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OfferCreateDto {
    private Long candidateId;
    private LocalDate expectedHireDate;
    private BigDecimal proposedSalary;
}

// OfferResponseDto.java
package com.daf360.rh.dto;
import com.daf360.rh.domain.enums.OfferStatus;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder
public class OfferResponseDto {
    private Long id;
    private LocalDate expectedHireDate;
    private BigDecimal proposedSalary;
    private LocalDateTime sentAt;
    private OfferStatus status;
    private String rejectionReason;
}
```

- [ ] **Step 2 : Créer CandidateService**

```java
// CandidateService.java
package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.dto.CandidateRequestDto;
import com.daf360.rh.dto.CandidateResponseDto;
import com.daf360.rh.dto.InterviewResponseDto;
import com.daf360.rh.dto.OfferResponseDto;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.InterviewRepository;
import com.daf360.rh.repository.JobOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CandidateService {

    private final CandidateRepository candidateRepository;
    private final InterviewRepository interviewRepository;
    private final JobOfferRepository jobOfferRepository;

    @Transactional
    public CandidateResponseDto createCandidate(CandidateRequestDto dto) {
        if (candidateRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new BusinessRuleException("Un candidat avec cet email existe déjà.");
        }
        Candidate candidate = new Candidate();
        candidate.setFirstName(dto.getFirstName());
        candidate.setLastName(dto.getLastName());
        candidate.setEmail(dto.getEmail());
        candidate.setPhone(dto.getPhone());
        candidate.setAppliedPosition(dto.getAppliedPosition());
        candidate.setCvUrl(dto.getCvUrl());
        candidate.setNotes(dto.getNotes());
        candidate.setStatus(CandidateStatus.NEW);
        return toDto(candidateRepository.save(candidate));
    }

    @Transactional(readOnly = true)
    public List<CandidateResponseDto> listAll() {
        return candidateRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CandidateResponseDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional
    public CandidateResponseDto updateStatus(Long id, CandidateStatus status) {
        Candidate c = findOrThrow(id);
        c.setStatus(status);
        return toDto(candidateRepository.save(c));
    }

    private Candidate findOrThrow(Long id) {
        return candidateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Candidat introuvable: " + id));
    }

    public CandidateResponseDto toDto(Candidate c) {
        List<InterviewResponseDto> interviews = interviewRepository
                .findByCandidateIdOrderByRoundAsc(c.getId())
                .stream().map(i -> InterviewResponseDto.builder()
                        .id(i.getId())
                        .interviewType(i.getInterviewType())
                        .scheduledAt(i.getScheduledAt())
                        .interviewerId(i.getInterviewerId())
                        .round(i.getRound())
                        .outcome(i.getOutcome())
                        .feedback(i.getFeedback())
                        .build())
                .collect(Collectors.toList());

        OfferResponseDto offer = jobOfferRepository.findByCandidateId(c.getId())
                .map(o -> OfferResponseDto.builder()
                        .id(o.getId())
                        .expectedHireDate(o.getExpectedHireDate())
                        .proposedSalary(o.getProposedSalary())
                        .sentAt(o.getSentAt())
                        .status(o.getStatus())
                        .rejectionReason(o.getRejectionReason())
                        .build())
                .orElse(null);

        return CandidateResponseDto.builder()
                .id(c.getId())
                .firstName(c.getFirstName())
                .lastName(c.getLastName())
                .email(c.getEmail())
                .phone(c.getPhone())
                .appliedPosition(c.getAppliedPosition())
                .cvUrl(c.getCvUrl())
                .notes(c.getNotes())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .interviews(interviews)
                .offer(offer)
                .build();
    }
}
```

- [ ] **Step 3 : Créer InterviewService et OfferService**

```java
// InterviewService.java
package com.daf360.rh.service;

import com.daf360.rh.domain.Interview;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.InterviewOutcome;
import com.daf360.rh.dto.InterviewCreateDto;
import com.daf360.rh.dto.InterviewResponseDto;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRepository interviewRepository;
    private final CandidateRepository candidateRepository;

    @Transactional
    public InterviewResponseDto scheduleInterview(InterviewCreateDto dto) {
        var candidate = candidateRepository.findById(dto.getCandidateId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidat introuvable: " + dto.getCandidateId()));

        int nextRound = interviewRepository.countByCandidateId(candidate.getId()) + 1;

        Interview interview = new Interview();
        interview.setCandidate(candidate);
        interview.setInterviewType(dto.getInterviewType());
        interview.setScheduledAt(dto.getScheduledAt());
        interview.setInterviewerId(dto.getInterviewerId());
        interview.setRound(nextRound);
        interview.setOutcome(InterviewOutcome.PENDING);

        candidate.setStatus(CandidateStatus.IN_PROGRESS);
        candidateRepository.save(candidate);

        Interview saved = interviewRepository.save(interview);
        return InterviewResponseDto.builder()
                .id(saved.getId()).interviewType(saved.getInterviewType())
                .scheduledAt(saved.getScheduledAt()).interviewerId(saved.getInterviewerId())
                .round(saved.getRound()).outcome(saved.getOutcome()).build();
    }

    @Transactional
    public InterviewResponseDto recordOutcome(Long interviewId, InterviewOutcome outcome, String feedback) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Entretien introuvable: " + interviewId));
        interview.setOutcome(outcome);
        interview.setFeedback(feedback);
        if (outcome == InterviewOutcome.FAILED) {
            interview.getCandidate().setStatus(CandidateStatus.REJECTED);
            candidateRepository.save(interview.getCandidate());
        }
        Interview saved = interviewRepository.save(interview);
        return InterviewResponseDto.builder()
                .id(saved.getId()).interviewType(saved.getInterviewType())
                .scheduledAt(saved.getScheduledAt()).interviewerId(saved.getInterviewerId())
                .round(saved.getRound()).outcome(saved.getOutcome()).feedback(saved.getFeedback()).build();
    }
}
```

```java
// OfferService.java
package com.daf360.rh.service;

import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.OfferStatus;
import com.daf360.rh.dto.OfferCreateDto;
import com.daf360.rh.dto.OfferResponseDto;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.JobOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OfferService {

    private final JobOfferRepository jobOfferRepository;
    private final CandidateRepository candidateRepository;
    private final HrNotificationService notificationService;

    @Transactional
    public OfferResponseDto sendOffer(OfferCreateDto dto) {
        var candidate = candidateRepository.findById(dto.getCandidateId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidat introuvable: " + dto.getCandidateId()));
        if (jobOfferRepository.findByCandidateId(candidate.getId()).isPresent()) {
            throw new BusinessRuleException("Une offre a déjà été envoyée à ce candidat.");
        }
        JobOffer offer = new JobOffer();
        offer.setCandidate(candidate);
        offer.setExpectedHireDate(dto.getExpectedHireDate());
        offer.setProposedSalary(dto.getProposedSalary());
        offer.setSentAt(LocalDateTime.now());
        offer.setStatus(OfferStatus.SENT);
        candidate.setStatus(CandidateStatus.OFFERED);
        candidateRepository.save(candidate);
        JobOffer saved = jobOfferRepository.save(offer);
        return toDto(saved);
    }

    @Transactional
    public OfferResponseDto acceptOffer(Long offerId) {
        JobOffer offer = findOrThrow(offerId);
        offer.setStatus(OfferStatus.ACCEPTED);
        offer.getCandidate().setStatus(CandidateStatus.HIRED);
        candidateRepository.save(offer.getCandidate());
        notificationService.notifyOfferAccepted(offer.getCandidate());
        return toDto(jobOfferRepository.save(offer));
    }

    @Transactional
    public OfferResponseDto rejectOffer(Long offerId, String reason) {
        JobOffer offer = findOrThrow(offerId);
        offer.setStatus(OfferStatus.REJECTED);
        offer.setRejectionReason(reason);
        offer.getCandidate().setStatus(CandidateStatus.REJECTED);
        candidateRepository.save(offer.getCandidate());
        return toDto(jobOfferRepository.save(offer));
    }

    private JobOffer findOrThrow(Long id) {
        return jobOfferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Offre introuvable: " + id));
    }

    private OfferResponseDto toDto(JobOffer o) {
        return OfferResponseDto.builder()
                .id(o.getId()).expectedHireDate(o.getExpectedHireDate())
                .proposedSalary(o.getProposedSalary()).sentAt(o.getSentAt())
                .status(o.getStatus()).rejectionReason(o.getRejectionReason()).build();
    }
}
```

- [ ] **Step 4 : Créer HrNotificationService**

```java
// HrNotificationService.java
package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HrNotificationService {

    private final JavaMailSender mailSender;

    public void notifyOfferAccepted(Candidate candidate) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo("rh@daf360.com");
            msg.setSubject("[DAF360 RH] Offre acceptée — " + candidate.getFirstName() + " " + candidate.getLastName());
            msg.setText("Le candidat " + candidate.getFirstName() + " " + candidate.getLastName()
                    + " (" + candidate.getEmail() + ") a accepté l'offre pour le poste : "
                    + candidate.getAppliedPosition() + ".\n\nVeuillez créer son profil employé complet.");
            mailSender.send(msg);
        } catch (Exception e) {
            log.warn("Impossible d'envoyer le mail de notification: {}", e.getMessage());
        }
    }

    public void notifyTrialPeriodEnded(Employee employee) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo("rh@daf360.com");
            msg.setSubject("[DAF360 RH] Fin de période d'essai — " + employee.getFirstName() + " " + employee.getLastName());
            msg.setText("La période d'essai de " + employee.getFirstName() + " " + employee.getLastName()
                    + " se termine aujourd'hui. Veuillez confirmer sa titularisation ou sa rupture de contrat.");
            mailSender.send(msg);
        } catch (Exception e) {
            log.warn("Impossible d'envoyer le mail de fin d'essai: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 5 : Créer RecruitmentController**

```java
// RecruitmentController.java
package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.InterviewOutcome;
import com.daf360.rh.dto.*;
import com.daf360.rh.service.CandidateService;
import com.daf360.rh.service.InterviewService;
import com.daf360.rh.service.OfferService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/hr/recruitment")
@RequiredArgsConstructor
public class RecruitmentController {

    private final CandidateService candidateService;
    private final InterviewService interviewService;
    private final OfferService offerService;

    // --- Candidates ---
    @GetMapping("/candidates")
    //@PreAuthorize("hasAnyRole('HR','ADMIN','MANAGER')")
    public List<CandidateResponseDto> listCandidates() {
        return candidateService.listAll();
    }

    @GetMapping("/candidates/{id}")
    //@PreAuthorize("hasAnyRole('HR','ADMIN','MANAGER')")
    public CandidateResponseDto getCandidate(@PathVariable Long id) {
        return candidateService.getById(id);
    }

    @PostMapping("/candidates")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<CandidateResponseDto> createCandidate(@RequestBody CandidateRequestDto dto) {
        return ResponseEntity.ok(candidateService.createCandidate(dto));
    }

    // --- Interviews ---
    @PostMapping("/interviews")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<InterviewResponseDto> scheduleInterview(@RequestBody InterviewCreateDto dto) {
        return ResponseEntity.ok(interviewService.scheduleInterview(dto));
    }

    @PutMapping("/interviews/{id}/outcome")
    //@PreAuthorize("hasAnyRole('HR','ADMIN','MANAGER')")
    public ResponseEntity<InterviewResponseDto> recordOutcome(
            @PathVariable Long id,
            @RequestBody OutcomeRequest req) {
        return ResponseEntity.ok(interviewService.recordOutcome(id, req.getOutcome(), req.getFeedback()));
    }

    // --- Offers ---
    @PostMapping("/offers")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<OfferResponseDto> sendOffer(@RequestBody OfferCreateDto dto) {
        return ResponseEntity.ok(offerService.sendOffer(dto));
    }

    @PutMapping("/offers/{id}/accept")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<OfferResponseDto> acceptOffer(@PathVariable Long id) {
        return ResponseEntity.ok(offerService.acceptOffer(id));
    }

    @PutMapping("/offers/{id}/reject")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<OfferResponseDto> rejectOffer(@PathVariable Long id, @RequestBody RejectRequest req) {
        return ResponseEntity.ok(offerService.rejectOffer(id, req.getReason()));
    }

    @Data static class OutcomeRequest { private InterviewOutcome outcome; private String feedback; }
    @Data static class RejectRequest { private String reason; }
}
```

- [ ] **Step 6 : Écrire le test service**

```java
// CandidateServiceTest.java
package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.dto.CandidateRequestDto;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.InterviewRepository;
import com.daf360.rh.repository.JobOfferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateServiceTest {

    @Mock CandidateRepository candidateRepository;
    @Mock InterviewRepository interviewRepository;
    @Mock JobOfferRepository jobOfferRepository;
    @InjectMocks CandidateService service;

    @Test
    void createCandidate_duplicateEmail_throwsBusinessRule() {
        when(candidateRepository.findByEmail("a@b.com")).thenReturn(Optional.of(new Candidate()));
        CandidateRequestDto dto = new CandidateRequestDto();
        dto.setEmail("a@b.com"); dto.setFirstName("A"); dto.setLastName("B"); dto.setAppliedPosition("Dev");
        assertThatThrownBy(() -> service.createCandidate(dto))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createCandidate_newEmail_savesWithStatusNew() {
        when(candidateRepository.findByEmail("new@b.com")).thenReturn(Optional.empty());
        Candidate saved = new Candidate();
        saved.setId(1L); saved.setFirstName("Ali"); saved.setLastName("Test");
        saved.setEmail("new@b.com"); saved.setAppliedPosition("Dev"); saved.setStatus(CandidateStatus.NEW);
        when(candidateRepository.save(any())).thenReturn(saved);
        when(interviewRepository.findByCandidateIdOrderByRoundAsc(1L)).thenReturn(List.of());
        when(jobOfferRepository.findByCandidateId(1L)).thenReturn(Optional.empty());

        CandidateRequestDto dto = new CandidateRequestDto();
        dto.setEmail("new@b.com"); dto.setFirstName("Ali"); dto.setLastName("Test"); dto.setAppliedPosition("Dev");
        var result = service.createCandidate(dto);
        assertThat(result.getStatus()).isEqualTo(CandidateStatus.NEW);
    }
}
```

- [ ] **Step 7 : Lancer les tests**

```
mvn test -Dtest=CandidateServiceTest -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8 : Commit**

```
git add src/main/java/com/daf360/rh/dto/
git add src/main/java/com/daf360/rh/service/CandidateService.java
git add src/main/java/com/daf360/rh/service/InterviewService.java
git add src/main/java/com/daf360/rh/service/OfferService.java
git add src/main/java/com/daf360/rh/service/HrNotificationService.java
git add src/main/java/com/daf360/rh/controller/RecruitmentController.java
git add src/test/java/com/daf360/rh/service/CandidateServiceTest.java
git commit -m "feat(recruitment): add services, DTOs and RecruitmentController"
```

---

## JOUR 5-6 (30-31 mai) — Task 3 : Frontend Recrutement

**Files:**
- Create: `src/app/core/rh/rh.model.ts`
- Create: `src/app/core/rh/rh.service.ts`
- Create: `src/app/pages/rh/recruitment/candidate-list/` (3 fichiers)
- Create: `src/app/pages/rh/recruitment/candidate-form/` (3 fichiers)
- Create: `src/app/pages/rh/recruitment/candidate-detail/` (3 fichiers)
- Modify: `src/app/app.routes.ts`

- [ ] **Step 1 : Créer rh.model.ts**

```typescript
// src/app/core/rh/rh.model.ts
export type CandidateStatus = 'NEW' | 'IN_PROGRESS' | 'OFFERED' | 'HIRED' | 'REJECTED';
export type InterviewType = 'RH' | 'TECHNICAL' | 'MANAGER' | 'FINAL';
export type InterviewOutcome = 'PENDING' | 'PASSED' | 'FAILED';
export type OfferStatus = 'SENT' | 'ACCEPTED' | 'REJECTED';
export type LifecycleStage = 'TRIAL_PERIOD' | 'PERMANENT' | 'RESIGNED';
export type RequestType = 'DOCUMENT' | 'CERTIFICATE' | 'ATTESTATION' | 'OTHER';
export type RequestStatus = 'PENDING' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED';

export interface InterviewResponse {
  id: number;
  interviewType: InterviewType;
  scheduledAt: string;
  interviewerId: number | null;
  round: number;
  outcome: InterviewOutcome;
  feedback: string | null;
}

export interface OfferResponse {
  id: number;
  expectedHireDate: string;
  proposedSalary: number | null;
  sentAt: string;
  status: OfferStatus;
  rejectionReason: string | null;
}

export interface CandidateResponse {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string | null;
  appliedPosition: string;
  cvUrl: string | null;
  notes: string | null;
  status: CandidateStatus;
  createdAt: string;
  interviews: InterviewResponse[];
  offer: OfferResponse | null;
}

export interface CandidateRequest {
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  appliedPosition: string;
  cvUrl?: string;
  notes?: string;
}

export interface InterviewCreateDto {
  candidateId: number;
  interviewType: InterviewType;
  scheduledAt: string;
  interviewerId?: number;
}

export interface OfferCreateDto {
  candidateId: number;
  expectedHireDate: string;
  proposedSalary?: number;
}

export interface EmployeeRequestCreate {
  requestType: RequestType;
  description: string;
}

export interface EmployeeRequestResponse {
  id: number;
  employeeId: number;
  requestType: RequestType;
  description: string;
  status: RequestStatus;
  hrNotes: string | null;
  createdAt: string;
  processedAt: string | null;
}
```

- [ ] **Step 2 : Créer rh.service.ts**

```typescript
// src/app/core/rh/rh.service.ts
import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import {
  CandidateResponse, CandidateRequest, InterviewCreateDto,
  InterviewResponse, OfferCreateDto, OfferResponse,
  EmployeeRequestCreate, EmployeeRequestResponse
} from './rh.model';

@Injectable({ providedIn: 'root' })
export class RhService {
  private http = inject(HttpClient);
  private base = `${environment.apiUrl}/api/hr`;

  // Candidates
  getCandidates() {
    return this.http.get<CandidateResponse[]>(`${this.base}/recruitment/candidates`, { withCredentials: true });
  }
  getCandidate(id: number) {
    return this.http.get<CandidateResponse>(`${this.base}/recruitment/candidates/${id}`, { withCredentials: true });
  }
  createCandidate(dto: CandidateRequest) {
    return this.http.post<CandidateResponse>(`${this.base}/recruitment/candidates`, dto, { withCredentials: true });
  }

  // Interviews
  scheduleInterview(dto: InterviewCreateDto) {
    return this.http.post<InterviewResponse>(`${this.base}/recruitment/interviews`, dto, { withCredentials: true });
  }
  recordOutcome(id: number, outcome: string, feedback: string) {
    return this.http.put<InterviewResponse>(`${this.base}/recruitment/interviews/${id}/outcome`, { outcome, feedback }, { withCredentials: true });
  }

  // Offers
  sendOffer(dto: OfferCreateDto) {
    return this.http.post<OfferResponse>(`${this.base}/recruitment/offers`, dto, { withCredentials: true });
  }
  acceptOffer(id: number) {
    return this.http.put<OfferResponse>(`${this.base}/recruitment/offers/${id}/accept`, {}, { withCredentials: true });
  }
  rejectOffer(id: number, reason: string) {
    return this.http.put<OfferResponse>(`${this.base}/recruitment/offers/${id}/reject`, { reason }, { withCredentials: true });
  }

  // Lifecycle
  promoteToTitulaire(employeeId: number) {
    return this.http.put(`${this.base}/employes/${employeeId}/lifecycle/promote`, {}, { withCredentials: true });
  }
  resign(employeeId: number) {
    return this.http.put(`${this.base}/employes/${employeeId}/lifecycle/resign`, {}, { withCredentials: true });
  }

  // Requests
  getMyRequests() {
    return this.http.get<EmployeeRequestResponse[]>(`${this.base}/requests/my`, { withCredentials: true });
  }
  getAllRequests() {
    return this.http.get<EmployeeRequestResponse[]>(`${this.base}/requests`, { withCredentials: true });
  }
  createRequest(dto: EmployeeRequestCreate) {
    return this.http.post<EmployeeRequestResponse>(`${this.base}/requests`, dto, { withCredentials: true });
  }
  processRequest(id: number, status: string, hrNotes: string) {
    return this.http.put<EmployeeRequestResponse>(`${this.base}/requests/${id}/process`, { status, hrNotes }, { withCredentials: true });
  }
}
```

- [ ] **Step 3 : Créer candidate-list.ts**

```typescript
// src/app/pages/rh/recruitment/candidate-list/candidate-list.ts
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { RhService } from '../../../../core/rh/rh.service';
import { CandidateResponse, CandidateStatus } from '../../../../core/rh/rh.model';

@Component({
  selector: 'app-candidate-list',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './candidate-list.html',
  styleUrl: './candidate-list.css',
})
export class CandidateListComponent implements OnInit {
  private rh = inject(RhService);
  candidates = signal<CandidateResponse[]>([]);
  loading = signal(false);

  readonly statusLabels: Record<CandidateStatus, string> = {
    NEW: 'Nouveau',
    IN_PROGRESS: 'En cours',
    OFFERED: 'Offre envoyée',
    HIRED: 'Embauché',
    REJECTED: 'Refusé',
  };

  readonly statusColors: Record<CandidateStatus, string> = {
    NEW: '#64748b',
    IN_PROGRESS: '#f39c12',
    OFFERED: '#1a6b7c',
    HIRED: '#27ae60',
    REJECTED: '#e74c3c',
  };

  ngOnInit() {
    this.loading.set(true);
    this.rh.getCandidates().subscribe({
      next: list => { this.candidates.set(list); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
```

- [ ] **Step 4 : Créer candidate-list.html**

```html
<!-- src/app/pages/rh/recruitment/candidate-list/candidate-list.html -->
<div class="page-header">
  <h1 class="page-title">Candidats</h1>
  <a routerLink="/rh/recruitment/nouveau" class="btn-primary">+ Nouveau candidat</a>
</div>

@if (loading()) {
  <p class="loading-text">Chargement…</p>
} @else {
  <div class="candidates-table">
    <div class="table-header">
      <span>Nom</span><span>Poste</span><span>Email</span><span>Statut</span><span>Actions</span>
    </div>
    @for (c of candidates(); track c.id) {
      <div class="table-row">
        <span class="candidate-name">{{ c.firstName }} {{ c.lastName }}</span>
        <span>{{ c.appliedPosition }}</span>
        <span class="candidate-email">{{ c.email }}</span>
        <span class="status-badge" [style.background]="statusColors[c.status] + '22'"
              [style.color]="statusColors[c.status]">
          {{ statusLabels[c.status] }}
        </span>
        <a [routerLink]="['/rh/recruitment', c.id]" class="btn-link">Voir</a>
      </div>
    }
    @if (candidates().length === 0) {
      <p class="empty-state">Aucun candidat enregistré.</p>
    }
  </div>
}
```

- [ ] **Step 5 : Créer candidate-list.css**

```css
/* src/app/pages/rh/recruitment/candidate-list/candidate-list.css */
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-title { font-family: 'Manrope', sans-serif; font-size: 22px; font-weight: 700; color: var(--color-text); margin: 0; }
.btn-primary { background: var(--color-primary); color: #fff; padding: 9px 18px; border-radius: 8px; font-family: 'Manrope', sans-serif; font-size: 13px; font-weight: 700; text-decoration: none; }
.candidates-table { background: #fff; border: 1px solid var(--color-border); border-radius: 12px; overflow: hidden; }
.table-header { display: grid; grid-template-columns: 2fr 2fr 2fr 1.2fr 0.8fr; gap: 12px; padding: 12px 20px; background: var(--color-bg); font-family: 'Manrope', sans-serif; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; color: var(--color-text-muted); }
.table-row { display: grid; grid-template-columns: 2fr 2fr 2fr 1.2fr 0.8fr; gap: 12px; padding: 14px 20px; align-items: center; border-top: 1px solid var(--color-border); font-family: 'Manrope', sans-serif; font-size: 13px; color: var(--color-text); }
.candidate-name { font-weight: 700; }
.candidate-email { color: var(--color-text-muted); }
.status-badge { display: inline-block; padding: 4px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; }
.btn-link { color: var(--color-primary); font-weight: 700; text-decoration: none; font-size: 13px; }
.empty-state { padding: 32px; text-align: center; color: var(--color-text-muted); font-family: 'Manrope', sans-serif; }
.loading-text { color: var(--color-text-muted); font-family: 'Manrope', sans-serif; }
```

- [ ] **Step 6 : Ajouter les routes dans app.routes.ts**

```typescript
// Dans src/app/app.routes.ts, ajouter dans le tableau Routes :
{
  path: 'rh',
  canActivate: [authGuard],
  children: [
    { path: 'recruitment', component: CandidateListComponent },
    { path: 'recruitment/nouveau', component: CandidateFormComponent },
    { path: 'recruitment/:id', component: CandidateDetailComponent },
    { path: 'requests', component: RequestListComponent },
    { path: 'requests/nouveau', component: RequestFormComponent },
    { path: '', redirectTo: 'recruitment', pathMatch: 'full' },
  ]
}
```

- [ ] **Step 7 : Commit**

```
git -C c:\Users\ITEC2\Documents\daf360-shell add src/app/core/rh/ src/app/pages/rh/recruitment/candidate-list/ src/app/app.routes.ts
git -C c:\Users\ITEC2\Documents\daf360-shell commit -m "feat(rh-frontend): add candidate list, RhService and rh.model"
```

---

## JOUR 7 (2 juin) — Task 4 : Formulaires Frontend Recrutement

**Files:**
- Create: `src/app/pages/rh/recruitment/candidate-form/` (3 fichiers)
- Create: `src/app/pages/rh/recruitment/candidate-detail/` (3 fichiers)

- [ ] **Step 1 : Créer candidate-form.ts**

```typescript
// src/app/pages/rh/recruitment/candidate-form/candidate-form.ts
import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { RhService } from '../../../../core/rh/rh.service';

@Component({
  selector: 'app-candidate-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './candidate-form.html',
  styleUrl: './candidate-form.css',
})
export class CandidateFormComponent {
  private rh = inject(RhService);
  private router = inject(Router);

  firstName = signal(''); lastName = signal('');
  email = signal(''); phone = signal('');
  appliedPosition = signal(''); cvUrl = signal('');
  notes = signal(''); saving = signal(false);
  error = signal('');

  save() {
    if (!this.firstName() || !this.lastName() || !this.email() || !this.appliedPosition()) {
      this.error.set('Veuillez remplir tous les champs obligatoires.');
      return;
    }
    this.saving.set(true);
    this.rh.createCandidate({
      firstName: this.firstName(), lastName: this.lastName(),
      email: this.email(), phone: this.phone(),
      appliedPosition: this.appliedPosition(), cvUrl: this.cvUrl(),
      notes: this.notes(),
    }).subscribe({
      next: c => this.router.navigate(['/rh/recruitment', c.id]),
      error: () => { this.error.set('Erreur lors de la création.'); this.saving.set(false); },
    });
  }
}
```

- [ ] **Step 2 : Créer candidate-form.html**

```html
<!-- src/app/pages/rh/recruitment/candidate-form/candidate-form.html -->
<div class="form-page">
  <h1 class="page-title">Nouveau candidat</h1>
  @if (error()) { <p class="error-msg">{{ error() }}</p> }
  <div class="form-card">
    <div class="form-row">
      <div class="field"><label>Prénom *</label><input type="text" [value]="firstName()" (input)="firstName.set($any($event.target).value)" /></div>
      <div class="field"><label>Nom *</label><input type="text" [value]="lastName()" (input)="lastName.set($any($event.target).value)" /></div>
    </div>
    <div class="form-row">
      <div class="field"><label>Email *</label><input type="email" [value]="email()" (input)="email.set($any($event.target).value)" /></div>
      <div class="field"><label>Téléphone</label><input type="tel" [value]="phone()" (input)="phone.set($any($event.target).value)" /></div>
    </div>
    <div class="field"><label>Poste visé *</label><input type="text" [value]="appliedPosition()" (input)="appliedPosition.set($any($event.target).value)" /></div>
    <div class="field"><label>URL du CV</label><input type="url" [value]="cvUrl()" (input)="cvUrl.set($any($event.target).value)" /></div>
    <div class="field"><label>Notes</label><textarea rows="4" [value]="notes()" (input)="notes.set($any($event.target).value)"></textarea></div>
    <div class="form-actions">
      <button class="btn-primary" (click)="save()" [disabled]="saving()">
        {{ saving() ? 'Enregistrement…' : 'Créer le candidat' }}
      </button>
    </div>
  </div>
</div>
```

- [ ] **Step 3 : Créer candidate-detail.ts**

```typescript
// src/app/pages/rh/recruitment/candidate-detail/candidate-detail.ts
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { RhService } from '../../../../core/rh/rh.service';
import { CandidateResponse, InterviewType } from '../../../../core/rh/rh.model';

@Component({
  selector: 'app-candidate-detail',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './candidate-detail.html',
  styleUrl: './candidate-detail.css',
})
export class CandidateDetailComponent implements OnInit {
  private rh = inject(RhService);
  private route = inject(ActivatedRoute);

  candidate = signal<CandidateResponse | null>(null);
  loading = signal(false);

  showInterviewForm = signal(false);
  interviewType = signal<InterviewType>('RH');
  scheduledAt = signal('');
  interviewTypes: InterviewType[] = ['RH', 'TECHNICAL', 'MANAGER', 'FINAL'];

  showOfferForm = signal(false);
  expectedHireDate = signal('');
  proposedSalary = signal('');

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.load(id);
  }

  load(id: number) {
    this.loading.set(true);
    this.rh.getCandidate(id).subscribe({
      next: c => { this.candidate.set(c); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  scheduleInterview() {
    const c = this.candidate();
    if (!c) return;
    this.rh.scheduleInterview({
      candidateId: c.id,
      interviewType: this.interviewType(),
      scheduledAt: this.scheduledAt(),
    }).subscribe({ next: () => { this.showInterviewForm.set(false); this.load(c.id); } });
  }

  sendOffer() {
    const c = this.candidate();
    if (!c) return;
    this.rh.sendOffer({
      candidateId: c.id,
      expectedHireDate: this.expectedHireDate(),
      proposedSalary: this.proposedSalary() ? Number(this.proposedSalary()) : undefined,
    }).subscribe({ next: () => { this.showOfferForm.set(false); this.load(c.id); } });
  }

  acceptOffer() {
    const offer = this.candidate()?.offer;
    if (!offer) return;
    this.rh.acceptOffer(offer.id).subscribe({ next: () => this.load(this.candidate()!.id) });
  }
}
```

- [ ] **Step 4 : Créer candidate-detail.html**

```html
<!-- src/app/pages/rh/recruitment/candidate-detail/candidate-detail.html -->
@if (loading()) { <p class="loading-text">Chargement…</p> }
@if (candidate(); as c) {
  <div class="detail-page">
    <div class="detail-header">
      <div>
        <h1 class="page-title">{{ c.firstName }} {{ c.lastName }}</h1>
        <p class="candidate-position">{{ c.appliedPosition }}</p>
      </div>
      <div class="header-actions">
        <button class="btn-secondary" (click)="showInterviewForm.set(true)">+ Entretien</button>
        @if (!c.offer) { <button class="btn-primary" (click)="showOfferForm.set(true)">Envoyer une offre</button> }
      </div>
    </div>

    <!-- Interview Timeline -->
    <section class="section">
      <h2 class="section-title">Entretiens</h2>
      @if (c.interviews.length === 0) { <p class="empty-state">Aucun entretien planifié.</p> }
      @for (i of c.interviews; track i.id) {
        <div class="interview-card" [class.passed]="i.outcome === 'PASSED'" [class.failed]="i.outcome === 'FAILED'">
          <div class="interview-round">Round {{ i.round }}</div>
          <div class="interview-info">
            <span class="interview-type">{{ i.interviewType }}</span>
            <span class="interview-date">{{ i.scheduledAt | date:'dd/MM/yyyy HH:mm' }}</span>
          </div>
          <span class="outcome-badge outcome-{{ i.outcome.toLowerCase() }}">{{ i.outcome }}</span>
        </div>
      }
    </section>

    <!-- Offer Section -->
    @if (c.offer) {
      <section class="section">
        <h2 class="section-title">Offre</h2>
        <div class="offer-card">
          <p>Statut : <strong>{{ c.offer.status }}</strong></p>
          <p>Date d'embauche prévue : {{ c.offer.expectedHireDate }}</p>
          @if (c.offer.status === 'SENT') {
            <button class="btn-primary" (click)="acceptOffer()">Confirmer l'acceptation</button>
          }
        </div>
      </section>
    }

    <!-- Interview Form -->
    @if (showInterviewForm()) {
      <div class="modal-overlay" (click)="showInterviewForm.set(false)">
        <div class="modal-card" (click)="$event.stopPropagation()">
          <h3>Planifier un entretien</h3>
          <label>Type
            <select [value]="interviewType()" (change)="interviewType.set($any($event.target).value)">
              @for (t of interviewTypes; track t) { <option [value]="t">{{ t }}</option> }
            </select>
          </label>
          <label>Date et heure <input type="datetime-local" [value]="scheduledAt()" (input)="scheduledAt.set($any($event.target).value)" /></label>
          <div class="modal-actions">
            <button class="btn-primary" (click)="scheduleInterview()">Planifier</button>
            <button class="btn-link" (click)="showInterviewForm.set(false)">Annuler</button>
          </div>
        </div>
      </div>
    }

    <!-- Offer Form -->
    @if (showOfferForm()) {
      <div class="modal-overlay" (click)="showOfferForm.set(false)">
        <div class="modal-card" (click)="$event.stopPropagation()">
          <h3>Envoyer une offre</h3>
          <label>Date d'embauche prévue <input type="date" [value]="expectedHireDate()" (input)="expectedHireDate.set($any($event.target).value)" /></label>
          <label>Salaire proposé <input type="number" [value]="proposedSalary()" (input)="proposedSalary.set($any($event.target).value)" /></label>
          <div class="modal-actions">
            <button class="btn-primary" (click)="sendOffer()">Envoyer l'offre</button>
            <button class="btn-link" (click)="showOfferForm.set(false)">Annuler</button>
          </div>
        </div>
      </div>
    }
  </div>
}
```

- [ ] **Step 5 : Commit**

```
git -C c:\Users\ITEC2\Documents\daf360-shell add src/app/pages/rh/
git -C c:\Users\ITEC2\Documents\daf360-shell commit -m "feat(rh-frontend): add candidate form and detail with interview/offer UI"
```

---

## JOUR 8-9 (3-4 juin) — Task 5 : Cycle de vie employé (Backend)

**Files:**
- Modify: `src/main/java/com/daf360/rh/domain/Employee.java`
- Create: `src/main/java/com/daf360/rh/domain/enums/EmploymentLifecycleStage.java`
- Create: `src/main/java/com/daf360/rh/dto/LifecycleTransitionDto.java`
- Create: `src/main/java/com/daf360/rh/service/EmployeeLifecycleService.java`
- Create: `src/main/java/com/daf360/rh/controller/EmployeeLifecycleController.java`
- Modify: `src/main/java/com/daf360/rh/scheduler/HrScheduler.java`
- Test: `src/test/java/com/daf360/rh/service/EmployeeLifecycleServiceTest.java`

- [ ] **Step 1 : Créer l'enum EmploymentLifecycleStage**

```java
// EmploymentLifecycleStage.java
package com.daf360.rh.domain.enums;
public enum EmploymentLifecycleStage {
    TRIAL_PERIOD, PERMANENT, RESIGNED
}
```

- [ ] **Step 2 : Modifier Employee.java — ajouter les champs lifecycle**

Dans `Employee.java`, ajouter après les champs existants :

```java
@Enumerated(EnumType.STRING)
@Column(name = "lifecycle_stage", length = 20)
private EmploymentLifecycleStage lifecycleStage = EmploymentLifecycleStage.TRIAL_PERIOD;

@Column(name = "trial_end_date")
private LocalDate trialEndDate;

@Column(name = "resigned_at")
private LocalDate resignedAt;
```

- [ ] **Step 3 : Créer EmployeeLifecycleService**

```java
// EmployeeLifecycleService.java
package com.daf360.rh.service;

import com.daf360.rh.domain.Employee;
import com.daf360.rh.domain.enums.EmployeeStatus;
import com.daf360.rh.domain.enums.EmploymentLifecycleStage;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class EmployeeLifecycleService {

    private final EmployeeRepository employeeRepository;

    @Transactional
    public void promoteToTitulaire(Long employeeId) {
        Employee emp = findOrThrow(employeeId);
        if (emp.getLifecycleStage() != EmploymentLifecycleStage.TRIAL_PERIOD) {
            throw new BusinessRuleException("L'employé n'est pas en période d'essai.");
        }
        emp.setLifecycleStage(EmploymentLifecycleStage.PERMANENT);
        employeeRepository.save(emp);
    }

    @Transactional
    public void resign(Long employeeId) {
        Employee emp = findOrThrow(employeeId);
        if (emp.getLifecycleStage() == EmploymentLifecycleStage.RESIGNED) {
            throw new BusinessRuleException("L'employé a déjà démissionné.");
        }
        emp.setLifecycleStage(EmploymentLifecycleStage.RESIGNED);
        emp.setResignedAt(LocalDate.now());
        emp.setStatus(EmployeeStatus.ARCHIVED);
        employeeRepository.save(emp);
    }

    @Transactional(readOnly = true)
    public Employee findOrThrow(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employé introuvable: " + id));
    }
}
```

- [ ] **Step 4 : Créer EmployeeLifecycleController**

```java
// EmployeeLifecycleController.java
package com.daf360.rh.controller;

import com.daf360.rh.service.EmployeeLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hr/employes")
@RequiredArgsConstructor
public class EmployeeLifecycleController {

    private final EmployeeLifecycleService lifecycleService;

    @PutMapping("/{id}/lifecycle/promote")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<Void> promote(@PathVariable Long id) {
        lifecycleService.promoteToTitulaire(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/lifecycle/resign")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<Void> resign(@PathVariable Long id) {
        lifecycleService.resign(id);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 5 : Modifier HrScheduler — ajouter détection fin période d'essai**

Dans `HrScheduler.java`, ajouter la méthode :

```java
@Autowired private EmployeeRepository employeeRepository;
@Autowired private HrNotificationService notificationService;

@Scheduled(cron = "0 0 8 * * MON-FRI")
public void checkTrialPeriodExpiry() {
    LocalDate today = LocalDate.now();
    employeeRepository.findAll().stream()
        .filter(e -> e.getLifecycleStage() == EmploymentLifecycleStage.TRIAL_PERIOD
                  && e.getTrialEndDate() != null
                  && !e.getTrialEndDate().isAfter(today))
        .forEach(e -> {
            log.info("Trial period ended for employee {}", e.getId());
            notificationService.notifyTrialPeriodEnded(e);
        });
}
```

- [ ] **Step 6 : Écrire le test**

```java
// EmployeeLifecycleServiceTest.java
package com.daf360.rh.service;

import com.daf360.rh.domain.Employee;
import com.daf360.rh.domain.enums.EmploymentLifecycleStage;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeLifecycleServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @InjectMocks EmployeeLifecycleService service;

    @Test
    void promote_whenTrial_setsPermament() {
        Employee emp = new Employee();
        emp.setId(1L); emp.setLifecycleStage(EmploymentLifecycleStage.TRIAL_PERIOD);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(emp));
        when(employeeRepository.save(any())).thenReturn(emp);
        service.promoteToTitulaire(1L);
        assertThat(emp.getLifecycleStage()).isEqualTo(EmploymentLifecycleStage.PERMANENT);
    }

    @Test
    void promote_whenNotTrial_throwsBusinessRule() {
        Employee emp = new Employee();
        emp.setId(2L); emp.setLifecycleStage(EmploymentLifecycleStage.PERMANENT);
        when(employeeRepository.findById(2L)).thenReturn(Optional.of(emp));
        assertThatThrownBy(() -> service.promoteToTitulaire(2L))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resign_archivesEmployee() {
        Employee emp = new Employee();
        emp.setId(3L); emp.setLifecycleStage(EmploymentLifecycleStage.PERMANENT);
        when(employeeRepository.findById(3L)).thenReturn(Optional.of(emp));
        when(employeeRepository.save(any())).thenReturn(emp);
        service.resign(3L);
        assertThat(emp.getLifecycleStage()).isEqualTo(EmploymentLifecycleStage.RESIGNED);
    }
}
```

- [ ] **Step 7 : Lancer les tests**

```
mvn test -Dtest=EmployeeLifecycleServiceTest -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8 : Commit**

```
git add src/main/java/com/daf360/rh/domain/enums/EmploymentLifecycleStage.java
git add src/main/java/com/daf360/rh/domain/Employee.java
git add src/main/java/com/daf360/rh/service/EmployeeLifecycleService.java
git add src/main/java/com/daf360/rh/controller/EmployeeLifecycleController.java
git add src/main/java/com/daf360/rh/scheduler/HrScheduler.java
git add src/test/java/com/daf360/rh/service/EmployeeLifecycleServiceTest.java
git commit -m "feat(lifecycle): add trial/permanent/resigned lifecycle with scheduler detection"
```

---

## JOUR 10-11 (5-6 juin) — Task 6 : Gestion des demandes (Backend)

**Files:**
- Create: `src/main/java/com/daf360/rh/domain/enums/RequestType.java`
- Create: `src/main/java/com/daf360/rh/domain/enums/RequestStatus.java`
- Create: `src/main/java/com/daf360/rh/domain/EmployeeRequest.java`
- Create: `src/main/java/com/daf360/rh/repository/EmployeeRequestRepository.java`
- Create: `src/main/java/com/daf360/rh/dto/EmployeeRequestCreateDto.java`
- Create: `src/main/java/com/daf360/rh/dto/EmployeeRequestResponseDto.java`
- Create: `src/main/java/com/daf360/rh/service/EmployeeRequestService.java`
- Create: `src/main/java/com/daf360/rh/controller/EmployeeRequestController.java`
- Test: `src/test/java/com/daf360/rh/service/EmployeeRequestServiceTest.java`

- [ ] **Step 1 : Créer les enums**

```java
// RequestType.java
package com.daf360.rh.domain.enums;
public enum RequestType {
    DOCUMENT, CERTIFICATE, ATTESTATION, OTHER
}

// RequestStatus.java
package com.daf360.rh.domain.enums;
public enum RequestStatus {
    PENDING, IN_REVIEW, APPROVED, REJECTED
}
```

- [ ] **Step 2 : Créer l'entité EmployeeRequest**

```java
// EmployeeRequest.java
package com.daf360.rh.domain;

import com.daf360.rh.audit.AuditableEntity;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.domain.enums.RequestType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@Entity
@Table(name = "employee_requests")
public class EmployeeRequest extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private RequestType requestType;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "hr_notes", length = 1000)
    private String hrNotes;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processed_by")
    private Long processedBy;
}
```

- [ ] **Step 3 : Créer le repository**

```java
// EmployeeRequestRepository.java
package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EmployeeRequestRepository extends JpaRepository<EmployeeRequest, Long> {
    List<EmployeeRequest> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);
    List<EmployeeRequest> findByStatusOrderByCreatedAtDesc(RequestStatus status);
    List<EmployeeRequest> findAllByOrderByCreatedAtDesc();
}
```

- [ ] **Step 4 : Créer les DTOs**

```java
// EmployeeRequestCreateDto.java
package com.daf360.rh.dto;
import com.daf360.rh.domain.enums.RequestType;
import lombok.Data;

@Data
public class EmployeeRequestCreateDto {
    private RequestType requestType;
    private String description;
}

// EmployeeRequestResponseDto.java
package com.daf360.rh.dto;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.domain.enums.RequestType;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
public class EmployeeRequestResponseDto {
    private Long id;
    private Long employeeId;
    private RequestType requestType;
    private String description;
    private RequestStatus status;
    private String hrNotes;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}
```

- [ ] **Step 5 : Créer EmployeeRequestService**

```java
// EmployeeRequestService.java
package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.dto.EmployeeRequestCreateDto;
import com.daf360.rh.dto.EmployeeRequestResponseDto;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.EmployeeRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeRequestService {

    private final EmployeeRequestRepository requestRepository;

    @Transactional
    public EmployeeRequestResponseDto createRequest(Long employeeId, EmployeeRequestCreateDto dto) {
        EmployeeRequest req = new EmployeeRequest();
        req.setEmployeeId(employeeId);
        req.setRequestType(dto.getRequestType());
        req.setDescription(dto.getDescription());
        req.setStatus(RequestStatus.PENDING);
        return toDto(requestRepository.save(req));
    }

    @Transactional(readOnly = true)
    public List<EmployeeRequestResponseDto> getByEmployee(Long employeeId) {
        return requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeRequestResponseDto> getAll() {
        return requestRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public EmployeeRequestResponseDto process(Long requestId, RequestStatus status, String hrNotes, Long processedBy) {
        EmployeeRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Demande introuvable: " + requestId));
        req.setStatus(status);
        req.setHrNotes(hrNotes);
        req.setProcessedAt(LocalDateTime.now());
        req.setProcessedBy(processedBy);
        return toDto(requestRepository.save(req));
    }

    private EmployeeRequestResponseDto toDto(EmployeeRequest r) {
        return EmployeeRequestResponseDto.builder()
                .id(r.getId()).employeeId(r.getEmployeeId())
                .requestType(r.getRequestType()).description(r.getDescription())
                .status(r.getStatus()).hrNotes(r.getHrNotes())
                .createdAt(r.getCreatedAt()).processedAt(r.getProcessedAt())
                .build();
    }
}
```

- [ ] **Step 6 : Créer EmployeeRequestController**

```java
// EmployeeRequestController.java
package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.dto.EmployeeRequestCreateDto;
import com.daf360.rh.dto.EmployeeRequestResponseDto;
import com.daf360.rh.service.EmployeeRequestService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/hr/requests")
@RequiredArgsConstructor
public class EmployeeRequestController {

    private final EmployeeRequestService requestService;

    @GetMapping
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public List<EmployeeRequestResponseDto> getAll() {
        return requestService.getAll();
    }

    @GetMapping("/my")
    //@PreAuthorize("isAuthenticated()")
    public List<EmployeeRequestResponseDto> getMine(@RequestParam Long employeeId) {
        return requestService.getByEmployee(employeeId);
    }

    @PostMapping
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmployeeRequestResponseDto> create(
            @RequestParam Long employeeId,
            @RequestBody EmployeeRequestCreateDto dto) {
        return ResponseEntity.ok(requestService.createRequest(employeeId, dto));
    }

    @PutMapping("/{id}/process")
    //@PreAuthorize("hasAnyRole('HR','ADMIN')")
    public ResponseEntity<EmployeeRequestResponseDto> process(
            @PathVariable Long id,
            @RequestBody ProcessRequest req) {
        return ResponseEntity.ok(requestService.process(id, req.getStatus(), req.getHrNotes(), 0L));
    }

    @Data static class ProcessRequest { private RequestStatus status; private String hrNotes; }
}
```

- [ ] **Step 7 : Écrire le test**

```java
// EmployeeRequestServiceTest.java
package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.domain.enums.RequestType;
import com.daf360.rh.dto.EmployeeRequestCreateDto;
import com.daf360.rh.repository.EmployeeRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeRequestServiceTest {

    @Mock EmployeeRequestRepository requestRepository;
    @InjectMocks EmployeeRequestService service;

    @Test
    void createRequest_setsPendingStatus() {
        EmployeeRequest saved = new EmployeeRequest();
        saved.setId(1L); saved.setEmployeeId(10L);
        saved.setRequestType(RequestType.DOCUMENT); saved.setDescription("Attestation de travail");
        saved.setStatus(RequestStatus.PENDING);
        when(requestRepository.save(any())).thenReturn(saved);

        var dto = new EmployeeRequestCreateDto();
        dto.setRequestType(RequestType.DOCUMENT); dto.setDescription("Attestation de travail");
        var result = service.createRequest(10L, dto);
        assertThat(result.getStatus()).isEqualTo(RequestStatus.PENDING);
    }
}
```

- [ ] **Step 8 : Lancer les tests**

```
mvn test -Dtest=EmployeeRequestServiceTest -q
```
Expected: `BUILD SUCCESS`

- [ ] **Step 9 : Commit**

```
git add src/main/java/com/daf360/rh/domain/enums/RequestType.java
git add src/main/java/com/daf360/rh/domain/enums/RequestStatus.java
git add src/main/java/com/daf360/rh/domain/EmployeeRequest.java
git add src/main/java/com/daf360/rh/repository/EmployeeRequestRepository.java
git add src/main/java/com/daf360/rh/dto/EmployeeRequestCreateDto.java
git add src/main/java/com/daf360/rh/dto/EmployeeRequestResponseDto.java
git add src/main/java/com/daf360/rh/service/EmployeeRequestService.java
git add src/main/java/com/daf360/rh/controller/EmployeeRequestController.java
git add src/test/java/com/daf360/rh/service/EmployeeRequestServiceTest.java
git commit -m "feat(requests): add EmployeeRequest entity, service and controller"
```

---

## JOUR 12-13 (9-10 juin) — Task 7 : Frontend Demandes & Cycle de vie

**Files:**
- Create: `src/app/pages/rh/requests/request-list/` (3 fichiers)
- Create: `src/app/pages/rh/requests/request-form/` (3 fichiers)
- Create: `src/app/pages/rh/lifecycle/employee-lifecycle/` (3 fichiers)

- [ ] **Step 1 : Créer request-list.ts**

```typescript
// src/app/pages/rh/requests/request-list/request-list.ts
import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { RhService } from '../../../../core/rh/rh.service';
import { AuthService } from '../../../../core/services/auth';
import { EmployeeRequestResponse, RequestStatus } from '../../../../core/rh/rh.model';

@Component({
  selector: 'app-request-list',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './request-list.html',
  styleUrl: './request-list.css',
})
export class RequestListComponent implements OnInit {
  private rh = inject(RhService);
  private auth = inject(AuthService);

  readonly isHr = computed(() => {
    const perms = this.auth.user()?.permissions ?? [];
    return perms.includes('MANAGE_REQUESTS') || perms.includes('HR');
  });

  requests = signal<EmployeeRequestResponse[]>([]);
  loading = signal(false);
  processingId = signal<number | null>(null);
  hrNotes = signal('');

  readonly statusLabels: Record<RequestStatus, string> = {
    PENDING: 'En attente', IN_REVIEW: 'En cours', APPROVED: 'Approuvée', REJECTED: 'Refusée',
  };

  readonly statusColors: Record<RequestStatus, string> = {
    PENDING: '#f39c12', IN_REVIEW: '#1a6b7c', APPROVED: '#27ae60', REJECTED: '#e74c3c',
  };

  ngOnInit() {
    this.load();
  }

  load() {
    this.loading.set(true);
    const obs = this.isHr() ? this.rh.getAllRequests() : this.rh.getMyRequests();
    obs.subscribe({
      next: list => { this.requests.set(list); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  approve(id: number) {
    this.rh.processRequest(id, 'APPROVED', this.hrNotes()).subscribe({ next: () => this.load() });
  }

  reject(id: number) {
    this.rh.processRequest(id, 'REJECTED', this.hrNotes()).subscribe({ next: () => this.load() });
  }
}
```

- [ ] **Step 2 : Créer request-list.html**

```html
<!-- src/app/pages/rh/requests/request-list/request-list.html -->
<div class="page-header">
  <h1 class="page-title">{{ isHr() ? 'Toutes les demandes' : 'Mes demandes' }}</h1>
  @if (!isHr()) { <a routerLink="/rh/requests/nouveau" class="btn-primary">+ Nouvelle demande</a> }
</div>

@if (loading()) { <p class="loading-text">Chargement…</p> }
@for (r of requests(); track r.id) {
  <div class="request-card">
    <div class="request-header">
      <span class="request-type">{{ r.requestType }}</span>
      <span class="status-badge" [style.background]="statusColors[r.status] + '22'" [style.color]="statusColors[r.status]">
        {{ statusLabels[r.status] }}
      </span>
    </div>
    <p class="request-desc">{{ r.description }}</p>
    <p class="request-date">Créée le {{ r.createdAt | date:'dd/MM/yyyy' }}</p>
    @if (r.hrNotes) { <p class="hr-notes">Réponse RH : {{ r.hrNotes }}</p> }
    @if (isHr() && r.status === 'PENDING') {
      <div class="process-actions">
        <input type="text" [(ngModel)]="hrNotes" placeholder="Notes RH (optionnel)" class="hr-notes-input" />
        <button class="btn-approve" (click)="approve(r.id)">Approuver</button>
        <button class="btn-reject" (click)="reject(r.id)">Refuser</button>
      </div>
    }
  </div>
}
@if (requests().length === 0 && !loading()) {
  <p class="empty-state">Aucune demande.</p>
}
```

- [ ] **Step 3 : Créer request-form.ts**

```typescript
// src/app/pages/rh/requests/request-form/request-form.ts
import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { RhService } from '../../../../core/rh/rh.service';
import { RequestType } from '../../../../core/rh/rh.model';

@Component({
  selector: 'app-request-form',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './request-form.html',
  styleUrl: './request-form.css',
})
export class RequestFormComponent {
  private rh = inject(RhService);
  private router = inject(Router);

  requestType = signal<RequestType>('DOCUMENT');
  description = signal('');
  saving = signal(false);
  error = signal('');
  types: RequestType[] = ['DOCUMENT', 'CERTIFICATE', 'ATTESTATION', 'OTHER'];

  save() {
    if (!this.description()) { this.error.set('La description est obligatoire.'); return; }
    this.saving.set(true);
    this.rh.createRequest({ requestType: this.requestType(), description: this.description() })
      .subscribe({
        next: () => this.router.navigate(['/rh/requests']),
        error: () => { this.error.set('Erreur lors de la soumission.'); this.saving.set(false); },
      });
  }
}
```

- [ ] **Step 4 : Créer request-form.html**

```html
<!-- src/app/pages/rh/requests/request-form/request-form.html -->
<div class="form-page">
  <h1 class="page-title">Nouvelle demande</h1>
  @if (error()) { <p class="error-msg">{{ error() }}</p> }
  <div class="form-card">
    <div class="field">
      <label>Type de demande
        <select [value]="requestType()" (change)="requestType.set($any($event.target).value)">
          @for (t of types; track t) { <option [value]="t">{{ t }}</option> }
        </select>
      </label>
    </div>
    <div class="field">
      <label>Description *
        <textarea rows="5" [value]="description()" (input)="description.set($any($event.target).value)"
                  placeholder="Décrivez votre demande…"></textarea>
      </label>
    </div>
    <div class="form-actions">
      <button class="btn-primary" (click)="save()" [disabled]="saving()">
        {{ saving() ? 'Envoi…' : 'Soumettre la demande' }}
      </button>
    </div>
  </div>
</div>
```

- [ ] **Step 5 : Commit frontend requests + lifecycle**

```
git -C c:\Users\ITEC2\Documents\daf360-shell add src/app/pages/rh/requests/ src/app/pages/rh/lifecycle/
git -C c:\Users\ITEC2\Documents\daf360-shell commit -m "feat(rh-frontend): add request list/form and lifecycle views"
```

---

## JOUR 14 (11 juin) — Task 8 : Navigation & Intégration

**Files:**
- Modify: `src/app/layout/sidebar/sidebar.html`
- Modify: `src/app/layout/sidebar/sidebar.ts`

- [ ] **Step 1 : Ajouter les liens RH dans la sidebar**

Dans `sidebar.html`, après les liens existants, ajouter :

```html
<div class="sidebar-section">
  <span class="section-label">RH</span>
  <a routerLink="/rh/recruitment" routerLinkActive="nav-active" class="nav-link">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
      <circle cx="9" cy="7" r="4"/>
      <line x1="23" y1="11" x2="17" y2="11"/><line x1="20" y1="8" x2="20" y2="14"/>
    </svg>
    Recrutement
  </a>
  <a routerLink="/rh/requests" routerLinkActive="nav-active" class="nav-link">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
      <polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/>
    </svg>
    Mes demandes
  </a>
</div>
```

- [ ] **Step 2 : Vérifier les imports dans app.routes.ts**

S'assurer que toutes les routes référencent les bons composants :

```typescript
import { CandidateListComponent } from './pages/rh/recruitment/candidate-list/candidate-list';
import { CandidateFormComponent } from './pages/rh/recruitment/candidate-form/candidate-form';
import { CandidateDetailComponent } from './pages/rh/recruitment/candidate-detail/candidate-detail';
import { RequestListComponent } from './pages/rh/requests/request-list/request-list';
import { RequestFormComponent } from './pages/rh/requests/request-form/request-form';
```

- [ ] **Step 3 : Lancer le build Angular**

```
cd c:\Users\ITEC2\Documents\daf360-shell
npx ng build --configuration=development 2>&1 | tail -20
```
Expected: `Application bundle generation complete.` — 0 erreurs TypeScript.

- [ ] **Step 4 : Lancer tous les tests backend**

```
cd c:\Users\ITEC2\OneDrive\Documents\projects\daf360-rh-service\rh-service
mvn test -q
```
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 5 : Commit final intégration**

```
git -C c:\Users\ITEC2\Documents\daf360-shell add src/app/layout/sidebar/ src/app/app.routes.ts
git -C c:\Users\ITEC2\Documents\daf360-shell commit -m "feat(navigation): add RH section links in sidebar"
```

---

## JOUR 15 (12 juin) — Task 9 : Tests & Polish

- [ ] **Step 1 : Vérifier tous les endpoints avec curl**

```bash
# Créer un candidat
curl -s -X POST http://localhost:8081/api/hr/recruitment/candidates \
  -H "Content-Type: application/json" \
  -b "daf360_access=<token>" \
  -d '{"firstName":"Yassin","lastName":"Test","email":"y@test.com","appliedPosition":"Dev"}' | jq .

# Planifier un entretien
curl -s -X POST http://localhost:8081/api/hr/recruitment/interviews \
  -H "Content-Type: application/json" \
  -b "daf360_access=<token>" \
  -d '{"candidateId":1,"interviewType":"RH","scheduledAt":"2026-06-15T10:00:00"}' | jq .

# Créer une demande employé
curl -s -X POST "http://localhost:8081/api/hr/requests?employeeId=1" \
  -H "Content-Type: application/json" \
  -b "daf360_access=<token>" \
  -d '{"requestType":"DOCUMENT","description":"Attestation de travail"}' | jq .
```

- [ ] **Step 2 : Ajouter CSS manquant pour les formulaires partagés**

Dans `src/styles.css` (shell), ajouter les classes communes RH :

```css
/* RH shared form styles */
.form-page { max-width: 720px; margin: 0 auto; padding: 32px 24px; }
.form-card { background: #fff; border: 1px solid var(--color-border); border-radius: 12px; padding: 28px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
.field label { font-family: 'Manrope', sans-serif; font-size: 12px; font-weight: 700; color: var(--color-text-muted); text-transform: uppercase; letter-spacing: 0.06em; }
.field input, .field select, .field textarea {
  width: 100%; padding: 10px 14px; border: 1px solid var(--color-border); border-radius: 8px;
  font-family: 'Manrope', sans-serif; font-size: 14px; color: var(--color-text);
  background: var(--color-bg); outline: none; box-sizing: border-box;
}
.field input:focus, .field select:focus, .field textarea:focus { border-color: var(--color-primary); }
.form-actions { margin-top: 24px; display: flex; justify-content: flex-end; }
.btn-primary { background: var(--color-primary); color: #fff; padding: 10px 22px; border: none; border-radius: 8px; font-family: 'Manrope', sans-serif; font-size: 13px; font-weight: 700; cursor: pointer; text-decoration: none; }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-secondary { background: transparent; color: var(--color-primary); border: 1.5px solid var(--color-primary); padding: 9px 18px; border-radius: 8px; font-family: 'Manrope', sans-serif; font-size: 13px; font-weight: 700; cursor: pointer; }
.btn-link { color: var(--color-primary); background: none; border: none; cursor: pointer; font-family: 'Manrope', sans-serif; font-size: 13px; font-weight: 700; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 300; display: flex; align-items: center; justify-content: center; }
.modal-card { background: #fff; border-radius: 14px; padding: 28px; min-width: 400px; max-width: 560px; width: 90%; }
.modal-card h3 { font-family: 'Manrope', sans-serif; font-size: 17px; font-weight: 700; margin: 0 0 20px; }
.modal-actions { display: flex; gap: 12px; margin-top: 20px; }
.error-msg { color: #e74c3c; font-family: 'Manrope', sans-serif; font-size: 13px; margin-bottom: 16px; }
.empty-state { color: var(--color-text-muted); font-family: 'Manrope', sans-serif; padding: 32px; text-align: center; }
.loading-text { color: var(--color-text-muted); font-family: 'Manrope', sans-serif; }
.section { margin-top: 28px; }
.section-title { font-family: 'Manrope', sans-serif; font-size: 14px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--color-text-muted); margin-bottom: 12px; }
.request-card { background: #fff; border: 1px solid var(--color-border); border-radius: 10px; padding: 18px 20px; margin-bottom: 12px; }
.request-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.request-type { font-family: 'Manrope', sans-serif; font-size: 13px; font-weight: 700; color: var(--color-text); }
.request-desc { font-family: 'Manrope', sans-serif; font-size: 13px; color: var(--color-text); margin: 0 0 8px; }
.request-date { font-family: 'Manrope', sans-serif; font-size: 11px; color: var(--color-text-muted); margin: 0; }
.hr-notes { font-family: 'Manrope', sans-serif; font-size: 12px; color: var(--color-primary); margin: 8px 0 0; font-style: italic; }
.process-actions { display: flex; gap: 10px; align-items: center; margin-top: 14px; }
.hr-notes-input { flex: 1; padding: 8px 12px; border: 1px solid var(--color-border); border-radius: 7px; font-family: 'Manrope', sans-serif; font-size: 13px; }
.btn-approve { background: #27ae60; color: #fff; padding: 8px 16px; border: none; border-radius: 7px; font-family: 'Manrope', sans-serif; font-weight: 700; font-size: 12px; cursor: pointer; }
.btn-reject { background: #e74c3c; color: #fff; padding: 8px 16px; border: none; border-radius: 7px; font-family: 'Manrope', sans-serif; font-weight: 700; font-size: 12px; cursor: pointer; }
.interview-card { display: flex; align-items: center; gap: 16px; padding: 14px 18px; border: 1px solid var(--color-border); border-radius: 10px; margin-bottom: 10px; }
.interview-card.passed { border-left: 4px solid #27ae60; }
.interview-card.failed { border-left: 4px solid #e74c3c; }
.interview-round { background: var(--color-primary); color: #fff; font-family: 'Manrope', sans-serif; font-size: 11px; font-weight: 700; padding: 4px 10px; border-radius: 20px; white-space: nowrap; }
.interview-info { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.interview-type { font-family: 'Manrope', sans-serif; font-size: 13px; font-weight: 700; }
.interview-date { font-family: 'Manrope', sans-serif; font-size: 12px; color: var(--color-text-muted); }
.outcome-badge { padding: 4px 10px; border-radius: 20px; font-family: 'Manrope', sans-serif; font-size: 11px; font-weight: 700; }
.outcome-pending { background: #f39c1222; color: #f39c12; }
.outcome-passed { background: #27ae6022; color: #27ae60; }
.outcome-failed { background: #e74c3c22; color: #e74c3c; }
.offer-card { background: var(--color-bg); border: 1px solid var(--color-border); border-radius: 10px; padding: 18px 20px; }
.detail-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 24px; }
.detail-page { max-width: 820px; margin: 0 auto; padding: 32px 24px; }
.candidate-position { font-family: 'Manrope', sans-serif; font-size: 14px; color: var(--color-text-muted); margin: 4px 0 0; }
.header-actions { display: flex; gap: 10px; }
```

- [ ] **Step 3 : Build final et vérification**

```
cd c:\Users\ITEC2\Documents\daf360-shell && npx ng build --configuration=development
```
Expected : 0 erreurs.

```
cd c:\Users\ITEC2\OneDrive\Documents\projects\daf360-rh-service\rh-service && mvn test -q
```
Expected : `BUILD SUCCESS`.

- [ ] **Step 4 : Commit final**

```
git -C c:\Users\ITEC2\Documents\daf360-shell add src/styles.css
git -C c:\Users\ITEC2\Documents\daf360-shell commit -m "feat(rh): final polish — shared CSS for all RH pages"

git -C c:\Users\ITEC2\OneDrive\Documents\projects\daf360-rh-service\rh-service tag v0.2.0 -m "RH modules: recruitment, lifecycle, requests"
```

---

## Récapitulatif des endpoints implémentés

| Méthode | URL | Rôle requis | Description |
|---------|-----|-------------|-------------|
| GET | `/api/hr/recruitment/candidates` | HR, ADMIN, MANAGER | Liste des candidats |
| POST | `/api/hr/recruitment/candidates` | HR, ADMIN | Créer un candidat |
| GET | `/api/hr/recruitment/candidates/{id}` | HR, ADMIN, MANAGER | Détail candidat |
| POST | `/api/hr/recruitment/interviews` | HR, ADMIN | Planifier entretien |
| PUT | `/api/hr/recruitment/interviews/{id}/outcome` | HR, ADMIN, MANAGER | Saisir résultat |
| POST | `/api/hr/recruitment/offers` | HR, ADMIN | Envoyer une offre |
| PUT | `/api/hr/recruitment/offers/{id}/accept` | HR, ADMIN | Confirmer acceptation |
| PUT | `/api/hr/recruitment/offers/{id}/reject` | HR, ADMIN | Refuser offre |
| PUT | `/api/hr/employes/{id}/lifecycle/promote` | HR, ADMIN | Titulariser |
| PUT | `/api/hr/employes/{id}/lifecycle/resign` | HR, ADMIN | Enregistrer démission |
| GET | `/api/hr/requests` | HR, ADMIN | Toutes les demandes |
| GET | `/api/hr/requests/my` | Tout utilisateur | Mes demandes |
| POST | `/api/hr/requests` | Tout utilisateur | Soumettre une demande |
| PUT | `/api/hr/requests/{id}/process` | HR, ADMIN | Traiter une demande |

---

## Hors périmètre (reporté en V2)

- Upload de fichiers (CV, pièces jointes) — stockage local à implémenter séparément
- Signature électronique des contrats
- Tableau de bord KPI recrutement
- Entretien annuel / évaluation de performance
- Onboarding checklist
