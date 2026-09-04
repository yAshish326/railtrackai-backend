package com.railtrack.train.service.impl;

import com.railtrack.common.dto.RailRadarResponse;
import com.railtrack.history.service.SearchHistoryService;
import com.railtrack.train.client.RailRadarClient;
import com.railtrack.train.dto.response.JourneyResponse;
import com.railtrack.train.dto.response.TrainSummaryResponse;
import com.railtrack.train.mapper.RailRadarMapper;
import com.railtrack.train.service.TrainSearchHistoryService;
import com.railtrack.train.util.TrainRecommendationUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainServiceImplTest {

    private final RailRadarClient client = mock(RailRadarClient.class);
    private final RailRadarMapper mapper = mock(RailRadarMapper.class);
    private final TrainServiceImpl service = new TrainServiceImpl(
            client, mock(TrainRecommendationUtil.class), mock(SearchHistoryService.class),
            mock(TrainSearchHistoryService.class), mapper);

    @Test
    void betweenStationsReturnsOnlyUniqueTrainsAndSynchronizesTheCountAfterDateFiltering() {
        LocalDate journeyDate = LocalDate.of(2026, 9, 2); // Wednesday
        JourneyResponse mappedResponse = JourneyResponse.builder()
                .source("Bhubaneswar")
                .destination("Durg")
                .totalTrains(3)
                .trains(List.of(
                        train("18425", List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")),
                        train("18425", List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")),
                        train("18518", List.of("wed")),
                        train("12834", List.of("thu"))))
                .build();
        RailRadarResponse upstreamResponse = new RailRadarResponse(true, null, null);
        when(client.betweenStations(any(), any(), any(), any(Boolean.class), any(Boolean.class), any(), any()))
                .thenReturn(upstreamResponse);
        when(mapper.mapBetweenStations(upstreamResponse, "BBS", "DURG")).thenReturn(mappedResponse);

        JourneyResponse response = service.betweenStations("BBS", "DURG", journeyDate,
                false, false, null, null, null, null);

        assertEquals(2, response.getTotalTrains());
        assertEquals(List.of("18425", "18518"),
                response.getTrains().stream().map(TrainSummaryResponse::getTrainNumber).toList());
    }

    @Test
    void betweenStationsMatchesTheOriginRunDayUsingTheRequestedFromStationDayOffset() {
        LocalDate saturday = LocalDate.of(2026, 9, 5);
        JourneyResponse mappedResponse = JourneyResponse.builder()
                .source("from")
                .destination("to")
                .trains(List.of(
                        train("late-origin", List.of("fri"), 2, 2, 14, 203),
                        train("same-day-origin", List.of("fri"), 1, 1, 14, 203),
                        train("reverse-leg", List.of("fri"), 2, 2, 203, 14),
                        train("late-origin", List.of("fri"), 2, 2, 14, 219)))
                .build();
        RailRadarResponse upstreamResponse = new RailRadarResponse(true, null, null);
        when(client.betweenStations(any(), any(), any(), any(Boolean.class), any(Boolean.class), any(), any()))
                .thenReturn(upstreamResponse);
        when(mapper.mapBetweenStations(upstreamResponse, "FROM", "TO")).thenReturn(mappedResponse);

        JourneyResponse response = service.betweenStations("FROM", "TO", saturday,
                false, false, null, null, null, null);

        assertEquals(1, response.getTotalTrains());
        assertEquals("late-origin", response.getTrains().getFirst().getTrainNumber());
    }

    private TrainSummaryResponse train(String trainNumber, List<String> runningDays) {
        return TrainSummaryResponse.builder()
                .trainNumber(trainNumber)
                .runningDays(runningDays)
                .build();
    }

    private TrainSummaryResponse train(String trainNumber, List<String> runningDays,
                                       int departureDay, int arrivalDay,
                                       int departureSequence, int arrivalSequence) {
        return TrainSummaryResponse.builder()
                .trainNumber(trainNumber)
                .runningDays(runningDays)
                .departureDayNumber(departureDay)
                .arrivalDayNumber(arrivalDay)
                .departureSequence(departureSequence)
                .arrivalSequence(arrivalSequence)
                .build();
    }
}
