package com.railtrack.ai.service.impl;

import com.railtrack.ai.dto.AiPnrResponse;
import com.railtrack.ai.service.AiChatService;
import com.railtrack.pnr.dto.response.Passenger;
import com.railtrack.pnr.dto.response.PnrData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceImplTest {

    private final AiChatService aiChatService = mock(AiChatService.class);
    private final AiServiceImpl service = new AiServiceImpl(aiChatService);

    @Test
    void classifiesRacFromCurrentStatusDetailsAndExplainsItInPrompt() {
        when(aiChatService.analyzeTrustedData(anyString())).thenReturn("RAC advice");
        Passenger passenger = new Passenger(1, "WL 20", null, "WL 20", null, null, null, "RAC 13");

        AiPnrResponse response = service.analyzePnrStatus(pnrWith(passenger));

        assertEquals("RAC", response.getCurrentStatus());
        assertFalse(response.isAlternativeSuggested());
        verify(aiChatService).analyzeTrustedData(contains("Passenger 1: RAC 13"));
    }

    @Test
    void classifiesWaitlistFromCurrentStatusDetailsAndSuggestsAlternativeWhenLow() {
        when(aiChatService.analyzeTrustedData(anyString())).thenReturn("Waitlist advice");
        Passenger passenger = new Passenger(1, "WL 100", null, "WL 100", null, null, null, "GNWL 50");

        AiPnrResponse response = service.analyzePnrStatus(pnrWith(passenger));

        assertEquals("WAITLISTED", response.getCurrentStatus());
        assertTrue(response.isAlternativeSuggested());
    }

    private PnrData pnrWith(Passenger passenger) {
        return new PnrData("1234567890", "12724", "TELANGANA EXP", "MTJ", "NGP", null,
                "3A", "30 Aug 2026", "Chart not prepared", null, null, null, List.of(passenger));
    }
}
