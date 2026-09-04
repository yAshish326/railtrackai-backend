package com.railtrack.train.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.railtrack.common.dto.RailRadarResponse;
import com.railtrack.train.dto.response.JourneyResponse;
import com.railtrack.train.dto.response.LiveStationBoardResponse;
import com.railtrack.train.dto.response.LiveTrainResponse;
import com.railtrack.train.dto.response.RouteStationResponse;
import com.railtrack.train.dto.response.StationBoardResponse;
import com.railtrack.train.dto.response.StationBoardTrainResponse;
import com.railtrack.train.dto.response.Station;
import com.railtrack.train.dto.response.TrainDetailsResponse;
import com.railtrack.train.dto.response.TrainRouteResponse;
import com.railtrack.train.dto.response.TrainSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

@Component
public class RailRadarMapper {

    private static final Logger log = LoggerFactory.getLogger(RailRadarMapper.class);

    // ---------------------------------------------------------------
    public TrainDetailsResponse mapTrainDetails(RailRadarResponse response) {

        JsonNode data = validData(response, "trainDetails");
        if (data == null) {
            return null;
        }

        JsonNode train = data.get("train");
        if (train == null || train.isNull()) {
            return null;
        }

        JsonNode source = train.get("source");
        JsonNode destination = train.get("destination");

        return TrainDetailsResponse.builder()
                .trainNumber(getText(train, "number"))
                .trainName(getText(train, "name"))
                .trainType(getText(train, "type"))
                .sourceStationCode(getText(source, "code"))
                .sourceStationName(getText(source, "name"))
                .destinationStationCode(getText(destination, "code"))
                .destinationStationName(getText(destination, "name"))
                .distanceKm(getDouble(train, "distance"))
                .travelTimeMinutes(getInt(train, "duration"))
                .totalHalts(getInt(train, "totalHalts"))
                .runningDays(extractStringArrayWithFallback(train, "runDays", "runningDays", "runsOn"))
                .build();
    }

    // ---------------------------------------------------------------
    // 🚆 ACCURATE & FIXED LIVE TRAIN MAPPING
    // ---------------------------------------------------------------
    public LiveTrainResponse mapLiveTrain(RailRadarResponse response) {

        JsonNode data = validData(response, "liveTrain");
        if (data == null) {
            return null;
        }

        JsonNode currentLocation = data.get("currentLocation");
        JsonNode previousHalt = data.get("previousHalt");
        JsonNode nextHalt = data.get("nextHalt");
        JsonNode routeArray = data.get("route");

        // Index route entries by upper-case station code for O(1) lookup
        Map<String, JsonNode> routeByStationCode = indexRouteByCode(routeArray);

        String currentStationCode = getTextWithFallback(currentLocation, "stationCode", "code");
        String nextStationCode = getTextWithFallback(nextHalt, "stationCode", "code");

        JsonNode currentRouteEntry = currentStationCode != null ? routeByStationCode.get(currentStationCode.toUpperCase()) : null;
        JsonNode nextRouteEntry = nextStationCode != null ? routeByStationCode.get(nextStationCode.toUpperCase()) : null;

        // Extract coordinates from current route entry (or fallback to scanning routeArray)
        Double lat = getDoubleWithFallback(currentRouteEntry, "lat", "latitude");
        Double lng = getDoubleWithFallback(currentRouteEntry, "lng", "longitude", "lon");

        if ((lat == null || lng == null) && currentStationCode != null && routeArray != null && routeArray.isArray()) {
            for (JsonNode stop : routeArray) {
                String code = getTextWithFallback(stop, "stationCode", "code");
                if (currentStationCode.equalsIgnoreCase(code)) {
                    if (lat == null) lat = getDoubleWithFallback(stop, "lat", "latitude");
                    if (lng == null) lng = getDoubleWithFallback(stop, "lng", "longitude", "lon");
                    break;
                }
            }
        }

        // Speed & Platform extraction
        Double speed = getDoubleWithFallback(currentLocation, "speedKmh", "speedKmph", "speed");
        String platform = getTextWithFallback(currentRouteEntry, "platform", "pf");
        if (platform == null) platform = getTextWithFallback(currentLocation, "platform", "pf");

        String currentStationName = getTextWithFallback(currentLocation, "stationName", "name");
        if (currentStationName == null && currentRouteEntry != null) {
            currentStationName = getTextWithFallback(currentRouteEntry, "stationName", "name");
        }

        // Extract & format Arrival times (formatting ISO timestamps into HH:mm)
        String scheduledArrivalISO = getTextWithFallback(nextRouteEntry, "scheduledArrival", "expectedArrival", "expectedArrivalTime");
        String actualArrivalISO = getTextWithFallback(nextRouteEntry, "actualArrival");

        return LiveTrainResponse.builder()
                .trainNumber(getTextWithFallback(data, "trainNumber", "number"))
                .trainName(getTextWithFallback(data, "trainName", "name"))
                .previousStation(getTextWithFallback(previousHalt, "stationName", "name"))
                .currentStation(currentStationName != null ? currentStationName : currentStationCode)
                .nextStation(getTextWithFallback(nextHalt, "stationName", "name"))
                .latitude(lat)
                .longitude(lng)
                .delayMinutes(getIntWithFallback(data, "delayMinutes", "delay"))
                .expectedArrival(formatIsoTime(scheduledArrivalISO))
                .actualArrival(formatIsoTime(actualArrivalISO))
                .platform(platform)
                .speedKmph(speed)
                .runningStatus(getTextWithFallback(data, "status", "runningStatus"))
                .lastUpdatedAt(getTextWithFallback(data, "lastUpdatedAt", "updatedAt"))
                .build();
    }

    private Map<String, JsonNode> indexRouteByCode(JsonNode routeArray) {
        Map<String, JsonNode> index = new HashMap<>();
        if (routeArray == null || !routeArray.isArray()) {
            return index;
        }
        for (JsonNode element : routeArray) {
            String key = getTextWithFallback(element, "stationCode", "code", "stnCode");
            if (key != null) {
                index.put(key.trim().toUpperCase(), element);
            }
        }
        return index;
    }

    private String formatIsoTime(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank() || isoDateTime.equalsIgnoreCase("null")) {
            return "--";
        }
        try {
            if (isoDateTime.contains("T")) {
                String timePart = isoDateTime.split("T")[1];
                return timePart.substring(0, 5); // Extracts "HH:mm"
            }
        } catch (Exception e) {
            log.trace("Could not parse ISO time {}, returning raw string.", isoDateTime);
        }
        return isoDateTime;
    }

    // ---------------------------------------------------------------
    // 🚆 FIXED & ENHANCED TRAIN ROUTE MAPPING
    // ---------------------------------------------------------------
    public TrainRouteResponse mapTrainRoute(RailRadarResponse response) {

        JsonNode data = validData(response, "trainRoute");
        if (data == null) {
            return null;
        }

        List<RouteStationResponse> stations = new ArrayList<>();

        // Support "stations", "stops", "halts", or "schedule" arrays
        JsonNode stops = getArrayNodeWithFallback(data, "stations", "stops", "halts", "schedule");

        if (stops != null && stops.isArray()) {
            int seq = 1;
            for (JsonNode stop : stops) {
                // Multi-key extraction with fallbacks
                Integer sequence = getIntWithFallback(stop, "sequence", "seq", "sn", "s_no");
                String code = getTextWithFallback(stop, "stationCode", "code", "stnCode", "stn_code");
                String name = getTextWithFallback(stop, "stationName", "name", "stnName", "stn_name");

                String arrival = getTextWithFallback(stop, "arrival", "arrivalTime", "arr_time", "scheduledArrival", "arrTime", "arr");
                String departure = getTextWithFallback(stop, "departure", "departureTime", "dep_time", "scheduledDeparture", "depTime", "dep");
                Integer halt = getIntWithFallback(stop, "haltMinutes", "halt", "halt_time", "haltTime", "duration");
                Double distance = getDoubleWithFallback(stop, "distanceKm", "distance", "dist", "distanceFromSourceKm");
                Integer day = getIntWithFallback(stop, "dayNumber", "day", "dayCount", "dayNo");

                stations.add(RouteStationResponse.builder()
                        .sequence(sequence != null ? sequence : seq++)
                        .stationCode(code)
                        .stationName(name != null ? name : code)
                        .dayNumber(day != null ? day : 1)
                        .distanceKm(distance != null ? distance : 0.0)
                        .arrival(arrival != null && !arrival.isBlank() && !arrival.equals("null") ? arrival : "--")
                        .departure(departure != null && !departure.isBlank() && !departure.equals("null") ? departure : "--")
                        .haltMinutes(halt != null ? halt : 0)
                        .platform(getTextWithFallback(stop, "platform", "pf"))
                        .latitude(getDoubleWithFallback(stop, "latitude", "lat"))
                        .longitude(getDoubleWithFallback(stop, "longitude", "lng", "lon"))
                        .currentStation(stop.has("currentStation") && stop.get("currentStation").asBoolean())
                        .build());
            }
        }

        // Calculate cumulative Haversine distance if API returned 0.0 across all nodes
        double cumulativeKm = 0.0;
        RouteStationResponse prevStation = null;
        for (RouteStationResponse st : stations) {
            if (st.getDistanceKm() == 0.0 && prevStation != null
                    && st.getLatitude() != null && st.getLongitude() != null
                    && prevStation.getLatitude() != null && prevStation.getLongitude() != null) {
                double distStep = calculateHaversineDistance(
                        prevStation.getLatitude(), prevStation.getLongitude(),
                        st.getLatitude(), st.getLongitude()
                );
                cumulativeKm += distStep;
                st.setDistanceKm(Math.round(cumulativeKm * 10.0) / 10.0);
            } else if (st.getDistanceKm() > 0.0) {
                cumulativeKm = st.getDistanceKm();
            }
            prevStation = st;
        }

        // Top-level train details
        String trainNumber = getTextWithFallback(data, "trainNumber", "number");
        String trainName = getTextWithFallback(data, "trainName", "name");
        Double totalDistance = getDoubleWithFallback(data, "totalDistanceKm", "totalDistance", "distance");

        if (totalDistance == null || totalDistance == 0.0) {
            totalDistance = Math.round(cumulativeKm * 10.0) / 10.0;
        }

        // Comprehensive extraction of runningDays
        List<String> runningDays = extractStringArrayWithFallback(data, "runningDays", "runDays", "runsOn");
        if (runningDays.isEmpty() && data.has("train")) {
            JsonNode trainNode = data.get("train");
            runningDays = extractStringArrayWithFallback(trainNode, "runDays", "runningDays", "runsOn");
            if (trainName == null && trainNode.has("name")) {
                trainName = trainNode.get("name").asText();
            }
        }

        return TrainRouteResponse.builder()
                .trainNumber(trainNumber)
                .trainName(trainName)
                .totalDistanceKm(totalDistance)
                .runningDays(runningDays)
                .stations(stations)
                .build();
    }

    // --- Helper for Haversine Distance Calculation ---
    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // ---------------------------------------------------------------
    public JourneyResponse mapBetweenStations(RailRadarResponse response, String from, String to) {

        JsonNode data = validData(response, "betweenStations");
        if (data == null) {
            return JourneyResponse.builder()
                    .source(from).destination(to).totalTrains(0).trains(new ArrayList<>())
                    .build();
        }

        JsonNode fromStation = data.get("from");
        JsonNode toStation = data.get("to");
        String sourceName = fromStation != null ? getText(fromStation, "name") : from;
        String destinationName = toStation != null ? getText(toStation, "name") : to;
        Station sourceStation = new Station(fromStation != null ? getText(fromStation, "code") : from, sourceName);
        Station destinationStation = new Station(toStation != null ? getText(toStation, "code") : to, destinationName);

        List<TrainSummaryResponse> trains = new ArrayList<>();
        JsonNode trainList = data.get("trains");
        if (trainList != null && trainList.isArray()) {
            for (JsonNode entry : trainList) {
                JsonNode train = entry.get("train");
                JsonNode fromLeg = entry.get("from");
                JsonNode toLeg = entry.get("to");

                trains.add(TrainSummaryResponse.builder()
                        .trainNumber(getText(train, "number"))
                        .trainName(getText(train, "name"))
                        .trainType(getText(train, "type"))
                        .source(sourceStation)
                        .destination(destinationStation)
                        .departure(getText(fromLeg, "departure"))
                        .arrival(getText(toLeg, "arrival"))
                        .duration(getText(entry, "duration"))
                        .distanceKm(getDouble(entry, "distance"))
                        .runningDays(extractStringArrayWithFallback(train, "runDays", "runningDays", "runsOn"))
                        .availableClasses(new ArrayList<>())
                        .departureDayNumber(getInt(fromLeg, "day"))
                        .arrivalDayNumber(getInt(toLeg, "day"))
                        .departureSequence(getInt(fromLeg, "sequence"))
                        .arrivalSequence(getInt(toLeg, "sequence"))
                        .build());
            }
        }

        return JourneyResponse.builder()
                .source(sourceName)
                .destination(destinationName)
                // The upstream count can include duplicate entries. The API contract
                // requires this value to match the returned train list.
                .totalTrains(trains.size())
                .trains(trains)
                .build();
    }

    // ---------------------------------------------------------------
    public StationBoardResponse mapStationBoard(RailRadarResponse response,
                                                RailRadarResponse liveResponse,
                                                String stationCode) {

        JsonNode data = validData(response, "stationBoard");
        JsonNode liveData = validData(liveResponse, "stationBoard live enrichment");
        List<StationBoardTrainResponse> trains = new ArrayList<>();
        String stationName = null;
        Map<String, JsonNode> liveByTrainNumber = new HashMap<>();

        if (liveData != null) {
            JsonNode liveTrains = liveData.get("trains");
            if (liveTrains != null && liveTrains.isArray()) {
                for (JsonNode entry : liveTrains) {
                    String trainNumber = getText(entry.get("train"), "number");
                    if (trainNumber != null) {
                        // Keep the whole entry: expected times/status are in
                        // "live", while the live-board platform is in "stop".
                        liveByTrainNumber.put(trainNumber, entry);
                    }
                }
            }
        }

        if (data != null) {
            JsonNode station = data.get("station");
            stationName = getText(station, "name");

            JsonNode trainList = data.get("trains");
            if (trainList != null && trainList.isArray()) {
                for (JsonNode entry : trainList) {
                    JsonNode train = entry.get("train");
                    JsonNode stop = entry.get("stop");
                    String trainNumber = getText(train, "number");
                    JsonNode liveEntry = liveByTrainNumber.get(trainNumber);
                    JsonNode live = liveEntry != null ? liveEntry.get("live") : null;
                    JsonNode liveStop = liveEntry != null ? liveEntry.get("stop") : null;
                    String platform = getTextWithFallback(live, "platform", "pf");
                    if (platform == null) {
                        platform = getTextWithFallback(liveStop, "platform", "pf");
                    }
                    if (platform == null) {
                        platform = getTextWithFallback(stop, "platform", "pf");
                    }
                    trains.add(StationBoardTrainResponse.builder()
                            .trainNumber(trainNumber)
                            .trainName(getText(train, "name"))
                            .arrival(getText(stop, "arrival"))
                            .departure(getText(stop, "departure"))
                            .expectedArrival(getTextWithFallback(live,
                                    "expectedArrivalTime", "expectedArrival"))
                            .expectedDeparture(getTextWithFallback(live,
                                    "expectedDepartureTime", "expectedDeparture"))
                            .delayMinutes(getIntWithFallback(live, "delayMinutes", "delay"))
                            .platform(platform)
                            .status(getTextWithFallback(live, "type", "status"))
                            .build());
                }
            }
        }

        Integer count = data != null ? getInt(data, "count") : null;

        return StationBoardResponse.builder()
                .stationCode(stationCode)
                .stationName(stationName)
                .date(LocalDate.now())
                .totalTrains(count != null ? count : trains.size())
                .trains(trains)
                .build();
    }

    // ---------------------------------------------------------------
    public LiveStationBoardResponse mapLiveStationBoard(RailRadarResponse response, String stationCode) {

        JsonNode data = validData(response, "liveStationBoard");

        List<StationBoardTrainResponse> arriving = new ArrayList<>();
        List<StationBoardTrainResponse> departing = new ArrayList<>();
        List<StationBoardTrainResponse> delayed = new ArrayList<>();
        List<StationBoardTrainResponse> cancelled = new ArrayList<>();

        if (data != null) {
            JsonNode trainList = data.get("trains");
            if (trainList != null && trainList.isArray()) {
                for (JsonNode entry : trainList) {
                    JsonNode train = entry.get("train");
                    JsonNode stop = entry.get("stop");
                    JsonNode live = entry.get("live");

                    StationBoardTrainResponse row = StationBoardTrainResponse.builder()
                            .trainNumber(getText(train, "number"))
                            .trainName(getText(train, "name"))
                            .arrival(getText(stop, "arrival"))
                            .departure(getText(stop, "departure"))
                            .expectedArrival(getText(live, "expectedArrivalTime"))
                            .expectedDeparture(getText(live, "expectedDepartureTime"))
                            .delayMinutes(getInt(live, "delayMinutes"))
                            .platform(getText(live, "platform"))
                            .status(getText(live, "type"))
                            .build();

                    String liveType = row.getStatus();
                    if ("at-station".equals(liveType) || "upcoming".equals(liveType)) {
                        arriving.add(row);
                    }
                    if ("departed".equals(liveType)) {
                        departing.add(row);
                    }
                    if (row.getDelayMinutes() != null && row.getDelayMinutes() > 0) {
                        delayed.add(row);
                    }
                }
            }
        }

        return LiveStationBoardResponse.builder()
                .stationCode(stationCode)
                .arrivingTrains(arriving)
                .departingTrains(departing)
                .delayedTrains(delayed)
                .cancelledTrains(cancelled)
                .build();
    }

    // ---------------------------------------------------------------
    // Shared Helpers with Fallback Key Support
    // ---------------------------------------------------------------

    private JsonNode getArrayNodeWithFallback(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            if (node.has(field) && node.get(field).isArray()) {
                return node.get(field);
            }
        }
        return null;
    }

    private String getTextWithFallback(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asText();
            }
        }
        return null;
    }

    private Integer getIntWithFallback(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asInt();
            }
        }
        return null;
    }

    private Double getDoubleWithFallback(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asDouble();
            }
        }
        return null;
    }

    private List<String> extractStringArrayWithFallback(JsonNode node, String... fields) {
        List<String> values = new ArrayList<>();
        if (node == null) return values;

        for (String field : fields) {
            if (node.has(field) && node.get(field).isArray()) {
                Iterator<JsonNode> iterator = node.get(field).elements();
                while (iterator.hasNext()) {
                    values.add(iterator.next().asText());
                }
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return values;
    }

    private Map<String, JsonNode> indexByField(JsonNode arrayNode, String keyField) {
        Map<String, JsonNode> index = new HashMap<>();
        if (arrayNode == null || !arrayNode.isArray()) {
            return index;
        }
        for (JsonNode element : arrayNode) {
            String key = getText(element, keyField);
            if (key != null) {
                index.put(key, element);
            }
        }
        return index;
    }

    private JsonNode validData(RailRadarResponse response, String context) {
        if (response == null || !response.success() || response.data() == null || response.data().isNull()) {
            log.warn("RailRadar {} response was empty or unsuccessful; returning empty mapping.", context);
            return null;
        }
        return response.data();
    }

    private String getText(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private Integer getInt(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asInt();
    }

    private Double getDouble(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asDouble();
    }
}
