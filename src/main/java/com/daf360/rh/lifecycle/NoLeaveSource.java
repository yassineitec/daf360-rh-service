package com.daf360.rh.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * « Personne n'est en congé », en attendant l'intégration Timesheet.
 *
 * <p>{@link ConditionalOnMissingBean} : le jour où une vraie source apparaît, elle prend la
 * place sans qu'on ait à retirer celle-ci ni à toucher au planificateur. Rien d'autre à
 * faire que d'écrire la classe.
 *
 * <p>Répond un ensemble VIDE, et non null : l'appelant ne doit pas avoir à distinguer
 * « aucun congé » de « pas de source de congés ». La différence est journalisée une fois au
 * démarrage, là où elle sert — savoir pourquoi personne ne passe jamais en ON_LEAVE.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "timesheetLeaveSource")
public class NoLeaveSource implements LeaveSource {

    public NoLeaveSource() {
        log.info("LeaveSource: aucune source de conges branchee — le passage automatique en "
               + "ON_LEAVE reste inactif jusqu'a l'integration Timesheet. Le changement "
               + "manuel depuis la fiche employe fonctionne normalement.");
    }

    @Override
    public Set<Long> usersOnLeave(Set<Long> userIds, LocalDate date) {
        return Set.of();
    }
}
