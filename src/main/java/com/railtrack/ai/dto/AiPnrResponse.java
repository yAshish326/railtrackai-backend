package com.railtrack.ai.dto;

public class AiPnrResponse {
    private String currentStatus;       // "CONFIRMED", "RAC", "WAITLISTED", or "UNKNOWN"
    private double confirmationChance;   // Estimated chance of a full confirmed berth
    private String aiRecommendation;    // Smart advice text or alternate bus text
    private boolean alternativeSuggested; // True if percentage < 70%

    public AiPnrResponse() {
    }

    public AiPnrResponse(String currentStatus, double confirmationChance, String aiRecommendation, boolean alternativeSuggested) {
        this.currentStatus = currentStatus;
        this.confirmationChance = confirmationChance;
        this.aiRecommendation = aiRecommendation;
        this.alternativeSuggested = alternativeSuggested;
    }

    // Getters and Setters
    public String getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
    }

    public double getConfirmationChance() {
        return confirmationChance;
    }

    public void setConfirmationChance(double confirmationChance) {
        this.confirmationChance = confirmationChance;
    }

    public String getAiRecommendation() {
        return aiRecommendation;
    }

    public void setAiRecommendation(String aiRecommendation) {
        this.aiRecommendation = aiRecommendation;
    }

    public boolean isAlternativeSuggested() {
        return alternativeSuggested;
    }

    public void setAlternativeSuggested(boolean alternativeSuggested) {
        this.alternativeSuggested = alternativeSuggested;
    }
}
