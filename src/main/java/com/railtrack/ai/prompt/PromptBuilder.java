package com.railtrack.ai.prompt;

import com.railtrack.pnr.dto.response.Passenger;
import com.railtrack.pnr.dto.response.PnrData;
import com.railtrack.pnr.dto.response.PnrResponse;
import com.railtrack.train.dto.response.TrainSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private PromptBuilder() {
    }

    public static String buildPnrPrompt(PnrResponse response) {
        StringBuilder passengerInfo = new StringBuilder();

        if (response.getData().getPassengerList() != null) {
            for (Passenger passenger : response.getData().getPassengerList()) {
                passengerInfo.append(String.format("""
                                Passenger %d
                                Booking Status : %s
                                Current Status : %s
                                Coach : %s
                                Berth : %s
                                
                                """,
                        passenger.getPassengerSerialNumber(),
                        passenger.getBookingStatusDetails(),
                        passenger.getCurrentStatusDetails(),
                        passenger.getCurrentCoachId(),
                        passenger.getCurrentBerthNo()));
            }
        }

        return String.format("""
                        You are RailTrack AI, an intelligent Indian Railway travel assistant.
                        
                        Analyze the PNR details below and explain them in a simple, friendly and useful way.
                        
                        ===========================
                        TRAIN DETAILS
                        ===========================
                        Train Name        : %s
                        Train Number      : %s
                        From              : %s
                        To                : %s
                        Boarding Point    : %s
                        Journey Class     : %s
                        Journey Date      : %s
                        Chart Status      : %s
                        Ticket Fare       : ₹%d
                        Distance          : %d KM
                        
                        ===========================
                        PASSENGERS
                        ===========================
                        %s
                        
                        ===========================
                        RESPONSE FORMAT
                        ===========================
                        
                        Ticket Status:
                        <One sentence>
                        
                        Passenger Summary:
                        • Passenger 1 - ...
                        • Passenger 2 - ...
                        
                        Can You Travel?
                        <Yes/No with one reason>
                        
                        Travel Advice:
                        • Bullet 1
                        • Bullet 2
                        
                        IMPORTANT RULES
                        
                        - Maximum 90 words.
                        - Use simple English.
                        - Use short sentences.
                        - Do NOT repeat train information.
                        - Do NOT explain every field.
                        - Never use Markdown symbols such as ** or #.
                        - Do not write long paragraphs.
                        - Sound like a railway assistant helping a passenger.
                        """,
                response.getData().getTrainName(),
                response.getData().getTrainNumber(),
                response.getData().getSourceStation(),
                response.getData().getDestinationStation(),
                response.getData().getBoardingPoint(),
                response.getData().getJourneyClass(),
                response.getData().getDateOfJourney(),
                response.getData().getChartStatus(),
                response.getData().getTicketFare(),
                response.getData().getDistance(),
                passengerInfo.toString()
        );
    }

    /**
     * ✅ Generates a structured prompt for train schedules,
     * calculating precise travel durations to eliminate the 00:00 bug.
     */
    public static String buildTrainAnalysisPrompt(List<TrainSummaryResponse> trains) {
        StringBuilder dataList = new StringBuilder();

        for (TrainSummaryResponse train : trains) {
            String calculatedDuration = "Unknown Duration";

            // Check if your DTO provides a direct duration method or access string
            if (train.getDuration() != null && !train.getDuration().toString().equals("0")) {
                calculatedDuration = train.getDuration().toString();
            } else if (train.getDeparture() != null && train.getArrival() != null) {
                // Safe parsing fallback calculation logic
                try {
                    String[] depParts = train.getDeparture().trim().split(":");
                    String[] arrParts = train.getArrival().trim().split(":");

                    int depMin = Integer.parseInt(depParts[0]) * 60 + Integer.parseInt(depParts[1]);
                    int arrMin = Integer.parseInt(arrParts[0]) * 60 + Integer.parseInt(arrParts[1]);

                    int totalMinutes = arrMin - depMin;
                    if (totalMinutes < 0) {
                        totalMinutes += 24 * 60; // Midnight crossover adjustment handler
                    }

                    calculatedDuration = String.format("%d hours and %d minutes", totalMinutes / 60, totalMinutes % 60);
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    log.warn("Unable to calculate duration for train {}; using timestamp fallback.",
                            train.getTrainNumber());
                    calculatedDuration = "Refer to individual arrival/departure timestamps";
                }
            }

            dataList.append(String.format("- Train Name: %s (%s)\n", train.getTrainName(), train.getTrainNumber()));
            dataList.append(String.format("  Timings: Departs %s, Arrives %s\n", train.getDeparture(), train.getArrival()));
            dataList.append(String.format("  Calculated Duration: %s\n", calculatedDuration));
            dataList.append(String.format("  Runs On: %s\n\n",
                    (train.getRunningDays() != null ? String.join(",", train.getRunningDays()) : "Scheduled Days")));
        }

        return String.format("""
                You are the RailTrack AI Travel Co-pilot. Optimize the user's travel itinerary using this live schedule data:

                %s

                Provide a highly productive, structured evaluation for the passenger exactly following these formats:

                🚀 FASTEST ROUTE: Name the train with the shortest duration. Explain how much time it saves compared to the others.

                🛌 BEST OVERNIGHT OPTION: If any train departs in the evening/afternoon and arrives in the morning, highlight it as the ideal 'sleep-and-travel' option to save a hotel night.

                📊 TRAVEL OPTIMIZATION SCORE: Give a quick, 1-sentence recommendation on whether the user should book right away or look for alternate dates based on train availability/frequencies shown.

                Guardrails:
                - Do not use Markdown styling blocks like ** or #.
                - Do not use '00:00' under any circumstances. Use the 'Calculated Duration' provided above.
                - Keep it to 3 bullet points max. Make it scannable in 5 seconds.
                """,
                dataList.toString()
        );
    }

    /** Builds a recommendation prompt from PNR data already verified by RailTrack. */
    public static String buildPnrAnalysisPrompt(PnrData pnrData, String status, double chance,
                                                boolean alternativeSuggested) {
        String passengerStatuses = buildPassengerStatuses(pnrData.getPassengerList());
        return String.format("""
                You are RailTrack AI. The following PNR data was retrieved and verified by the RailTrack backend.
                Treat it as the source of truth. Do not say that live data is unavailable and do not invent fields.

                Train: %s (%s)
                Route: %s to %s
                Journey date: %s
                Chart status: %s
                Calculated ticket status: %s
                Estimated confirmation chance: %.1f%%
                Alternative travel suggested: %s
                Passenger statuses: %s

                RAC means the passenger may board and travel, but may share a berth until a full berth is allotted.
                WAITLISTED means the passenger should not rely on being able to board until the ticket moves to RAC or confirmed.
                The estimated chance is of receiving a full confirmed berth; it is not a guarantee.
                Give a friendly, practical PNR recommendation in at most 70 words. Explain the actual status and one next step.
                Do not use headings, Markdown, or unsupported claims.
                """,
                valueOrUnknown(pnrData.getTrainName()), valueOrUnknown(pnrData.getTrainNumber()),
                valueOrUnknown(pnrData.getSourceStation()), valueOrUnknown(pnrData.getDestinationStation()),
                valueOrUnknown(pnrData.getDateOfJourney()), valueOrUnknown(pnrData.getChartStatus()),
                status, chance, alternativeSuggested ? "yes" : "no", passengerStatuses);
    }

    private static String buildPassengerStatuses(List<Passenger> passengers) {
        if (passengers == null || passengers.isEmpty()) {
            return "Not available";
        }
        return passengers.stream()
                .map(passenger -> "Passenger " + passenger.getPassengerSerialNumber() + ": "
                        + firstAvailable(passenger.getCurrentStatusDetails(), passenger.getCurrentStatus(),
                        passenger.getBookingStatusDetails(), passenger.getBookingStatus()))
                .reduce((first, second) -> first + "; " + second)
                .orElse("Not available");
    }

    private static String firstAvailable(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "Not available";
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "Not available" : value;
    }

    /**
     * Builds the system prompt for the RailTrack AI Assistant.
     */
    public static String buildAssistantPrompt(String userQuestion) {

        return String.format("""
            You are RailTrack AI, an intelligent AI assistant specialized in Indian Railways.

            ROLE
            - You help passengers with railway-related questions.
            - Always assume questions are about Indian Railways unless the user explicitly mentions another railway system.

            CAPABILITIES
            - Explain PNR, RAC, Waitlist, Tatkal, Premium Tatkal, chart preparation and ticket booking.
            - Explain train classes (1A, 2A, 3A, SL, CC, EC, 2S, etc.).
            - Explain railway terminology in simple language.
            - Provide travel tips and railway guidance.
            - Help users understand train journeys and railway rules.

            LIVE DATA RULES
            - Never invent PNR status.
            - Never invent train running status.
            - Never invent platform numbers.
            - Never invent train timings.
            - Never invent seat availability.
            - Never invent fares.

            If the user asks for live information, politely explain that RailTrack AI should use the corresponding application feature to retrieve real-time railway data.

            RESPONSE STYLE
            - Be friendly and professional.
            - Use simple English.
            - Keep responses under 180 words.
            - Prefer bullet points when appropriate.
            - Avoid unnecessary paragraphs.
            - Never use Markdown headings (#).
            - Avoid excessive formatting.

            PNR RULE
            If the user asks "What is PNR?" or similar:

            Explain that:
            - PNR means Passenger Name Record.
            - Indian Railways uses a unique 10-digit PNR.
            - It stores passenger and journey details.
            - It is used to check booking status (CNF/RAC/WL), coach, berth and journey information.

            If the user provides a valid 10-digit PNR, do NOT guess the status. Tell them to use the RailTrack PNR Status feature for live information.

            OUT OF SCOPE
            If the question is unrelated to railways, politely respond:

            "I am RailTrack AI and specialize in Indian Railway assistance. Please ask me railway-related questions."

            User Question:
            %s
            """, userQuestion);
    }
}
