package com.daf360.rh.service.sharepoint;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.repository.EmployeeProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The single place that answers "where does this employee's {kind} live in SharePoint?".
 *
 * <p>Replaces three implementations of that question that had drifted apart: the photo
 * mirror's own copy in {@code EmployeeProfileService}, the document mirror's in
 * {@code EmployeeDocumentService}, and the generated-PDF path in {@code PdfDocumentService}.
 * Nothing is switched over to it in this phase — the point of phase A is that this exists,
 * is tested, and can be inspected through the diagnostic endpoint without any existing
 * behaviour changing.
 *
 * <p>Two rules shape everything below:
 *
 * <ol>
 *   <li><b>A miss always has a reason, and the reason is always logged.</b> The predecessor
 *       returned bare null from six different branches, which is why finding out that a
 *       working integration simply had never been asked for one employee's photo took an hour
 *       of reading Graph by hand.</li>
 *   <li><b>Matching never guesses.</b> Case, spacing and accents fold; nothing else does. Two
 *       candidate folders, or two employees with the same name, are refused rather than
 *       resolved — on a payslip the cost of a wrong guess is showing somebody another
 *       person's salary.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePointResolver {

    /**
     * How long a cached FAILURE is trusted before it is retried.
     *
     * <p>Exists to stop an unresolvable employee from costing Graph calls on every single
     * render. A day is short enough that fixing a folder in SharePoint shows up the same
     * working day, and the admin panel's forced resolve bypasses it entirely, so nobody has to
     * wait on it after making a correction.
     */
    private static final Duration NEGATIVE_TTL = Duration.ofHours(24);

    /**
     * How long a cached SUCCESS is trusted. Longer than the failure window: a folder that
     * resolved once rarely moves, and the expensive case we are avoiding is the miss.
     */
    private static final Duration POSITIVE_TTL = Duration.ofDays(7);

    private final EmployeeProfileRepository profileRepository;
    private final EmployeeFolderResolver employeeFolderResolver;
    private final SharePointLocationService locationService;
    private final EmployeeSharePointFolderStore folderStore;
    private final GraphSharePointService graph;

    /**
     * Where an employee's documents of one kind live.
     *
     * @param path           the template with {@code {employeeFolder}} substituted.
     *                       {@code {year}} is left in place for year-scoped kinds — the caller
     *                       enumerates years via {@link #years}. Null unless FOUND.
     * @param basePath       the folder that must exist for the kind to be usable: {@code path}
     *                       for a flat kind, the folder holding the year folders for a
     *                       year-scoped one. Null unless FOUND.
     * @param employeeFolder the real folder segment that was matched, for display
     * @param detail         a short human-readable reason, always set when status != FOUND
     */
    public record ResolvedLocation(SharePointStatus status, String path, String basePath,
                                   String employeeFolder, ResolutionSource source, String detail) {

        public boolean isFound() { return status == SharePointStatus.FOUND; }

        static ResolvedLocation miss(SharePointStatus status, String detail) {
            return new ResolvedLocation(status, null, null, null, null, detail);
        }
    }

    /** Cached-where-possible resolution for a built-in kind. What request paths call. */
    public ResolvedLocation resolve(Long employeeProfileId, DocKind kind) {
        return resolve(employeeProfileId, kind == null ? null : KindRef.of(kind), false);
    }

    /** As {@link #resolve(Long, DocKind)}, with the forced variant for a built-in kind. */
    public ResolvedLocation resolve(Long employeeProfileId, DocKind kind, boolean force) {
        return resolve(employeeProfileId, kind == null ? null : KindRef.of(kind), force);
    }

    /**
     * Resolution for any kind, built-in or a {@code document_types} code — see {@link KindRef}.
     *
     * @param force skip both caches and re-run discovery. What the admin panel's "re-resolve"
     *              uses, so a correction in SharePoint can be picked up immediately instead of
     *              waiting out {@link #NEGATIVE_TTL}. A MANUAL override still wins — forcing
     *              refreshes a discovery, it does not discard a human's answer.
     */
    public ResolvedLocation resolve(Long employeeProfileId, KindRef kind, boolean force) {
        if (employeeProfileId == null || kind == null) {
            return ResolvedLocation.miss(SharePointStatus.NO_CONFIG, "requete incomplete");
        }

        EmployeeProfile profile = profileRepository.findById(employeeProfileId).orElse(null);
        if (profile == null) {
            return ResolvedLocation.miss(SharePointStatus.NO_CONFIG,
                    "profil " + employeeProfileId + " introuvable");
        }

        Optional<String> template = locationService.templateForCode(profile.getPaysId(), kind.code());
        if (template.isEmpty()) {
            // Not logged: a country with no path configured for a kind is a normal, expected
            // state (Egypt has no payroll tree yet), and logging it would fire on every render.
            return ResolvedLocation.miss(SharePointStatus.NO_CONFIG,
                    "aucun chemin configure pour le pays " + profile.getPaysId() + " / " + kind);
        }

        Optional<EmployeeSharePointFolderStore.CachedFolder> cached =
                folderStore.find(employeeProfileId, kind);

        // A MANUAL override is the answer, full stop — no discovery, no TTL, not even under
        // force. It exists precisely for folders discovery cannot find.
        if (cached.isPresent() && cached.get().isManual() && cached.get().isUsable()) {
            return build(template.get(), cached.get().folderSegment(), ResolutionSource.MANUAL,
                    kind, false);
        }

        if (!force && cached.isPresent() && isFresh(cached.get())) {
            EmployeeSharePointFolderStore.CachedFolder hit = cached.get();
            if (hit.status() == SharePointStatus.FOUND && hit.isUsable()) {
                return build(template.get(), hit.folderSegment(), ResolutionSource.DISCOVERED,
                        kind, false);
            }
            // A remembered failure, returned without touching Graph. This is the call that
            // used to cost ~5 Graph requests per avatar render.
            return ResolvedLocation.miss(hit.status(),
                    hit.lastError() == null ? "echec memorise" : hit.lastError());
        }

        return discover(profile, kind, template.get());
    }

    /** One employee's outcome in a batch resolve. */
    public record BatchRow(Long profileId, String employeeFolder, SharePointStatus status,
                           ResolutionSource source, String detail) {}

    /**
     * Resolves every employee for one kind, in one pass.
     *
     * <p>The reason this exists: resolution was previously lazy and request-driven, so an
     * employee's documents were only ever located if somebody happened to open a page showing
     * them. On the live tenant that meant two of roughly a hundred and thirty employees had a
     * resolved photo, and the difference between them was which profiles had been viewed since
     * the Graph credentials were installed. A batch pass turns that from an accident into an
     * operation, and its result is the admin panel's "who resolves, who does not" table.
     *
     * <p>Sequential on purpose. Graph throttles, and the failure mode of parallelising a
     * hundred-plus lookups is a 429 storm that makes every row fail for a reason that has
     * nothing to do with the folders.
     */
    public List<BatchRow> resolveAll(KindRef kind, boolean force) {
        if (kind == null) return List.of();
        List<BatchRow> rows = new java.util.ArrayList<>();
        for (EmployeeProfile profile : profileRepository.findAll()) {
            ResolvedLocation loc = resolve(profile.getId(), kind, force);
            rows.add(new BatchRow(profile.getId(), loc.employeeFolder(), loc.status(),
                    loc.source(), loc.detail()));
        }
        long found = rows.stream().filter(r -> r.status() == SharePointStatus.FOUND).count();
        log.info("Resolution SharePoint {} terminee : {} trouves sur {} employes",
                kind, found, rows.size());
        return rows;
    }

    /**
     * The year folders under a resolved year-scoped location, newest first.
     *
     * <p>Separate from {@link #resolve} because listing years is a Graph round trip that only
     * a caller actually reading files needs — the admin panel's status table does not, and
     * would otherwise pay for 130 of them.
     *
     * @param limit most recent N years, or all when null. Truncation is logged: a silently
     *              capped list reads as "this is everything" when it is not.
     */
    public List<String> years(ResolvedLocation location, Integer limit) {
        if (location == null || !location.isFound() || location.basePath() == null) return List.of();
        List<String> all = graph.listFolders(location.basePath());
        if (limit == null || limit <= 0 || all.size() <= limit) return all;
        log.info("Dossiers annuels tronques sous {} : {} sur {} retenus",
                location.basePath(), limit, all.size());
        return all.subList(0, limit);
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private ResolvedLocation discover(EmployeeProfile profile, KindRef kind, String template) {
        Long profileId = profile.getId();

        String fullName = employeeFolderResolver.fullNameOf(profile.getUserId());
        String wanted = employeeFolderResolver.normalize(fullName);
        if (wanted == null) {
            return remember(profileId, kind, null, SharePointStatus.NO_NAME,
                    "aucun fullName exploitable pour l utilisateur " + profile.getUserId());
        }
        if (employeeFolderResolver.isAmbiguous(wanted, profile.getPaysId())) {
            return remember(profileId, kind, null, SharePointStatus.AMBIGUOUS_EMPLOYEE,
                    "plusieurs employes du pays " + profile.getPaysId()
                    + " portent le nom '" + wanted + "'");
        }

        ResolvedLocation candidate =
                build(template, wanted, ResolutionSource.DISCOVERED, kind, true);
        if (candidate.isFound()) {
            folderStore.saveDiscovered(profileId, kind, wanted, SharePointStatus.FOUND, null);
            return candidate;
        }
        return remember(profileId, kind, null, candidate.status(), candidate.detail());
    }

    /**
     * Substitutes the employee folder into the template and, when asked, confirms the folder
     * is really there.
     *
     * @param verify false when the segment came from a trusted cache or a MANUAL override —
     *               re-checking existence on every cache hit would defeat the cache and put
     *               the Graph call back on the request path.
     */
    private ResolvedLocation build(String template, String employeeFolder, ResolutionSource source,
                                  KindRef kind, boolean verify) {
        String path = template.replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, employeeFolder);
        String basePath = kind.yearScoped()
                ? SharePointPaths.stripFrom(path, SharePointPaths.YEAR_TOKEN)
                : path;

        if (verify && !graph.folderExists(basePath)) {
            // Cannot tell "folder absent" from "Graph unreachable" through folderExists, and
            // the caller must not need to: both mean no documents this time. isConfigured
            // is the one distinction worth surfacing, because the fix is an env variable
            // rather than a folder.
            SharePointStatus status = graph.isConfigured()
                    ? SharePointStatus.FOLDER_MISSING
                    : SharePointStatus.UNAVAILABLE;
            return ResolvedLocation.miss(status, "dossier introuvable: " + basePath);
        }
        return new ResolvedLocation(SharePointStatus.FOUND, path, basePath, employeeFolder,
                source, null);
    }

    /**
     * Caches a failure and logs it exactly once per resolution.
     *
     * <p>This single log line is the fix for the defect that made the photo integration
     * un-diagnosable. It is at INFO rather than WARN on purpose: for most of these statuses an
     * unconfigured country or an employee with no documents yet is a normal state, not an
     * incident, and a WARN storm would train everyone to ignore it.
     */
    private ResolvedLocation remember(Long profileId, KindRef kind, String segment,
                                      SharePointStatus status, String detail) {
        folderStore.saveDiscovered(profileId, kind, segment, status, detail);
        log.info("SharePoint {} non resolu pour le profil {} : {} ({})",
                kind, profileId, status, detail);
        return ResolvedLocation.miss(status, detail);
    }

    private boolean isFresh(EmployeeSharePointFolderStore.CachedFolder cached) {
        OffsetDateTime at = cached.resolvedAt();
        if (at == null) return false;
        Duration ttl = cached.status() == SharePointStatus.FOUND ? POSITIVE_TTL : NEGATIVE_TTL;
        return Duration.between(at, OffsetDateTime.now()).compareTo(ttl) < 0;
    }
}
