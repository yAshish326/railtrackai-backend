package com.railtrack.ai.controller;

import com.railtrack.ai.dto.AiPnrResponse;
import com.railtrack.ai.dto.AiTrainRecommendationResponse;
import com.railtrack.ai.service.AiHistoryService;
import com.railtrack.ai.service.AiService;
import com.railtrack.auth.entity.User;
import com.railtrack.auth.service.UserService;
import com.railtrack.train.dto.response.TrainSummaryResponse;
import com.railtrack.pnr.dto.response.PnrData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiService aiService;
    private final AiHistoryService aiHistoryService;
    private final UserService userService;

    public AiController(AiService aiService,
                        AiHistoryService aiHistoryService,
                        UserService userService) {
        this.aiService = aiService;
        this.aiHistoryService = aiHistoryService;
        this.userService = userService;
    }

    /**
     * Endpoint 1: Run analytical assessment on the search results.
     * Triggered right after the user clicks 'Search' on the main Dashboard view.
     */
    @PostMapping("/analyze-trains")
    public ResponseEntity<AiTrainRecommendationResponse> analyzeTrainRoutes(@RequestBody List<TrainSummaryResponse> trainList) {
        User user = userService.getAuthenticatedUser();
        AiTrainRecommendationResponse response = aiService.generateTrainSuggestions(trainList);
        try {
            String prompt = "Train route analysis for: " + trainList.stream()
                    .limit(5)
                    .map(TrainSummaryResponse::getTrainNumber)
                    .toList();
            aiHistoryService.saveHistory(user, prompt, response.getInsightMessage());
        } catch (DataAccessException | TransactionSystemException e) {
            log.warn("Unable to save AI train-analysis history for user {}", user.getId(), e);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint 2: Process current confirmation odds for an active PNR query.
     * Triggered when looking up passenger statuses on the PNR check screen.
     */
    @PostMapping("/analyze-pnr")
    public ResponseEntity<AiPnrResponse> analyzePnrStatus(@RequestBody PnrData pnrData) {
        User user = userService.getAuthenticatedUser();
        AiPnrResponse response = aiService.analyzePnrStatus(pnrData);
        try {
            String prompt = "PNR analysis request for PNR " + pnrData.getPnrNumber();
            aiHistoryService.saveHistory(user, pnrData.getPnrNumber(), prompt, response.getAiRecommendation());
        } catch (DataAccessException | TransactionSystemException e) {
            log.warn("Unable to save AI PNR-analysis history for user {}", user.getId(), e);
        }
        return ResponseEntity.ok(response);
    }
}
