package com.railtrack.ai.controller;

import com.railtrack.ai.dto.AiChatRequest;
import com.railtrack.ai.dto.AiChatResponse;
import com.railtrack.ai.dto.AiRateLimitStatusResponse;
import com.railtrack.ai.ratelimit.AiRateLimitStatus;
import com.railtrack.ai.ratelimit.AiRateLimiterService;
import com.railtrack.ai.service.AiChatService;
import com.railtrack.ai.service.AiHistoryService;
import com.railtrack.auth.entity.User;
import com.railtrack.auth.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.*;

/**
 * Backs the "AI Assistant" chat page (free-form Q&A about trains, PNR,
 * station boards, travel planning etc). Kept separate from {@link AiController},
 * which only handles the structured analyze-trains / analyze-pnr calls
 * triggered from the Dashboard and PNR screens.
 * This endpoint requires authentication (see SecurityConfig) because the
 * rate limiter and history are both scoped per logged-in user.
 */
@RestController
@RequestMapping("/api/v1/ai/assistant")
public class AiAssistantController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantController.class);

    private final AiChatService aiChatService;
    private final AiHistoryService aiHistoryService;
    private final AiRateLimiterService rateLimiterService;
    private final UserService userService;

    public AiAssistantController(AiChatService aiChatService,
                                  AiHistoryService aiHistoryService,
                                  AiRateLimiterService rateLimiterService,
                                  UserService userService) {
        this.aiChatService = aiChatService;
        this.aiHistoryService = aiHistoryService;
        this.rateLimiterService = rateLimiterService;
        this.userService = userService;
    }

    /**
     * Sends a free-form message to the AI Assistant.
     * Subject to a per-user daily quota (see assistant.rate-limit.* properties).
     */
    @PostMapping("/chat")
    public ResponseEntity<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {

        User user = userService.getAuthenticatedUser();

        rateLimiterService.checkAndConsume(user.getId());

        String reply = aiChatService.chat(request.getMessage());

        try {
            aiHistoryService.saveHistory(user, request.getMessage(), reply);
        } catch (DataAccessException | TransactionSystemException e) {
            log.warn("Unable to save AI assistant history for user {}", user.getId(), e);
        }

        AiRateLimitStatus status = rateLimiterService.getStatus(user.getId());

        return ResponseEntity.ok()
                .header("X-RateLimit-Limit", String.valueOf(status.getLimit()))
                .header("X-RateLimit-Remaining", String.valueOf(status.getRemaining()))
                .body(new AiChatResponse(reply));
    }

    /**
     * Returns the current user's remaining AI Assistant quota for today,
     * so the frontend can show something like "12 / 20 messages left".
     */
    @GetMapping("/limit")
    public ResponseEntity<AiRateLimitStatusResponse> getLimit() {
        User user = userService.getAuthenticatedUser();
        AiRateLimitStatus status = rateLimiterService.getStatus(user.getId());

        return ResponseEntity.ok(new AiRateLimitStatusResponse(
                status.getLimit(),
                status.getUsed(),
                status.getRemaining(),
                status.getResetAt()
        ));
    }
}
