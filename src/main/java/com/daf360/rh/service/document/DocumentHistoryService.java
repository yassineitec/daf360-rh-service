package com.daf360.rh.service.document;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.SharePointLocationService;
import com.daf360.rh.service.sharepoint.SharePointPaths;
import com.daf360.rh.service.sharepoint.SharePointResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The employee's whole SharePoint dossier, every tree, every subfolder — the "Historique" tab of
 * the profile page.
 *
 * <p>Where {@code EmployeeDocumentService.listRemote} lists ONE subfolder for ONE document type,
 * this walks each employee root the configuration knows about:
 * <pre>
 *   Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/**   ← pays.photo_sharepoint_location
 *   Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/**          ← sharepoint_locations (PAYSLIP…)
 * </pre>
 * No root is hardcoded: each comes from an existing template cut at {@code {employeeFolder}},
 * and a country with only one tree configured simply shows one tree.
 *
 * <p><b>Security model.</b> Nothing in a request names a path. The roots are derived from the
 * profile, and a download names an item id that must appear in the listing THIS service built
 * for THIS profile — the same "handle, not path" rule {@code MyPayslipService} applies, because
 * the payroll tree holds every employee's salary.
 *
 * <p><b>Cost.</b> One Graph call per folder walked, so the result is cached per profile for
 * {@code app.sharepoint.history.cache-ttl-minutes}. {@code refresh=true} bypasses it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentHistoryService {

    private final EmployeeProfileRepository profileRepository;
    private final EmployeeFolderResolver    employeeFolderResolver;
    private final SharePointResolver        resolver;
    private final SharePointLocationService locationService;
    private final GraphSharePointService    graph;

    @Value("${app.sharepoint.history.max-depth:4}")
    private int maxDepth;

    @Value("${app.sharepoint.history.max-items:1000}")
    private int maxItems;

    @Value("${app.sharepoint.history.cache-ttl-minutes:10}")
    private long cacheTtlMinutes;

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant at, History history) {}

    /**
     * Outcome of the whole lookup.
     * <ul>
     *   <li>{@code FOUND} — at least one root was listed</li>
     *   <li>{@code NO_CONFIG} — no root could be derived (no template for the country, no usable
     *       or an ambiguous employee name)</li>
     *   <li>{@code FOLDER_MISSING} — roots derived, none of them exists or could be listed</li>
     *   <li>{@code UNAVAILABLE} — Graph credentials are not set on this deployment</li>
     * </ul>
     */
    public enum Status { FOUND, NO_CONFIG, FOLDER_MISSING, UNAVAILABLE }

    /**
     * One tree the walk looked at.
     *
     * @param key   stable id for the frontend filter — the tree's own folder name
     *              (e.g. {@code 01_Contracts-Employment})
     * @param path  the employee root as SharePoint spells it; shown to HR so a "missing" tree
     *              can be checked by hand
     * @param found false when the root does not exist or could not be listed
     */
    public record Root(String key, String path, boolean found, boolean truncated) {}

    /**
     * One file of the dossier.
     *
     * @param category   the first subfolder under the employee root ({@code Identity Documents},
     *                   {@code 01_Pay-Slip}…), null for a file sitting in the root itself
     * @param folderPath the full folder path relative to the employee root
     * @param filedByApp true when the name carries the {@code {docId}_} prefix the app writes on
     *                   upload, i.e. the same piece the Documents tab already lists
     */
    public record Entry(String id, String name, String rootKey, String category, String folderPath,
                        String createdAt, String modifiedAt, String createdBy, String modifiedBy,
                        Long sizeBytes, String webUrl, boolean filedByApp, Long docId) {}

    public record History(Status status, List<Root> roots, List<Entry> items,
                          boolean truncated, String fetchedAt) {}

    /** One file's bytes, with the name and type for the response headers. */
    public record FilePayload(String fileName, String contentType, byte[] bytes) {}

    // ── Read ──────────────────────────────────────────────────────────────────

    public History history(Long profileId, boolean refresh) {
        EmployeeProfile profile = requireProfile(profileId);
        if (!refresh) {
            Cached hit = cache.get(profileId);
            if (hit != null && Duration.between(hit.at(), Instant.now())
                    .compareTo(Duration.ofMinutes(cacheTtlMinutes)) < 0) {
                return hit.history();
            }
        }
        History fresh = build(profile);
        // Failures are not cached: "Graph had a bad minute" must not stick for ten.
        if (fresh.status() == Status.FOUND) {
            cache.put(profileId, new Cached(Instant.now(), fresh));
        } else {
            cache.remove(profileId);
        }
        return fresh;
    }

    /**
     * One file of this employee's dossier.
     *
     * <p>The id must be in this profile's listing. A cached listing is tried first; on a miss it
     * is rebuilt once, so a file filed a minute ago is still reachable. Unknown id, unlistable
     * dossier and failed download all answer the same NOT_FOUND on purpose — distinct errors
     * would tell a caller whether an id exists somewhere on the site.
     */
    public FilePayload download(Long profileId, String itemId) {
        Entry entry = findEntry(history(profileId, false), itemId)
                .or(() -> findEntry(history(profileId, true), itemId))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Document introuvable"));

        byte[] bytes = graph.downloadItem(entry.id()).orElseThrow(() ->
                new AppException(ErrorCode.NOT_FOUND, "Document introuvable sur SharePoint"));

        String contentType = MediaTypeFactory.getMediaType(entry.name())
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return new FilePayload(entry.name(), contentType, bytes);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private History build(EmployeeProfile profile) {
        String now = Instant.now().toString();
        if (!graph.isConfigured()) {
            return new History(Status.UNAVAILABLE, List.of(), List.of(), false, now);
        }

        Map<String, String> roots = rootsFor(profile);
        if (roots.isEmpty()) {
            return new History(Status.NO_CONFIG, List.of(), List.of(), false, now);
        }

        List<Root> rootRows = new ArrayList<>();
        List<Entry> items = new ArrayList<>();
        boolean truncated = false;

        for (Map.Entry<String, String> r : roots.entrySet()) {
            String key = r.getKey();
            Optional<GraphSharePointService.TreeListing> listing =
                    graph.listTree(r.getValue(), maxDepth, maxItems);
            if (listing.isEmpty()) {
                rootRows.add(new Root(key, r.getValue(), false, false));
                continue;
            }
            GraphSharePointService.TreeListing l = listing.get();
            rootRows.add(new Root(key, l.resolvedRoot(), true, l.truncated()));
            truncated |= l.truncated();
            for (GraphSharePointService.TreeFile f : l.files()) {
                Long docId = parseDocIdPrefix(f.name());
                items.add(new Entry(f.id(), f.name(), key, categoryOf(f.folderPath()),
                        f.folderPath(), f.createdAt(), f.lastModified(),
                        f.createdBy(), f.modifiedBy(), f.sizeBytes(), f.webUrl(),
                        docId != null, docId));
            }
        }

        // Newest first. Graph's ISO-8601 UTC strings sort correctly as text.
        items.sort(Comparator.comparing(
                (Entry e) -> e.modifiedAt() == null ? "" : e.modifiedAt()).reversed());

        boolean anyFound = rootRows.stream().anyMatch(Root::found);
        return new History(anyFound ? Status.FOUND : Status.FOLDER_MISSING,
                rootRows, items, truncated, now);
    }

    /**
     * The employee roots to walk, keyed by tree name, de-duplicated.
     *
     * <p>Two sources, both already used elsewhere so they cannot disagree with the rest of the app:
     * <ol>
     *   <li>{@code EmployeeFolderResolver} — the contracts tree, exactly where document uploads go</li>
     *   <li>{@code SharePointResolver} for every {@link DocKind} — honours MANUAL folder overrides,
     *       which is what reaches deviating folders such as the payroll tree's
     *       {@code "Bilel ZEDINI-CDI-ARX tunisie"}</li>
     * </ol>
     * Two kinds usually share one employee root (PAYSLIP and SALARY_CERTIFICATE); the fold-compare
     * keeps it walked once.
     */
    private Map<String, String> rootsFor(EmployeeProfile profile) {
        List<String> candidates = new ArrayList<>();

        EmployeeFolderResolver.EmployeeFolder contracts =
                employeeFolderResolver.resolve(profile.getPaysId(), profile.getUserId());
        if (contracts != null) candidates.add(contracts.basePath());

        for (DocKind kind : DocKind.values()) {
            SharePointResolver.ResolvedLocation loc = resolver.resolve(profile.getId(), kind);
            if (!loc.isFound() || loc.employeeFolder() == null) continue;
            locationService.templateFor(profile.getPaysId(), kind)
                    .map(SharePointPaths::employeeFolderBase)
                    .map(base -> base.replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, loc.employeeFolder()))
                    .ifPresent(candidates::add);
        }

        Map<String, String> roots = new LinkedHashMap<>();
        for (String path : candidates) {
            boolean dup = roots.values().stream().anyMatch(p -> samePath(p, path));
            if (dup) continue;
            String key = treeKey(path);
            // Two different roots under one tree name would collide as filter keys.
            while (roots.containsKey(key)) key = key + "'";
            roots.put(key, path);
        }
        return roots;
    }

    /** The segment just above the employee folder: {@code .../01_Contracts-Employment/Ali X} → {@code 01_Contracts-Employment}. */
    private static String treeKey(String root) {
        String[] segs = root.split("/");
        return segs.length >= 2 ? segs[segs.length - 2] : root;
    }

    private static boolean samePath(String a, String b) {
        String[] x = a.split("/");
        String[] y = b.split("/");
        if (x.length != y.length) return false;
        for (int i = 0; i < x.length; i++) {
            if (!SharePointPaths.sameSegment(x[i], y[i])) return false;
        }
        return true;
    }

    private static String categoryOf(String folderPath) {
        if (folderPath == null || folderPath.isEmpty()) return null;
        int slash = folderPath.indexOf('/');
        return slash < 0 ? folderPath : folderPath.substring(0, slash);
    }

    private static Optional<Entry> findEntry(History h, String itemId) {
        if (itemId == null) return Optional.empty();
        return h.items().stream().filter(e -> itemId.equals(e.id())).findFirst();
    }

    /** Same rule as {@code EmployeeDocumentService.parseDocIdPrefix}: {@code 42_contrat.pdf} → 42. */
    private static Long parseDocIdPrefix(String name) {
        if (name == null) return null;
        int underscore = name.indexOf('_');
        if (underscore <= 0) return null;
        try {
            return Long.parseLong(name.substring(0, underscore));
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    private EmployeeProfile requireProfile(Long profileId) {
        return profileRepository.findById(profileId).orElseThrow(() ->
                new AppException(ErrorCode.EMPLOYEE_NOT_FOUND, "Profil introuvable: id=" + profileId));
    }
}
