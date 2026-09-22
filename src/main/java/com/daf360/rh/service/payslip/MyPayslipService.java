package com.daf360.rh.service.payslip;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.payslip.MyPayslipDto;
import com.daf360.rh.dto.payslip.MyPayslipHistoryDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.SharePointPaths;
import com.daf360.rh.service.sharepoint.SharePointResolver;
import com.daf360.rh.service.sharepoint.SharePointStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The employee's own payslips, read back out of SharePoint — the read side of
 * {@link PayslipBatchService}, which is what put them there.
 *
 * <p><b>Every path is recomputed from the caller's user id.</b> Nothing in a request names a
 * folder, a profile or a path: the year and the file name are the only client-supplied values,
 * and both are checked against a fresh listing of the caller's OWN resolved folder before a
 * single byte is fetched. That is the whole security model, and it is deliberately not
 * "validate the path the client sent" — on a payslip, the cost of getting it wrong is showing
 * one employee another person's salary.
 *
 * <p>No permission is required, matching {@code MissionController}'s {@code /my} endpoints:
 * reading your own payslip is not a privilege. The authorisation that matters is the identity
 * the folder is derived from, not an authority on the method.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyPayslipService {

    /**
     * A file name the download endpoint will accept: one plain segment ending in .pdf.
     *
     * <p>Separators, {@code ..} and control characters are what a traversal looks like here,
     * because the name is appended to a folder path that reaches HR's whole document tree.
     * Membership in the real listing is checked as well — this pattern only keeps a malformed
     * value from ever reaching Graph.
     */
    private static final Pattern SAFE_FILE_NAME =
            Pattern.compile("^[^/\\\\:*?\"<>|\\p{Cntrl}]{1,250}\\.(?i:pdf)$");

    private final EmployeeProfileRepository profileRepository;
    private final SharePointResolver resolver;
    private final GraphSharePointService graph;
    private final EmployeeFolderResolver employeeFolderResolver;

    /** One payslip's bytes, with the name to put in the Content-Disposition header. */
    public record PayslipFile(String fileName, byte[] bytes) {}

    /**
     * Every payslip filed for the caller, newest period first.
     *
     * <p>Never throws for a missing folder, an unconfigured country or an unreachable Graph:
     * those are reported as the status on an empty list, because they are states the employee
     * has to be TOLD about, not failures. See {@link MyPayslipHistoryDto}.
     *
     * <p>Cost is one Graph call per year folder plus one to enumerate them. Acceptable for a
     * page one person opens about themselves; it would not be for a list view over everybody,
     * which is why {@code SharePointResolver.resolveAll} exists separately and lists no files.
     */
    @Transactional(readOnly = true)
    public MyPayslipHistoryDto history(Long userId) {
        EmployeeProfile profile = requireProfile(userId);

        SharePointResolver.ResolvedLocation location =
                resolver.resolve(profile.getId(), DocKind.PAYSLIP);
        if (!location.isFound()) {
            return MyPayslipHistoryDto.empty(location.status());
        }

        String fullName = employeeFolderResolver.fullNameOf(profile.getUserId());
        List<MyPayslipDto> items = new ArrayList<>();

        // Null limit: the whole history. An employee asking for their payslips wants the year
        // they need, which is not always the most recent one, and the count is bounded by how
        // long they have worked here.
        for (String yearFolder : resolver.years(location, null)) {
            Integer year = parseYear(yearFolder);
            if (year == null) {
                // A folder that is not a year sitting among the year folders — the live tree has
                // junk like this ("OLD"). Skipped rather than guessed at, and logged once so it
                // can be cleaned up.
                log.info("Dossier non annuel ignore sous {} : {}", location.basePath(), yearFolder);
                continue;
            }
            for (GraphSharePointService.RemoteFile file : graph.listFiles(folderFor(location, yearFolder))) {
                if (!isPdf(file.name())) continue;
                warnIfMisfiled(file.name(), fullName, profile.getId());
                PaySlipFileName.Period period = PaySlipFileName.parse(file.name(), year);
                items.add(new MyPayslipDto(
                        file.name(),
                        period.year() == null ? year : period.year(),
                        period.month(),
                        period.followsConvention(),
                        file.sizeBytes(),
                        file.lastModified()));
            }
        }

        // Newest period first, then by name so two files of the same month keep a stable order
        // rather than following whatever order Graph happened to return them in. A null month
        // (an unparseable name) sorts last within its year: it is the least identifiable row.
        Comparator<MyPayslipDto> byPeriod = Comparator
                .comparingInt((MyPayslipDto d) -> d.year()).reversed()
                .thenComparing(Comparator.comparingInt(
                        (MyPayslipDto d) -> d.month() == null ? 0 : d.month()).reversed())
                .thenComparing(MyPayslipDto::fileName);
        items.sort(byPeriod);

        return new MyPayslipHistoryDto(SharePointStatus.FOUND, items);
    }

    /**
     * One payslip's bytes, for the caller only.
     *
     * <p>{@code fileName} is a HANDLE, not a path: the folder is re-resolved from
     * {@code userId}, the year folder is listed again, and the name must be one of the entries
     * that listing returned. So a caller can only ever reach a file that is, right now, in
     * their own payslip folder — no amount of creativity in the parameter changes which folder
     * is looked at.
     *
     * @throws AppException NOT_FOUND when the folder does not resolve, the name is malformed,
     *                      or no such file is filed for this employee. One code for all three
     *                      on purpose: distinct errors here would answer "does this file
     *                      exist?" for names the caller may not read.
     */
    @Transactional(readOnly = true)
    public PayslipFile download(Long userId, int year, String fileName) {
        EmployeeProfile profile = requireProfile(userId);

        if (fileName == null || !SAFE_FILE_NAME.matcher(fileName).matches()) {
            log.warn("Telechargement de bulletin refuse pour le profil {} : nom de fichier invalide ({})",
                    profile.getId(), fileName);
            throw new AppException(ErrorCode.NOT_FOUND, "Bulletin de paie introuvable.");
        }

        SharePointResolver.ResolvedLocation location =
                resolver.resolve(profile.getId(), DocKind.PAYSLIP);
        if (!location.isFound()) {
            log.info("Telechargement de bulletin impossible pour le profil {} : {} ({})",
                    profile.getId(), location.status(), location.detail());
            throw new AppException(ErrorCode.NOT_FOUND, "Bulletin de paie introuvable.");
        }

        String folder = folderFor(location, String.valueOf(year));

        // The name is matched against what is REALLY in the folder, and the match's own spelling
        // is what gets downloaded. Two reasons: it proves the file is filed for this employee,
        // and it survives the accent/spacing drift the live tree has (see SharePointPaths.fold).
        Optional<String> actual = matchInFolder(graph.listFiles(folder).stream()
                .map(GraphSharePointService.RemoteFile::name)
                .toList(), fileName);
        if (actual.isEmpty()) {
            log.info("Bulletin {} absent du dossier {} pour le profil {}",
                    fileName, folder, profile.getId());
            throw new AppException(ErrorCode.NOT_FOUND, "Bulletin de paie introuvable.");
        }

        warnIfMisfiled(actual.get(), employeeFolderResolver.fullNameOf(profile.getUserId()),
                profile.getId());

        byte[] bytes = graph.downloadFile(folder + "/" + actual.get())
                .orElseThrow(() -> {
                    // Listed a moment ago and unreadable now: a Graph failure, not a missing file.
                    log.warn("Echec de lecture du bulletin {} dans {} pour le profil {}",
                            actual.get(), folder, profile.getId());
                    return new AppException(ErrorCode.NOT_FOUND, "Bulletin de paie introuvable.");
                });

        return new PayslipFile(actual.get(), bytes);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private EmployeeProfile requireProfile(Long userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND, "Utilisateur non identifie.");
        }
        return profileRepository.findByUserId(userId).orElseThrow(() -> new AppException(
                ErrorCode.EMPLOYEE_NOT_FOUND,
                "Aucun profil employe pour l'utilisateur " + userId + "."));
    }

    /**
     * The real name of the requested file among a folder's contents.
     *
     * <p>Exact match first. Only if nothing matches literally does it fold case, spacing and
     * accents ({@link SharePointPaths#sameSegment}) — and then only when EXACTLY ONE entry
     * matches, because "Kods_CHÉRIF_Mai_2026.pdf" and "Kods_CHERIF_Mai_2026.pdf" fold to the
     * same form and picking between two real files by coin toss would serve the wrong month
     * with no way to tell from the outside. Same rule the folder matcher in
     * {@code GraphSharePointService} applies one level up.
     */
    private Optional<String> matchInFolder(List<String> names, String fileName) {
        if (names.contains(fileName)) return Optional.of(fileName);
        List<String> folded = names.stream()
                .filter(name -> SharePointPaths.sameSegment(name, fileName))
                .toList();
        if (folded.size() == 1) return Optional.of(folded.get(0));
        if (folded.size() > 1) {
            log.warn("Nom de bulletin '{}' ambigu ({} correspondances : {}) — aucun choisi",
                    fileName, folded.size(), String.join(", ", folded));
        }
        return Optional.empty();
    }

    /** The resolved path with {@code {year}} substituted — PAYSLIP is year-scoped. */
    private String folderFor(SharePointResolver.ResolvedLocation location, String yearFolder) {
        return location.path().replace(SharePointPaths.YEAR_TOKEN, yearFolder);
    }

    private Integer parseYear(String folderName) {
        String squashed = SharePointPaths.squash(folderName);
        return squashed.matches("(19|20)\\d{2}") ? Integer.valueOf(squashed) : null;
    }

    private boolean isPdf(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
    }

    /**
     * Logs a payslip whose name does not start with the employee it is filed under.
     *
     * <p>Logged, NOT refused. The file is in this employee's own folder — which is the
     * authorisation — so hiding it would only mean an employee cannot see a payslip that HR
     * did put there, e.g. one named "bulletin final (2).pdf". What this catches is the opposite
     * mistake, HR filing someone else's slip in this folder, which no path check can see and
     * which is the one worth a human looking at.
     */
    private void warnIfMisfiled(String fileName, String employeeFullName, Long profileId) {
        if (employeeFullName != null && !employeeFullName.isBlank()
                && !PaySlipFileName.namesEmployee(fileName, employeeFullName)) {
            log.warn("Bulletin '{}' dans le dossier du profil {} ne porte pas le nom '{}' — " +
                     "a verifier (classement potentiellement errone)",
                    fileName, profileId, employeeFullName);
        }
    }
}
