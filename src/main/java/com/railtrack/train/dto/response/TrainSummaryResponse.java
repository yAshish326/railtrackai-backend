package com.railtrack.train.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Business-level summary of a single train, used by between-stations search results. */
@Data
@Builder
public class TrainSummaryResponse {

    private String trainNumber;

    private String trainName;

    private String trainType;

    private Station source;

    private Station destination;

    private String departure;

    private String arrival;

    private String duration;

    private Double distanceKm;

    private List<String> runningDays;

    private List<String> availableClasses;

    /**
     * One-based service-day positions supplied by RailRadar for the requested
     * legs. They are internal matching metadata, not part of the API response.
     */
    @JsonIgnore
    private Integer departureDayNumber;

    @JsonIgnore
    private Integer arrivalDayNumber;

    @JsonIgnore
    private Integer departureSequence;

    @JsonIgnore
    private Integer arrivalSequence;
}
