package com.railtrack.ai.service.impl;

import com.railtrack.ai.dto.AiPnrResponse;
import com.railtrack.ai.dto.AiTrainRecommendationResponse;
import com.railtrack.ai.prompt.PromptBuilder;
import com.railtrack.ai.service.AiChatService;
import com.railtrack.ai.service.AiService;
import com.railtrack.pnr.dto.response.Passenger;
import com.railtrack.pnr.dto.response.PnrData;
import com.railtrack.train.dto.response.TrainSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final AiChatService aiChatService;

    public AiServiceImpl(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @Override
    public AiTrainRecommendationResponse generateTrainSuggestions(List<TrainSummaryResponse> trains) {
        if (trains == null || trains.isEmpty()) {
            return new AiTrainRecommendationResponse("No train data available to evaluate at this time.", null, null);
        }

        TrainSummaryResponse fastest = trains.get(0);
        TrainSummaryResponse longest = trains.get(0);
        for (TrainSummaryResponse train : trains) {
            if (parseDurationToMinutes(train.getDuration()) < parseDurationToMinutes(fastest.getDuration())) {
                fastest = train;
            }
            if (parseDurationToMinutes(train.getDuration()) > parseDurationToMinutes(longest.getDuration())) {
                longest = train;
            }
        }

        String insightMessage = aiChatService.analyzeTrustedData(PromptBuilder.buildTrainAnalysisPrompt(trains));
        return new AiTrainRecommendationResponse(insightMessage, fastest, longest);
    }

    @Override
    public AiPnrResponse analyzePnrStatus(PnrData pnrData) {
        if (pnrData == null) {
            return new AiPnrResponse("UNKNOWN", 0.0, "Invalid PNR details provided.", false);
        }

        List<Passenger> passengers = pnrData.getPassengerList();
        if (passengers != null && !passengers.isEmpty()) {
            boolean allConfirmed = passengers.stream().allMatch(this::isConfirmed);
            if (allConfirmed) {
                return buildPnrResponse("CONFIRMED", 100.0, false, pnrData);
            }

            int maxWaitlist = passengers.stream().mapToInt(this::waitlistNumber).max().orElse(0);
            if (maxWaitlist > 0) {
                double chance = calculateHeuristicChance(maxWaitlist);
                return buildPnrResponse("WAITLISTED", chance, chance < 70.0, pnrData);
            }
        }

        String chartStatus = pnrData.getChartStatus() == null ? "" : pnrData.getChartStatus().toUpperCase();
        if (chartStatus.contains("CHART PREPARED")) {
            return buildPnrResponse("CONFIRMED", 100.0, false, pnrData);
        }
        return buildPnrResponse("UNKNOWN", 50.0, false, pnrData);
    }

    private AiPnrResponse buildPnrResponse(String status, double chance, boolean alternativeSuggested, PnrData pnrData) {
        String recommendation = aiChatService.analyzeTrustedData(
                PromptBuilder.buildPnrAnalysisPrompt(pnrData, status, chance, alternativeSuggested));
        return new AiPnrResponse(status, chance, recommendation, alternativeSuggested);
    }

    private boolean isConfirmed(Passenger passenger) {
        String status = passenger.getCurrentStatus() == null ? "" : passenger.getCurrentStatus().toUpperCase();
        String details = passenger.getCurrentStatusDetails() == null ? "" : passenger.getCurrentStatusDetails().toUpperCase();
        return status.contains("CNF") || status.contains("CONFIRMED") || details.contains("CNF");
    }

    private int waitlistNumber(Passenger passenger) {
        String status = passenger.getCurrentStatus() != null ? passenger.getCurrentStatus() : passenger.getBookingStatus();
        if (status == null) {
            return 0;
        }
        String digits = status.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private int parseDurationToMinutes(String duration) {
        if (duration == null || duration.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            String clean = duration.toLowerCase().replaceAll("[^0-9hms: ]", "").trim();
            if (clean.contains("h")) {
                String[] parts = clean.split("h");
                int hours = Integer.parseInt(parts[0].trim());
                int minutes = parts.length > 1 && parts[1].contains("m")
                        ? Integer.parseInt(parts[1].replace("m", "").trim()) : 0;
                return hours * 60 + minutes;
            }
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
            return Integer.parseInt(clean);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            log.warn("Unable to parse train duration '{}'; treating it as unavailable.", duration);
            return Integer.MAX_VALUE;
        }
    }

    private double calculateHeuristicChance(int waitlistNumber) {
        double score = 98.0 - (waitlistNumber * 1.25);
        return Math.round(Math.max(8.5, Math.min(94.0, score)) * 10.0) / 10.0;
    }
}
