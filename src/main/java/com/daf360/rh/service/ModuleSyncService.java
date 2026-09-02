package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes an account change to the consuming modules straight away, instead of waiting for
 * their 15-minute pull.
 *
 * POURQUOI
 * -----------------------------------------------------------------------------
 * Finance et la paie tiennent chacune une copie de `Users` (`users_ref`), rafraichie par un
 * `@Scheduled` toutes les 15 minutes. C'est tres bien pour un changement de nom ; c'est
 * inacceptable pour une desactivation urgente — un compte a couper reste selectionnable
 * pendant un quart d'heure dans chaque module.
 *
 * CE SERVICE NE REMPLACE PAS LE PULL
 * -----------------------------------------------------------------------------
 * Il l'accelere. Le `@Scheduled` de chaque consommateur reste le garant de la coherence :
 * si cet appel echoue — module arrete, reseau, 403 — la copie se remet a jour au prochain
 * passage, au pire 15 minutes plus tard. C'est la raison pour laquelle un echec ici est
 * rapporte a l'appelant SANS faire echouer le changement RH lui-meme : la modification est
 * deja enregistree et sera propagee de toute facon.
 *
 * Remplacer le pull par ce push serait un retour en arriere deguise en amelioration : un
 * appel perdu deviendrait une divergence permanente et silencieuse — exactement la panne
 * qui a laisse 97 comptes egyptiens figes pendant dix semaines.
 *
 * LE JETON DE L'APPELANT EST TRANSMIS
 * -----------------------------------------------------------------------------
 * Plutot qu'un secret partage : le declenchement est un geste d'administrateur, et chaque
 * module doit pouvoir appliquer ses propres regles d'acces. Consequence a connaitre — le
 * point d'entree de la paie exige SUPER_ADMIN, donc un administrateur RH qui ne l'a pas
 * verra « paie : 403 ». C'est signale explicitement plutot que masque.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleSyncService {

    /**
     * Court volontairement : ce service est appele depuis un ecran, en synchrone. Un module
     * qui ne repond pas en 8 s ne doit pas laisser l'administrateur devant un bouton qui
     * tourne — le pull rattrapera.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final AppProperties appProperties;

    /**
     * @param bearerToken l'en-tete Authorization de l'appelant, transmis tel quel. Peut etre
     *                    nul (appel interne) : les modules qui exigent un jeton repondront
     *                    401 et ce sera rapporte comme tel.
     */
    public List<ModuleSyncResult> propagateUsers(String bearerToken) {
        List<ModuleSyncResult> results = new ArrayList<>();
        results.add(call("finance", appProperties.getFactApiBaseUrl(),
                         "/api/fact/ref/users/sync", bearerToken));
        results.add(call("paie", appProperties.getPayrollApiBaseUrl(),
                         "/api/payroll/ref/sync", bearerToken));
        return results;
    }

    private ModuleSyncResult call(String module, String baseUrl, String path, String bearerToken) {
        if (baseUrl == null || baseUrl.isBlank()) {
            // Non configure = module absent de ce deploiement. Ce n'est pas une erreur, et
            // le dire evite de faire passer une absence voulue pour une panne.
            return new ModuleSyncResult(module, false, true, "module non configure", 0);
        }

        String url = baseUrl + path;
        long started = System.currentTimeMillis();
        try {
            // Les delais sont poses sur la fabrique de requetes, pas seulement declares :
            // sans cela un module qui accepte la connexion puis ne repond jamais bloque le
            // bouton de l'administrateur indefiniment.
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(TIMEOUT);
            factory.setReadTimeout(TIMEOUT);
            RestClient client = RestClient.builder().requestFactory(factory).build();

            client.post()
                  .uri(url)
                  .headers(h -> {
                      if (bearerToken != null && !bearerToken.isBlank()) {
                          h.set("Authorization", bearerToken);
                      }
                  })
                  .retrieve()
                  .toBodilessEntity();

            long ms = System.currentTimeMillis() - started;
            log.info("ModuleSync: {} resynchronise en {} ms ({})", module, ms, url);
            return new ModuleSyncResult(module, true, false, "OK", ms);

        } catch (org.springframework.web.client.RestClientResponseException e) {
            long ms = System.currentTimeMillis() - started;
            HttpStatusCode status = e.getStatusCode();
            log.warn("ModuleSync: {} a repondu {} sur {} — le pull de 15 min rattrapera",
                     module, status.value(), url);
            return new ModuleSyncResult(module, false, false,
                    "HTTP " + status.value(), ms);

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - started;
            log.warn("ModuleSync: {} injoignable sur {} ({}) — le pull de 15 min rattrapera",
                     module, url, e.getClass().getSimpleName());
            return new ModuleSyncResult(module, false, false,
                    "injoignable : " + e.getClass().getSimpleName(), ms);
        }
    }

    /**
     * @param skipped vrai quand le module n'est simplement pas configure — a distinguer d'un
     *                echec, sinon l'ecran affiche une panne la ou il n'y a rien a joindre.
     */
    public record ModuleSyncResult(String module, boolean ok, boolean skipped,
                                   String message, long durationMs) {}
}
