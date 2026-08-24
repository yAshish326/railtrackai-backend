package com.railtrack.train.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.railtrack.common.dto.RailRadarResponse;
import com.railtrack.history.entity.SearchOperation;
import com.railtrack.history.service.SearchHistoryService;
import com.railtrack.train.client.RailRadarClient;
import com.railtrack.train.dto.request.TrainSearchHistoryRequest;
import com.railtrack.train.dto.response.*;
import com.railtrack.train.mapper.RailRadarMapper;
import com.railtrack.train.service.TrainSearchHistoryService;
import com.railtrack.train.service.TrainService;
import com.railtrack.train.util.TrainRecommendationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TrainServiceImpl implements TrainService {

    private static final Logger log = LoggerFactory.getLogger(TrainServiceImpl.class);

    private final RailRadarClient client;
    private final TrainRecommendationUtil recommendationUtil;
    private final SearchHistoryService searchHistoryService;
    private final TrainSearchHistoryService trainSearchHistoryService;
    private final RailRadarMapper mapper;

    public TrainServiceImpl(
            RailRadarClient client,
            TrainRecommendationUtil recommendationUtil,
            SearchHistoryService searchHistoryService,
            TrainSearchHistoryService trainSearchHistoryService,
            RailRadarMapper mapper) {

        this.client = client;
        this.recommendationUtil = recommendationUtil;
        this.searchHistoryService = searchHistoryService;
        this.trainSearchHistoryService = trainSearchHistoryService;
        this.mapper = mapper;
    }

    @Override
    public RecommendedTrainResponse getRecommendedTrain(String from, String to) {
        TrainSearchResponse response = client.legacySearchTrains(from, to);
        Train bestTrain = recommendationUtil.getBestTrain(response.getData().getTrains());
        return new RecommendedTrainResponse(bestTrain, response);
    }

    @Override
    public TrainDetailsResponse trainDetails(String number, boolean haltsOnly) {
        long start = System.currentTimeMillis();
        RailRadarResponse response = client.trainDetails(number, haltsOnly);
        record(SearchOperation.TRAIN_DETAILS, number, response);
        logDuration("trainDetails", number, start);
        return mapper.mapTrainDetails(response);
    }

    @Override
    public LiveTrainResponse liveTrain(String number, LocalDate date,
                                       boolean haltsOnly, boolean geometry,
                                       String format, boolean includeCoordinates) {
        long start = System.currentTimeMillis();
        RailRadarResponse response = client.liveTrain(number, date, haltsOnly, geometry, format, includeCoordinates);
        record(SearchOperation.LIVE_TRAIN_STATUS, number, response);
        logDuration("liveTrain", number, start);
        return mapper.mapLiveTrain(response);
    }

    // ---------------------------------------------------------------
    // 🚆 ROUTE METHOD: Merges Timetable Schedule into Geometry
    // ---------------------------------------------------------------
    @Override
    public TrainRouteResponse route(String number, String format, boolean stops) {
        long start = System.currentTimeMillis();
        RailRadarResponse routeResponseRaw = client.route(number, format, stops);
        record(SearchOperation.TRAIN_ROUTE_GEOMETRY, number, routeResponseRaw);
        logDuration("route", number, start);

        TrainRouteResponse routeResponse = mapper.mapTrainRoute(routeResponseRaw);

        // Fetch Train Details to merge full timetable schedule & runDays
        try {
            RailRadarResponse detailsRaw = client.trainDetails(number, false);
            if (detailsRaw != null && detailsRaw.success() && detailsRaw.data() != null) {
                JsonNode dataNode = detailsRaw.data();

                if (routeResponse != null) {
                    // 1. Enrich Train Name & Running Days if missing
                    if (dataNode.has("train")) {
                        JsonNode trainNode = dataNode.get("train");
                        if (routeResponse.getTrainName() == null && trainNode.has("name")) {
                            routeResponse.setTrainName(trainNode.get("name").asText());
                        }
                        if (routeResponse.getRunningDays() == null || routeResponse.getRunningDays().isEmpty()) {
                            List<String> days = new ArrayList<>();
                            JsonNode runDaysNode = trainNode.has("runDays") ? trainNode.get("runDays") : trainNode.get("runningDays");
                            if (runDaysNode != null && runDaysNode.isArray()) {
                                for (JsonNode d : runDaysNode) days.add(d.asText());
                            }
                            routeResponse.setRunningDays(days);
                        }
                    }

                    // 2. Extract scheduled halts array
                    JsonNode scheduleStops = getScheduleStopsNode(dataNode);

                    if (scheduleStops != null && scheduleStops.isArray() && routeResponse.getStations() != null) {
                        Map<String, JsonNode> scheduleMap = new HashMap<>();
                        for (JsonNode stop : scheduleStops) {
                            String code = getStationCode(stop);
                            if (code != null) {
                                scheduleMap.put(code.trim().toUpperCase(), stop);
                            }
                        }

                        List<RouteStationResponse> stationList = routeResponse.getStations();
                        int totalStations = stationList.size();

                        // Merge times and compute halt minutes
                        for (int i = 0; i < totalStations; i++) {
                            RouteStationResponse st = stationList.get(i);
                            if (st.getStationCode() != null) {
                                String stCode = st.getStationCode().trim().toUpperCase();
                                if (scheduleMap.containsKey(stCode)) {
                                    JsonNode sched = scheduleMap.get(stCode);

                                    String arr = getJsonText(sched, "arrival", "arrivalTime", "arrTime", "scheduledArrival", "arr");
                                    String dep = getJsonText(sched, "departure", "departureTime", "depTime", "scheduledDeparture", "dep");
                                    Integer halt = getJsonInt(sched, "haltMinutes", "halt", "haltTime", "duration");
                                    Integer day = getJsonInt(sched, "dayNumber", "day", "dayNo", "dayCount", "arrivalDay", "departureDay");

                                    if (arr != null && !arr.isBlank() && !arr.equalsIgnoreCase("null")) st.setArrival(arr);
                                    if (dep != null && !dep.isBlank() && !dep.equalsIgnoreCase("null")) st.setDeparture(dep);
                                    if (day != null) st.setDayNumber(day);

                                    // Guard first and last station defaults
                                    if (i == 0) st.setArrival("--");
                                    if (i == totalStations - 1) st.setDeparture("--");

                                    // Calculate haltMinutes if 0 or null from API
                                    if (halt != null && halt > 0) {
                                        st.setHaltMinutes(halt);
                                    } else if (st.getArrival() != null && st.getDeparture() != null
                                            && !st.getArrival().equals("--") && !st.getDeparture().equals("--")) {
                                        st.setHaltMinutes(calculateHaltMinutes(st.getArrival(), st.getDeparture()));
                                    } else {
                                        st.setHaltMinutes(0);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not merge schedule details for train {}: {}", number, e.getMessage());
        }

        return routeResponse;
    }

    private JsonNode getScheduleStopsNode(JsonNode dataNode) {
        if (dataNode.has("route")) return dataNode.get("route");
        if (dataNode.has("halts")) return dataNode.get("halts");
        if (dataNode.has("schedule")) return dataNode.get("schedule");
        if (dataNode.has("stops")) return dataNode.get("stops");
        if (dataNode.has("train")) {
            JsonNode t = dataNode.get("train");
            if (t.has("route")) return t.get("route");
            if (t.has("halts")) return t.get("halts");
            if (t.has("schedule")) return t.get("schedule");
            if (t.has("stops")) return t.get("stops");
        }
        return null;
    }

    private String getStationCode(JsonNode stop) {
        if (stop.has("stationCode") && !stop.get("stationCode").isNull()) return stop.get("stationCode").asText();
        if (stop.has("code") && !stop.get("code").isNull()) return stop.get("code").asText();
        if (stop.has("station") && stop.get("station").has("code") && !stop.get("station").get("code").isNull()) {
            return stop.get("station").get("code").asText();
        }
        return null;
    }

    private String getJsonText(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k) && !node.get(k).isNull()) return node.get(k).asText();
        }
        return null;
    }

    private Integer getJsonInt(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k) && !node.get(k).isNull()) return node.get(k).asInt();
        }
        return null;
    }

    private int calculateHaltMinutes(String arrivalTime, String departureTime) {
        try {
            String[] arrParts = arrivalTime.split(":");
            String[] depParts = departureTime.split(":");

            int arrMinutes = Integer.parseInt(arrParts[0].trim()) * 60 + Integer.parseInt(arrParts[1].trim());
            int depMinutes = Integer.parseInt(depParts[0].trim()) * 60 + Integer.parseInt(depParts[1].trim());

            int diff = depMinutes - arrMinutes;
            return Math.max(diff, 0);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            log.warn("Unable to calculate halt duration from arrival {} and departure {}; using zero.",
                    arrivalTime, departureTime);
            return 0;
        }
    }

    @Override
    public JourneyResponse betweenStations(String from, String to, LocalDate date,
                                           boolean live, boolean byCity,
                                           String type, String category,
                                           String quota, String travelClass) {
        long start = System.currentTimeMillis();
        RailRadarResponse response = client.betweenStations(from, to, date, live, byCity, type, category);

        if (response != null && response.success()) {
            searchHistoryService.save(SearchOperation.JOURNEY_BETWEEN_STATIONS, from, to);
            if (date != null && travelClass != null && quota != null) {
                trainSearchHistoryService.saveSearch(new TrainSearchHistoryRequest(
                        from, to, date, travelClass, quota));
            }
        }
        logDuration("betweenStations", from + "->" + to, start);

        JourneyResponse journeyResponse = mapper.mapBetweenStations(response, from, to);

        if (date != null && journeyResponse != null && journeyResponse.getTrains() != null) {

            String targetDay = date.getDayOfWeek()
                    .getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toLowerCase();

            List<TrainSummaryResponse> filteredTrains =
                    journeyResponse.getTrains().stream()
                            .filter(train -> {
                                if (train.getRunningDays() == null || train.getRunningDays().isEmpty()) {
                                    return true;
                                }

                                List<String> runningDaysLower = train.getRunningDays().stream()
                                        .map(String::toLowerCase)
                                        .collect(Collectors.toList());

                                return runningDaysLower.contains(targetDay) ||
                                        runningDaysLower.contains("daily") ||
                                        runningDaysLower.contains("all");
                            })
                            .collect(Collectors.toList());

            journeyResponse.setTrains(filteredTrains);
        }

        return journeyResponse;
    }

    @Override
    public StationBoardResponse stationBoard(String code, boolean includeIntermediate) {
        long start = System.currentTimeMillis();
        RailRadarResponse response = client.stationBoard(code, includeIntermediate);
        RailRadarResponse liveResponse = null;

        // Preserve the scheduled board even when the optional live feed is
        // temporarily unavailable. The live feed only enriches matching rows.
        try {
            liveResponse = client.stationLiveBoard(code, 4, includeIntermediate);
        } catch (Exception exception) {
            log.warn("Live station-board enrichment unavailable for {}: {}", code, exception.getMessage());
        }

        record(SearchOperation.STATION_BOARD, code, response);
        logDuration("stationBoard", code, start);
        return mapper.mapStationBoard(response, liveResponse, code);
    }

    @Override
    public LiveStationBoardResponse stationLiveBoard(String code, int hours,
                                                     boolean includeIntermediate) {
        long start = System.currentTimeMillis();
        RailRadarResponse response = client.stationLiveBoard(code, hours, includeIntermediate);
        record(SearchOperation.STATION_LIVE_BOARD, code, response);
        logDuration("stationLiveBoard", code, start);
        return mapper.mapLiveStationBoard(response, code);
    }

    private void record(SearchOperation operation, String identifier, RailRadarResponse response) {
        if (response != null && response.success()) {
            searchHistoryService.save(operation, identifier, null);
        }
    }

    private void logDuration(String operation, String identifier, long startMillis) {
        log.info("RailRadar {} completed for {} in {}ms",
                operation, identifier, System.currentTimeMillis() - startMillis);
    }
}
