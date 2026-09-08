package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.Mission;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.domain.enums.MissionStatus;
import com.daf360.rh.dto.profile.LifecycleTransitionDto;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.MissionRepository;
import com.daf360.rh.service.EmployeeProfileService;
import com.daf360.rh.service.PaysTimezoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fait suivre au statut de l'employé ce que la mission dit déjà.
 *
 * POURQUOI CETTE TÂCHE EXISTE
 * -----------------------------------------------------------------------------
 * `ON_MISSION` et `ON_LEAVE` figuraient dans la machine à états, dans la liste déroulante
 * de changement de statut et dans les couleurs de badge — mais AUCUN code ne les écrivait.
 * Deux statuts inatteignables, sauf à ce qu'un RH les pose à la main le bon matin puis les
 * retire le bon soir. Les ordres de mission portent pourtant déjà tout ce qu'il faut :
 * `start_date`, `end_date`, et un statut `APPROVED` qui dit que le déplacement est acté.
 *
 * CE QU'ELLE FAIT, ET RIEN DE PLUS
 * -----------------------------------------------------------------------------
 *   ACTIVE     + une mission approuvée couvrant aujourd'hui  → ON_MISSION
 *   ON_MISSION + plus aucune mission approuvée couvrant aujourd'hui → ACTIVE
 *   ACTIVE     + congé aujourd'hui (voir {@link LeaveSource}) → ON_LEAVE
 *   ON_LEAVE   + plus de congé aujourd'hui                    → ACTIVE
 *
 * ET CE QU'ELLE NE TOUCHE JAMAIS
 * -----------------------------------------------------------------------------
 * `PRE_ONBOARDING`, `OFFBOARDING`, `TERMINATED`, `ARCHIVED`. Ces quatre états sont le
 * résultat d'une décision humaine et d'un dossier ouvert quelque part ; une tâche
 * planifiée n'a pas à en sortir quelqu'un parce qu'une date est passée. Un employé en
 * cours d'offboarding qui part en mission reste en OFFBOARDING.
 *
 * La mission a priorité sur le congé : les deux sont improbables ensemble, et un ordre de
 * mission approuvé est un acte plus fort qu'une absence. Comme la machine à états
 * n'autorise pas ON_LEAVE → ON_MISSION directement, la passe fait l'escale par ACTIVE.
 *
 * CHAQUE JOUR EST CELUI DE L'EMPLOYÉ
 * -----------------------------------------------------------------------------
 * Les dates de mission sont des `LocalDate`, et « aujourd'hui » n'est pas le même instant
 * partout : à 23 h à Tunis il est déjà demain à Dubaï. On résout donc le jour PAR ENTITÉ
 * via {@link PaysTimezoneService}, comme le fait le planificateur de présence du pointage.
 * Sans ça une mission démarre un jour trop tôt ou trop tard selon le pays, et personne ne
 * comprend pourquoi.
 *
 * TOUT PASSE PAR LA MÊME PORTE QUE LE CHANGEMENT MANUEL
 * -----------------------------------------------------------------------------
 * {@code transitionLifecycleByActor} — donc la machine à états valide la transition et
 * l'audit garde une trace, avec un motif reconnaissable (`AUTO_MISSION_*`). Un changement
 * de statut automatique sans trace est la première chose dont le RH viendra demander la
 * raison.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceStatusJob {

    /** Les états que cette tâche s'autorise à quitter. Tout le reste est décidé par un humain. */
    private static final Set<LifecycleStatus> MOVABLE =
            Set.of(LifecycleStatus.ACTIVE, LifecycleStatus.ON_MISSION, LifecycleStatus.ON_LEAVE);

    private final EmployeeProfileRepository profileRepository;
    private final MissionRepository         missionRepository;
    private final EmployeeProfileService    profileService;
    private final PaysTimezoneService       timezoneService;
    private final LeaveSource               leaveSource;
    private final JdbcTemplate              jdbc;

    /**
     * 06:00, avant l'ouverture des bureaux et avant la tâche d'alertes de 08:00 : le statut
     * doit être juste au moment où quelqu'un ouvre la liste, pas deux heures après.
     */
    @Scheduled(cron = "0 0 6 * * ?")
    public void syncPresenceStatuses() {
        List<Long> paysIds = jdbc.queryForList(
                "SELECT DISTINCT pays_id FROM [dbo].[employee_profiles] "
                + "WHERE deleted = 0 AND pays_id IS NOT NULL", Long.class);

        int toMission = 0, toLeave = 0, backToActive = 0, refused = 0;

        for (Long paysId : paysIds) {
            // Le jour de CETTE entité. Sans fuseau configuré, on prend le jour du serveur :
            // l'automatisation reste utile, et le décalage possible d'un jour vaut mieux que
            // de ne rien faire pour toute une entité (c'est l'arbitrage inverse du pointage,
            // où agir à la mauvaise heure changeait un statut sous les yeux de l'employé).
            var zone  = timezoneService.zoneFor(paysId);
            LocalDate today = zone != null ? LocalDate.now(zone) : LocalDate.now();

            List<EmployeeProfile> profiles = profileRepository.findByPaysIdAndDeletedFalse(paysId)
                    .stream()
                    .filter(p -> p.getLifecycleStatus() != null && MOVABLE.contains(p.getLifecycleStatus()))
                    .toList();
            if (profiles.isEmpty()) continue;

            Set<Long> userIds = profiles.stream()
                    .map(EmployeeProfile::getUserId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());

            Set<Long> onMission = usersOnMission(userIds, today);
            Set<Long> onLeave   = new HashSet<>(leaveSource.usersOnLeave(userIds, today));
            // La mission gagne : on ne demande pas au congé son avis sur quelqu'un dont le
            // déplacement est approuvé.
            onLeave.removeAll(onMission);

            for (EmployeeProfile p : profiles) {
                LifecycleStatus current = p.getLifecycleStatus();
                LifecycleStatus target  = onMission.contains(p.getUserId()) ? LifecycleStatus.ON_MISSION
                                        : onLeave.contains(p.getUserId())   ? LifecycleStatus.ON_LEAVE
                                        : LifecycleStatus.ACTIVE;
                if (target == current) continue;

                String reason = switch (target) {
                    case ON_MISSION -> "AUTO_MISSION_START";
                    case ON_LEAVE   -> "AUTO_LEAVE_START";
                    default         -> current == LifecycleStatus.ON_MISSION
                                        ? "AUTO_MISSION_END" : "AUTO_LEAVE_END";
                };

                try {
                    // ON_LEAVE → ON_MISSION n'existe pas dans la machine à états, et c'est
                    // volontaire : on ne passe pas d'une absence à un déplacement sans
                    // repasser par « présent ». Le cas se produit dès qu'un RH a posé
                    // ON_LEAVE à la main et qu'une mission approuvée démarre. On fait donc
                    // l'escale explicitement — les deux écritures sont auditées, ce qui
                    // raconte la vraie séquence — plutôt que de laisser la transition être
                    // refusée et le statut mentir toute la durée de la mission.
                    if (!current.canTransitionTo(target)) {
                        LifecycleTransitionDto back = new LifecycleTransitionDto();
                        back.setNewStatus(LifecycleStatus.ACTIVE);
                        back.setReason(current == LifecycleStatus.ON_MISSION
                                ? "AUTO_MISSION_END" : "AUTO_LEAVE_END");
                        profileService.transitionLifecycleByActor(p.getId(), back, null);
                    }

                    LifecycleTransitionDto dto = new LifecycleTransitionDto();
                    dto.setNewStatus(target);
                    dto.setReason(reason);
                    profileService.transitionLifecycleByActor(p.getId(), dto, null);

                    if (target == LifecycleStatus.ON_MISSION)   toMission++;
                    else if (target == LifecycleStatus.ON_LEAVE) toLeave++;
                    else                                         backToActive++;

                    log.info("Presence: profil {} (user {}) {} → {} [{}]",
                            p.getId(), p.getUserId(), current, target, reason);
                } catch (Exception e) {
                    // Une transition refusée est une information, pas un incident à taire :
                    // la machine à états a évolué, ou le profil a bougé entre la lecture et
                    // l'écriture. On note et on continue — un profil ne doit pas arrêter la
                    // passe de toute une entité.
                    refused++;
                    log.warn("Presence: transition {} → {} refusee pour le profil {} : {}",
                            current, target, p.getId(), e.getMessage());
                }
            }
        }

        log.info("Presence: {} entite(s) — {} en mission, {} en conge, {} de retour actifs, "
               + "{} refus", paysIds.size(), toMission, toLeave, backToActive, refused);
    }

    /**
     * Les utilisateurs qu'un ordre de mission APPROUVÉ couvre aujourd'hui.
     *
     * <p>Une requête pour toute l'entité, pas une par employé : la passe traite des
     * centaines de profils, et `MissionRepository` n'offre que des recherches par
     * utilisateur. D'où le JPQL ici plutôt qu'un appel en boucle.
     */
    private Set<Long> usersOnMission(Set<Long> userIds, LocalDate today) {
        if (userIds.isEmpty()) return Set.of();
        List<Mission> covering = missionRepository.findApprovedCovering(
                userIds, MissionStatus.APPROVED, today);
        return covering.stream().map(Mission::getEmployeeUserId).collect(Collectors.toSet());
    }
}
