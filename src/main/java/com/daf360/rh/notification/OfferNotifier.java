package com.daf360.rh.notification;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateEmailTemplate;
import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.dto.email.CandidateEmailDtos.EmailPreviewDto;
import com.daf360.rh.service.CandidateEmailTemplateService;
import com.daf360.rh.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who hears about an offer round (V133 — two-way negotiation):
 *
 * <ul>
 *   <li>the CANDIDATE, by mail, when RH sends an approved round — RH's own or the DAF's
 *       counter-proposal — after previewing (and possibly editing) the email built from the
 *       entity's template (V134);</li>
 *   <li>the RECRUITER (the round's author), in-app, when the DAF answers: approved, refused,
 *       counter-proposed, or — on a candidate's counter — agreed, which ends the offer step.</li>
 * </ul>
 *
 * <p>Everything is sent after the business change commits: a rolled-back decision must not
 * have mailed anyone. Never throws.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfferNotifier {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern CURRENCY = Pattern.compile("\"localCurrency\"\\s*:\\s*\"([A-Za-z]{3})\"");

    private final MailService                   mailService;
    private final InAppNotifier                 notifier;
    private final CandidateEmailTemplateService templates;

    /** Where the recruiter lands: the candidate, on the Offre tab. */
    public static String offerLink(Long candidateId) {
        return "/rh/candidates/" + candidateId + "?tab=offre";
    }

    /** The currency the engine costed in, read from a stored simulation snapshot; null if absent. */
    public static String currencyOf(String simulationSnapshot) {
        if (simulationSnapshot == null) return null;
        Matcher m = CURRENCY.matcher(simulationSnapshot);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    // ── Candidate ────────────────────────────────────────────────────────────

    /**
     * The offer email as it would leave now (V134): the entity's template for this round —
     * OFFER for the first one, OFFER_REVISED for any later one — rendered for the candidate.
     * What RH sees, and may edit, before confirming the send.
     */
    public EmailPreviewDto preview(Candidate candidate, JobOffer round, String currency) {
        String code = round.getRoundNumber() != null && round.getRoundNumber() > 1
                ? CandidateEmailTemplate.CODE_OFFER_REVISED : CandidateEmailTemplate.CODE_OFFER;
        String[] rendered = templates.render(code, candidate.getPaysId(), tokens(candidate, round, currency));
        return new EmailPreviewDto(candidate.getEmailPersonal(), rendered[0], rendered[1]);
    }

    /**
     * Sends the offer email, after the send commits. RH's edited subject and body win when both
     * are given; otherwise the template is rendered. A candidate with no personal address is
     * refused upstream ({@code OfferService#sendOffer}), so this only guards a race.
     */
    public void offerToCandidate(Candidate candidate, JobOffer round, String currency,
                                 String subjectOverride, String bodyOverride) {
        String to = candidate.getEmailPersonal();
        if (to == null || to.isBlank()) {
            log.warn("Offer round {} for candidate {}: no personal email, mail skipped",
                    round.getId(), candidate.getId());
            return;
        }
        String subject;
        String body;
        if (subjectOverride != null && !subjectOverride.isBlank()
                && bodyOverride != null && !bodyOverride.isBlank()) {
            subject = subjectOverride.trim();
            body    = bodyOverride;
        } else {
            EmailPreviewDto p = preview(candidate, round, currency);
            subject = p.subject();
            body    = p.body();
        }
        String html = toHtml(body);
        afterCommit(() -> mailService.sendRoutedEmail(List.of(to), List.of(), List.of(), subject, html));
    }

    private static Map<String, String> tokens(Candidate c, JobOffer r, String currency) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("candidate_first_name", nz(c.getFirstName()));
        t.put("candidate_last_name",  nz(c.getLastName()));
        t.put("position",             nz(c.getAppliedPosition()));
        t.put("salary",               r.getProposedSalary() != null ? amount(r.getProposedSalary(), null) : "");
        t.put("currency",             nz(currency));
        t.put("hire_date",            r.getExpectedHireDate() != null ? r.getExpectedHireDate().format(DAY) : "—");
        t.put("notice_days",          r.getNoticePeriodDays() != null ? String.valueOf(r.getNoticePeriodDays()) : "—");
        t.put("expiry_date",          r.getExpiryDate() != null ? r.getExpiryDate().format(DAY) : "—");
        t.put("note",                 nz(r.getSalaryNote()));
        t.put("round",                r.getRoundNumber() != null ? String.valueOf(r.getRoundNumber()) : "1");
        return t;
    }

    /** The plain-text body as a mail: escaped, line breaks kept. */
    private static String toHtml(String text) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#191c1e;line-height:1.5\">"
             + esc(text).replace("\r\n", "\n").replace("\n", "<br>")
             + "</div>";
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String amount(BigDecimal v, String currency) {
        if (v == null) return "—";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.FRANCE);
        nf.setMinimumFractionDigits(0);
        nf.setMaximumFractionDigits(3);
        return nf.format(v) + (currency != null ? " " + currency : "");
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    // ── Recruiter ────────────────────────────────────────────────────────────

    /** The DAF approved an RH round: it can be sent. */
    public void approvedToSend(Long recruiterId, Candidate c, JobOffer round) {
        toRecruiter(recruiterId, c, "Offre validée par la DAF",
                "L'offre de " + name(c) + " (tour " + round.getRoundNumber() + ") est validée. "
              + "Vous pouvez l'envoyer au candidat.");
    }

    /** The DAF refused without naming a figure: RH revises. */
    public void refused(Long recruiterId, Candidate c, JobOffer round, String notes) {
        toRecruiter(recruiterId, c, "Offre refusée par la DAF",
                "L'offre de " + name(c) + " (tour " + round.getRoundNumber() + ") est refusée"
              + (notes != null && !notes.isBlank() ? " : « " + notes + " »" : "")
              + ". Révisez-la depuis l'onglet Offre.");
    }

    /** The DAF named its own figure: an approved round, waiting for RH to send it. */
    public void financeCounterReady(Long recruiterId, Candidate c, JobOffer newRound, String currency) {
        toRecruiter(recruiterId, c, "Contre-proposition de la DAF",
                "La DAF propose " + amount(newRound.getProposedSalary(), currency) + " pour " + name(c)
              + ". L'offre est validée : envoyez-la au candidat depuis la fiche (« Envoyer l'offre »).");
    }

    /** The DAF approved the candidate's own figure: both agree, the offer step is over. */
    public void agreed(Long recruiterId, Candidate c, JobOffer round, String currency) {
        toRecruiter(recruiterId, c, "Accord salarial trouvé",
                "La DAF accepte la demande de " + name(c) + " (" + amount(round.getProposedSalary(), currency)
              + "). L'offre est conclue.");
    }

    private void toRecruiter(Long recruiterId, Candidate c, String title, String message) {
        if (recruiterId == null) return;
        NotificationTarget target = NotificationTarget.of(
                NotificationEntityType.CANDIDATE, c.getId(), offerLink(c.getId()));
        afterCommit(() -> notifier.notifyUsers(List.of(recruiterId), InAppNotifier.MODULE_RH,
                title, message, target));
    }

    private static String name(Candidate c) {
        return ((c.getFirstName() != null ? c.getFirstName() : "") + " "
              + (c.getLastName() != null ? c.getLastName() : "")).trim();
    }

    private static void afterCommit(Runnable send) {
        Runnable safe = () -> {
            try { send.run(); }
            catch (Exception e) { log.warn("Offer notification failed: {}", e.getMessage()); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { safe.run(); }
            });
        } else {
            safe.run();
        }
    }
}
