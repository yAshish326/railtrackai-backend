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

            int maxWaitlist = passengers.stream().filter(this::isWaitlisted)
                    .mapToInt(this::statusNumber).max().orElse(0);
            if (passengers.stream().anyMatch(this::isWaitlisted)) {
                double chance = calculateWaitlistChance(maxWaitlist);
                return buildPnrResponse("WAITLISTED", chance, chance < 70.0, pnrData);
            }

            int maxRac = passengers.stream().filter(this::isRac)
                    .mapToInt(this::statusNumber).max().orElse(0);
            if (passengers.stream().anyMatch(this::isRac)) {
                return buildPnrResponse("RAC", calculateRacBerthChance(maxRac), false, pnrData);
            }
        }

        return buildPnrResponse("UNKNOWN", 0.0, false, pnrData);
    }

    private AiPnrResponse buildPnrResponse(String status, double chance, boolean alternativeSuggested, PnrData pnrData) {
        String recommendation = aiChatService.analyzeTrustedData(
                PromptBuilder.buildPnrAnalysisPrompt(pnrData, status, chance, alternativeSuggested));
        return new AiPnrResponse(status, chance, recommendation, alternativeSuggested);
    }

    private boolean isConfirmed(Passenger passenger) {
        String status = combinedStatus(passenger);
        return status.contains("CNF") || status.contains("CONFIRMED");
    }

    private boolean isRac(Passenger passenger) {
        return combinedStatus(passenger).contains("RAC");
    }

    private boolean isWaitlisted(Passenger passenger) {
        String status = combinedStatus(passenger);
        return status.contains("WAITLIST") || status.contains("WL");
    }

    private String combinedStatus(Passenger passenger) {
        // Booking status is historical. Once current status is available it must take precedence,
        // otherwise a ticket upgraded from WL to RAC would still look waitlisted.
        return firstAvailable(passenger.getCurrentStatusDetails(), passenger.getCurrentStatus(),
                passenger.getBookingStatusDetails(), passenger.getBookingStatus()).toUpperCase();
    }

    private String firstAvailable(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private int statusNumber(Passenger passenger) {
        String digits = combinedStatus(passenger).replaceAll("[^0-9]", " ");
        for (String part : digits.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                try {
                    return Integer.parseInt(part);
                } catch (NumberFormatException ignored) {
                    // Continue searching if a malformed number is encountered.
                }
            }
        }
        return 0;
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

    private double calculateWaitlistChance(int waitlistNumber) {
        double score = 98.0 - (waitlistNumber * 1.25);
        return Math.round(Math.max(8.5, Math.min(94.0, score)) * 10.0) / 10.0;
    }

    private double calculateRacBerthChance(int racNumber) {
        double score = 97.0 - (racNumber * 0.75);
        return Math.round(Math.max(40.0, Math.min(96.0, score)) * 10.0) / 10.0;
    }
}
