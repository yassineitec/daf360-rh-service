package com.daf360.rh.service.sharepoint;

import com.daf360.rh.domain.EmployeeDocument;
import com.daf360.rh.repository.EmployeeDocumentRepository;
import com.daf360.rh.service.document.DocumentTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brings the documents HR files straight into SharePoint into {@code employee_documents}, so
 * the database stays the one source of truth (V121).
 *
 * <p><b>Why.</b> The dossier, the « Documents manquants » card and the per-employee documents
 * bar only read {@code employee_documents}. A row used to exist only when a file was uploaded
 * THROUGH the app — while HR files most paperwork directly into the SharePoint tree. Those
 * employees were reported as missing documents that were sitting right there.
 *
 * <p><b>What is read.</b> For every in-service employee, the folder RH configured for each
 * document type REQUIRED in their country (Administration › SharePoint: the path per type, and
 * the « requis » checklist). The type of an imported file is the type whose folder it sits in —
 * nothing is guessed from file names. A folder configured for two required types of the same
 * country cannot say which one a file is: it is skipped and counted as ambiguous.
 *
 * <p><b>What is written</b>, per file found:
 * <ul>
 *   <li>a file the app uploaded itself ({@code <docId>_name}, see the document mirror) — nothing,
 *       its LOCAL row already covers it;</li>
 *   <li>a file never seen — a row: {@code storage_provider = SHAREPOINT}, {@code external_id} =
 *       the drive item id, PENDING, so HR can still verify or reject it;</li>
 *   <li>a file whose row the SYNC removed earlier ({@code deleted_by} null) — restored. A row HR
 *       deleted by hand stays deleted: that was a decision.</li>
 * </ul>
 * A SHAREPOINT row whose file is no longer in its folder is soft-deleted ({@code deleted_by}
 * null = "removed by the sync"), so the card flags the document as missing again.
 *
 * <p><b>Removal is only ever decided on an ANSWERED listing.</b> {@code listTree} returns empty
 * when it could not look (Graph down, folder missing, unconfigured) — a folder that could not be
 * read never removes anything, or one bad night of Graph would empty every dossier.
 *
 * <p>Idempotent: the unique index {@code UX_emp_docs_profile_external} (V121) makes a second run,
 * or two runs racing, unable to file the same SharePoint file twice in a dossier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePointDocumentSyncService {

    /** Marker in {@code notes} so the dossier says where a row came from. */
    static final String IMPORT_NOTE = "Importé depuis SharePoint";

    /** One employee type folder is a handful of files; past this the listing is not trusted
     *  as complete, and nothing is removed from it. */
    private static final int MAX_FILES_PER_FOLDER = 500;

    private static final String IN_SERVICE_SQL = "('ACTIVE','ON_LEAVE','ON_MISSION')";

    @Value("${app.sharepoint.document-sync-enabled:true}")
    private boolean enabled;

    private final JdbcTemplate jdbc;
    private final GraphSharePointService graph;
    private final EmployeeFolderResolver folderResolver;
    private final SharePointLocationService locationService;
    private final DocumentTypeService documentTypeService;
    private final EmployeeDocumentRepository documentRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Counts for one run, persisted in {@code sharepoint_document_sync_runs}. Also the LIVE
     * progress of the run in flight: the worker writes it, the status endpoint reads it from
     * another thread — hence volatile (a slightly stale number is fine, a never-visible one
     * is not).
     */
    public static final class Report {
        public volatile int profilesScanned, foldersListed, foldersUnreadable, foldersAmbiguous;
        public volatile int imported, restored, removed;
        /** Employees whose pass threw — the run goes on without them. */
        public volatile int profilesFailed;
        /** Employees in scope, known once the list is loaded (0 before). */
        public volatile int profilesTotal;
        /** Who is being processed right now — shown under the progress bar. */
        public volatile String current;
        public volatile String lastError;
        /** The whole pass could not run (SharePoint unconfigured, profile query failed…). */
        public volatile boolean fatal;
        public final OffsetDateTime startedAt = OffsetDateTime.now();

        @Override public String toString() {
            return "profils=" + profilesScanned + "/" + profilesTotal + " echecs=" + profilesFailed
                    + " dossiers=" + foldersListed
                    + " illisibles=" + foldersUnreadable + " ambigus=" + foldersAmbiguous
                    + " importes=" + imported + " restaures=" + restored + " retires=" + removed;
        }
    }

    /** Prefix of {@code last_error} for a run that could not run at all (vs a few failed dossiers). */
    static final String FATAL_PREFIX = "[ÉCHEC] ";

    /** The run in flight, null when idle. */
    private volatile Report live;

    /** The run in flight, or null when idle. */
    public Report liveProgress() {
        return running.get() ? live : null;
    }

    /**
     * Manual trigger (Administration › SharePoint › Documents requis). Runs in the background:
     * a full pass is minutes of Graph calls, far past any proxy timeout. The screen follows it
     * through {@link #lastRun()}.
     *
     * @return false when a run is already in progress (nightly or manual)
     */
    public boolean startInBackground() {
        // Claimed HERE, before answering: the screen's first poll must already see "running",
        // not a stale finished run in the gap before the worker thread starts.
        if (!running.compareAndSet(false, true)) return false;
        Thread worker = new Thread(() -> {
            try {
                runPass();
            } finally {
                running.set(false);
            }
        }, "sharepoint-document-sync");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * How a run ended, as the screen states it:
     * SUCCESS — every dossier processed; PARTIAL — finished, some dossiers failed;
     * FAILED — the pass could not run; INTERRUPTED — started, never finished (service restarted
     * mid-run); RUNNING — the run in flight.
     */
    public enum Outcome { RUNNING, SUCCESS, PARTIAL, FAILED, INTERRUPTED }

    /** One run as the admin screen shows it. */
    public record RunDto(Long id, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                         int profilesScanned, int foldersListed, int foldersUnreadable,
                         int foldersAmbiguous, int imported, int restored, int removed,
                         String lastError, Outcome outcome) {}

    /** The most recent run, or null (none yet, or V121 not applied). */
    public RunDto lastRun() {
        try {
            List<RunDto> rows = jdbc.query(
                    "SELECT TOP 1 * FROM [dbo].[sharepoint_document_sync_runs] ORDER BY id DESC",
                    (rs, i) -> {
                        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
                        String error = rs.getString("last_error");
                        Outcome outcome =
                                finished == null ? (running.get() ? Outcome.RUNNING : Outcome.INTERRUPTED)
                              : error == null    ? Outcome.SUCCESS
                              : error.startsWith(FATAL_PREFIX) ? Outcome.FAILED
                              : Outcome.PARTIAL;
                        return new RunDto(
                                rs.getLong("id"),
                                rs.getObject("started_at", OffsetDateTime.class), finished,
                                rs.getInt("profiles_scanned"), rs.getInt("folders_listed"),
                                rs.getInt("folders_unreadable"), rs.getInt("folders_ambiguous"),
                                rs.getInt("imported"), rs.getInt("restored"), rs.getInt("removed"),
                                error, outcome);
                    });
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("sharepoint_document_sync_runs illisible ({})", e.getMessage());
            return null;
        }
    }

    /** Every night, after the photo/presence jobs and well before the 06:00 presence run. */
    @Scheduled(cron = "0 30 2 * * *")
    public void nightly() {
        if (!enabled) return;
        syncAll();
    }

    /**
     * One full pass. Public so an admin action can run it on demand later; never runs twice at
     * once (a second caller gets null).
     */
    public Report syncAll() {
        if (!running.compareAndSet(false, true)) {
            log.info("Synchro documents SharePoint deja en cours — ignoree");
            return null;
        }
        try {
            return runPass();
        } finally {
            running.set(false);
        }
    }

    /** One full pass; the caller holds {@link #running}. */
    private Report runPass() {
        Report report = new Report();
        live = report;
        Long runId = startRun();
        String lastFailure = null;
        try {
            if (!graph.isConfigured()) {
                report.fatal = true;
                report.lastError = "SharePoint non configuré (MS_GRAPH_* absents) — rien n'a été synchronisé.";
                return report;
            }
            List<Map<String, Object>> profiles = jdbc.queryForList(
                    "SELECT ep.id, ep.pays_id, ep.user_id, u.fullName " +
                    "FROM [dbo].[employee_profiles] ep LEFT JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                    "WHERE ep.deleted = 0 AND ep.pays_id IS NOT NULL AND ep.lifecycle_status IN " + IN_SERVICE_SQL +
                    " ORDER BY u.fullName");
            report.profilesTotal = profiles.size();
            int sinceSave = 0;
            for (Map<String, Object> p : profiles) {
                report.current = p.get("fullName") != null ? (String) p.get("fullName") : "#" + p.get("id");
                try {
                    syncProfile(((Number) p.get("id")).longValue(),
                                ((Number) p.get("pays_id")).longValue(),
                                p.get("user_id") == null ? null : ((Number) p.get("user_id")).longValue(),
                                report);
                } catch (Exception e) {
                    // One dossier must never stop the others.
                    report.profilesFailed++;   // already counted in profilesScanned by syncProfile
                    lastFailure = report.current + " : " + e.getMessage();
                    log.warn("Synchro SharePoint du profil {} en echec: {}", p.get("id"), e.getMessage());
                }
                // Counts on disk as the run goes: a restart mid-run still leaves a readable record.
                if (++sinceSave >= 10) { saveRun(runId, report, false); sinceSave = 0; }
            }
            if (report.profilesFailed > 0) {
                report.lastError = report.profilesFailed + " dossier(s) en échec — dernier : " + lastFailure;
            }
            log.info("Synchro documents SharePoint terminee : {}", report);
            return report;
        } catch (Exception e) {
            report.fatal = true;
            report.lastError = "La synchronisation s'est arrêtée : " + e.getMessage();
            log.error("Synchro documents SharePoint interrompue", e);
            return report;
        } finally {
            report.current = null;
            saveRun(runId, report, true);
        }
    }

    // ── One employee ─────────────────────────────────────────────────────────

    private void syncProfile(long profileId, long paysId, Long userId, Report report) {
        report.profilesScanned++;
        List<String> required = documentTypeService.requiredCodes(paysId);
        if (required.isEmpty()) return;

        EmployeeFolderResolver.EmployeeFolder folder = folderResolver.resolve(paysId, userId);
        if (folder == null) {          // no root for the country, no name, or a homonym
            report.foldersUnreadable++;
            return;
        }

        // Folder → the required types configured on it. A type with no configured path is
        // simply not synchronised: RH has not said where it lives.
        Map<String, List<String>> typesByFolder = new LinkedHashMap<>();
        Map<String, String> pathByKey = new HashMap<>();
        for (String code : required) {
            locationService.templateForCode(paysId, code).ifPresent(template -> {
                String path = template.replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, folder.employeeFolder());
                String key = path.toLowerCase(Locale.ROOT);
                pathByKey.putIfAbsent(key, path);
                typesByFolder.computeIfAbsent(key, k -> new ArrayList<>()).add(code);
            });
        }

        List<EmployeeDocument> rows = documentRepository.findByEmployeeProfileId(profileId);
        Map<String, EmployeeDocument> byExternalId = new HashMap<>();
        Set<Long> ownIds = new HashSet<>();
        for (EmployeeDocument d : rows) {
            ownIds.add(d.getId());
            if (d.getExternalId() != null) byExternalId.put(d.getExternalId(), d);
        }

        Set<String> listedTypes = new HashSet<>();   // types whose folder was answered in full
        Set<String> seenIds     = new HashSet<>();

        for (Map.Entry<String, List<String>> entry : typesByFolder.entrySet()) {
            if (entry.getValue().size() > 1) {
                report.foldersAmbiguous++;
                log.info("Dossier SharePoint partage par {} (pays {}) — type indeterminable, ignore",
                        entry.getValue(), paysId);
                continue;
            }
            String code = entry.getValue().get(0);
            var listing = graph.listTree(pathByKey.get(entry.getKey()), 1, MAX_FILES_PER_FOLDER);
            if (listing.isEmpty()) {
                report.foldersUnreadable++;
                continue;
            }
            report.foldersListed++;
            List<GraphSharePointService.TreeFile> files = listing.get().files();
            if (files.size() < MAX_FILES_PER_FOLDER) listedTypes.add(code);

            for (GraphSharePointService.TreeFile file : files) {
                // listTree with depth 1 still reports files of the folder itself only.
                if (file.folderPath() != null && !file.folderPath().isEmpty()) continue;
                if (file.id() == null) continue;
                seenIds.add(file.id());
                if (isFiledByApp(file.name(), ownIds)) continue;

                EmployeeDocument existing = byExternalId.get(file.id());
                if (existing == null) {
                    documentRepository.save(newRow(profileId, code, file));
                    report.imported++;
                } else if (Boolean.TRUE.equals(existing.getIsDeleted()) && existing.getDeletedBy() == null) {
                    existing.setIsDeleted(false);
                    existing.setDeletedAt(null);
                    documentRepository.save(existing);
                    report.restored++;
                } else if (!Boolean.TRUE.equals(existing.getIsDeleted())
                        && file.name() != null && !file.name().equals(existing.getFileName())) {
                    existing.setFileName(file.name());       // renamed in SharePoint
                    documentRepository.save(existing);
                }
            }
        }

        // Rows the sync owns, of a type whose folder was fully read, whose file is gone.
        for (EmployeeDocument d : rows) {
            if (!"SHAREPOINT".equals(d.getStorageProvider())) continue;
            if (Boolean.TRUE.equals(d.getIsDeleted()) || d.getExternalId() == null) continue;
            if (!listedTypes.contains(d.getDocumentType())) continue;
            if (seenIds.contains(d.getExternalId())) continue;
            d.setIsDeleted(true);
            d.setDeletedAt(OffsetDateTime.now());
            d.setDeletedBy(null);                            // null = removed by the sync
            documentRepository.save(d);
            report.removed++;
        }
    }

    private EmployeeDocument newRow(long profileId, String code, GraphSharePointService.TreeFile file) {
        String webUrl = file.webUrl();
        return EmployeeDocument.builder()
                .employeeProfileId(profileId)
                .documentType(code)
                // file_url is NOT NULL, 500 chars. Downloads go through external_id, never this.
                .fileUrl(webUrl != null && webUrl.length() <= 500 ? webUrl : "sharepoint:item/" + file.id())
                .fileName(file.name())
                .fileSizeKb(file.sizeBytes() == null ? null : (int) (file.sizeBytes() / 1024))
                .verificationStatus("PENDING")
                .uploadedAt(parseOrNow(file.createdAt() != null ? file.createdAt() : file.lastModified()))
                .notes(IMPORT_NOTE)
                .storageProvider("SHAREPOINT")
                .externalId(file.id())
                .isDeleted(false)
                .build();
    }

    /** {@code 42_contrat.pdf} where 42 is a document of THIS dossier — the app's own mirror. */
    private static boolean isFiledByApp(String name, Set<Long> ownIds) {
        if (name == null) return false;
        int underscore = name.indexOf('_');
        if (underscore <= 0) return false;
        try {
            return ownIds.contains(Long.parseLong(name.substring(0, underscore)));
        } catch (NumberFormatException notAnId) {
            return false;
        }
    }

    private static OffsetDateTime parseOrNow(String iso) {
        try {
            return iso == null ? OffsetDateTime.now() : OffsetDateTime.parse(iso);
        } catch (Exception e) {
            return OffsetDateTime.now();
        }
    }

    // ── Run log (V121) — best effort: a server without the table still syncs ──

    private Long startRun() {
        try {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO [dbo].[sharepoint_document_sync_runs] (started_at) VALUES (SYSDATETIMEOFFSET())",
                        Statement.RETURN_GENERATED_KEYS);
                return ps;
            }, key);
            return key.getKey() == null ? null : key.getKey().longValue();
        } catch (Exception e) {
            log.debug("sharepoint_document_sync_runs indisponible ({})", e.getMessage());
            return null;
        }
    }

    /**
     * Writes the counts. {@code finished} stamps {@code finished_at} — until then the row reads
     * as RUNNING (or INTERRUPTED once the service is no longer running it).
     */
    private void saveRun(Long runId, Report r, boolean finished) {
        if (runId == null) return;
        String error = r.lastError == null ? null : (r.fatal ? FATAL_PREFIX : "") + r.lastError;
        try {
            jdbc.update(
                    "UPDATE [dbo].[sharepoint_document_sync_runs] SET " +
                    (finished ? "finished_at = SYSDATETIMEOFFSET(), " : "") +
                    "profiles_scanned = ?, folders_listed = ?, folders_unreadable = ?, folders_ambiguous = ?, " +
                    "imported = ?, restored = ?, removed = ?, last_error = ? WHERE id = ?",
                    r.profilesScanned, r.foldersListed, r.foldersUnreadable, r.foldersAmbiguous,
                    r.imported, r.restored, r.removed,
                    error == null ? null : error.substring(0, Math.min(1000, error.length())),
                    runId);
        } catch (Exception e) {
            log.debug("Ecriture du bilan de synchro impossible: {}", e.getMessage());
        }
    }
}
