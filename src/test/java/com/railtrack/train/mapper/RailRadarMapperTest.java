package com.railtrack.train.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railtrack.common.dto.RailRadarResponse;
import com.railtrack.train.dto.response.JourneyResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RailRadarMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RailRadarMapper mapper = new RailRadarMapper();

    @Test
    void mapBetweenStationsRetainsLegDayAndSequenceForServiceDateMatching() throws Exception {
        String json = """
                {
                  "from": {"code": "FROM", "name": "From"},
                  "to": {"code": "TO", "name": "To"},
                  "trains": [{
                    "train": {"number": "12345", "runDays": ["fri"]},
                    "from": {"departure": "00:25", "day": 2, "sequence": 14},
                    "to": {"arrival": "13:35", "day": 2, "sequence": 203}
                  }]
                }
                """;

        JourneyResponse response = mapper.mapBetweenStations(
                new RailRadarResponse(true, objectMapper.readTree(json), null), "FROM", "TO");

        var train = response.getTrains().getFirst();
        assertEquals(2, train.getDepartureDayNumber());
        assertEquals(2, train.getArrivalDayNumber());
        assertEquals(14, train.getDepartureSequence());
        assertEquals(203, train.getArrivalSequence());
    }
}
