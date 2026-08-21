package com.daf360.rh.sharepoint;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GraphSharePointServiceTest {

    @Mock AppProperties appProperties;
    @InjectMocks GraphSharePointService service;

    @Test
    void downloadFile_returnsEmptyWhenSharePointNotConfigured() {
        // appProperties is an unstubbed mock — every getMsGraph*() getter defaults to
        // null, so isConfigured() is false without needing to stub anything, and the
        // method must return before ever touching RestClient/the network.
        assertThat(service.downloadFile("Tunisia/01_HR/01_Contracts-Employment/Jean DUPONT/Identity Documents/Photo.jpg"))
                .isEmpty();

        // Prove the guard itself short-circuited — an empty Optional alone doesn't
        // rule out a broken/removed isConfigured() check, since a real (failing)
        // network call further downstream would be swallowed by downloadFile's own
        // generic catch(Exception) and also produce Optional.empty().
        //
        // isConfigured() short-circuits on the (null/blank) tenant id, so
        // getMsGraphClientId() is only ever read once getAccessToken() itself runs —
        // and getAccessToken() reads it synchronously, before issuing any HTTP call.
        // So never() here proves getAccessToken() (and therefore getSiteId(), which
        // only runs after it returns) was never entered — independent of whatever
        // that doomed network call would have done.
        verify(appProperties, never()).getMsGraphClientId();

        // getSharepointSiteHostname() is only ever read inside getSiteId(); kept as
        // an additional (if weaker on its own) guard against that method running.
        verify(appProperties, never()).getSharepointSiteHostname();
    }

    @Test
    void deleteFileIfExists_doesNothingWhenSharePointNotConfigured() {
        // Same precedent as downloadFile_returnsEmptyWhenSharePointNotConfigured above:
        // appProperties is an unstubbed mock, so isConfigured() is false and the method
        // must return before ever touching RestClient/the network — no exception, no
        // return value to check (void), so the proof is entirely in what was NOT called.
        service.deleteFileIfExists("Tunisia/01_HR/01_Contracts-Employment/Jean DUPONT/Identity Documents/Photo.jpg");

        // Prove the guard itself short-circuited, exactly as for downloadFile above.
        verify(appProperties, never()).getMsGraphClientId();
        verify(appProperties, never()).getSharepointSiteHostname();
    }
}
