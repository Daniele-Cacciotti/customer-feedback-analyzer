// Adapted and modified for Customer Feedback Analyzer. See the root README for lab attribution.
package com.retailstore.feedback.service;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.retailstore.feedback.model.EnhancedFeedback;
import com.retailstore.feedback.model.FeedbackEntry;
import com.retailstore.feedback.model.FeedbackSummary;

/**
 * Service for processing feedback data and enhancing it with AI insights.
 */
@Service
public class FeedbackService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeedbackService.class);

    private final GeminiService geminiService;
    private final Path sentimentFile;

    public FeedbackService(GeminiService geminiService,
            @Value("${feedback.sentiment-file}") String sentimentFile) {
        this.geminiService = geminiService;
        this.sentimentFile = Path.of(sentimentFile);
    }

    // Patterns for parsing feedback file
    private static final Pattern FEEDBACK_PATTERN = Pattern.compile("(?m)^Feedback #(\\d+)$");
    private static final Pattern CUSTOMER_PATTERN = Pattern.compile("(?m)^Customer:[ \\t]*(.+)$");
    private static final Pattern DEPARTMENT_PATTERN = Pattern.compile("(?m)^Department:[ \\t]*(.+)$");
    private static final Pattern DATE_PATTERN = Pattern.compile("(?m)^Date:[ \\t]*(.+)$");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?m)^Comment:[ \\t]*(.+)$");
    private static final Pattern SENTIMENT_PATTERN = Pattern.compile("(?m)^Sentiment:[ \\t]*(.+)$");

    private Map<InsightKey, GeminiService.Insight> cachedInsights = Map.of();

    /**
     * Reads feedback data from the sentiment analysis output file.
     *
     * @return List of FeedbackEntry objects
     * @throws IOException If an I/O error occurs
     */
    public List<FeedbackEntry> readFeedbackData() throws IOException {
        List<FeedbackEntry> entries = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(sentimentFile)) {
            StringBuilder entryText = new StringBuilder();
            String line;

            boolean inDetailedSection = false;
            while ((line = reader.readLine()) != null) {
                // Check if we've reached the detailed feedback section
                if (line.contains("## Detailed Feedback Entries")) {
                    inDetailedSection = true;
                    continue;
                }

                if (!inDetailedSection) {
                    continue;
                }

                // Process each line
                if (line.trim().isEmpty() && entryText.length() > 0) {
                    // We've reached the end of an entry
                    FeedbackEntry entry = parseFeedbackEntry(entryText.toString());
                    if (entry != null) {
                        entries.add(entry);
                    }
                    entryText = new StringBuilder();
                } else {
                    entryText.append(line).append("\n");
                }
            }

            // Process the last entry if there is one
            if (entryText.length() > 0) {
                FeedbackEntry entry = parseFeedbackEntry(entryText.toString());
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }

        return entries;
    }

    /**
     * Parses a feedback entry from text.
     *
     * @param text Text containing a feedback entry
     * @return FeedbackEntry object or null if parsing fails
     */
    private FeedbackEntry parseFeedbackEntry(String text) {
        FeedbackEntry entry = new FeedbackEntry();

        // Extract feedback ID
        Matcher idMatcher = FEEDBACK_PATTERN.matcher(text);
        if (idMatcher.find()) {
            entry.setId(Integer.parseInt(idMatcher.group(1)));
        } else {
            return null;
        }

        // Extract customer
        Matcher customerMatcher = CUSTOMER_PATTERN.matcher(text);
        if (customerMatcher.find()) {
            entry.setCustomer(customerMatcher.group(1));
        }

        // Extract department
        Matcher departmentMatcher = DEPARTMENT_PATTERN.matcher(text);
        if (departmentMatcher.find()) {
            entry.setDepartment(departmentMatcher.group(1));
        }

        // Extract date
        Matcher dateMatcher = DATE_PATTERN.matcher(text);
        if (dateMatcher.find()) {
            entry.setDate(dateMatcher.group(1));
        }

        // Extract comment
        Matcher commentMatcher = COMMENT_PATTERN.matcher(text);
        if (commentMatcher.find()) {
            entry.setComment(commentMatcher.group(1));
        }

        // Extract sentiment
        Matcher sentimentMatcher = SENTIMENT_PATTERN.matcher(text);
        if (sentimentMatcher.find()) {
            entry.setSentiment(sentimentMatcher.group(1));
        }

        if (entry.getDepartment() == null || entry.getComment() == null || entry.getSentiment() == null) {
            return null;
        }
        return entry;
    }

    /**
     * Enhances feedback with AI-generated categories and actionable insights.
     *
     * @return List of EnhancedFeedback objects
     * @throws IOException If an I/O error occurs
     */
    public synchronized List<EnhancedFeedback> getEnhancedFeedback() throws IOException {
        List<FeedbackEntry> entries = readFeedbackData();
        List<EnhancedFeedback> results = new ArrayList<>(entries.size());
        Map<InsightKey, GeminiService.Insight> currentInsights = new HashMap<>();
        boolean geminiConfigured = geminiService.isConfigured();
        boolean geminiAvailable = geminiConfigured;

        for (FeedbackEntry entry : entries) {
            EnhancedFeedback feedback = new EnhancedFeedback(entry);
            InsightKey key = new InsightKey(entry.getComment(), entry.getDepartment(), entry.getSentiment());
            GeminiService.Insight insight = cachedInsights.get(key);
            if (insight == null) {
                insight = currentInsights.get(key);
            }
            if (insight == null && geminiAvailable) {
                try {
                    insight = geminiService.analyze(entry);
                } catch (GeminiService.GeminiException e) {
                    LOGGER.warn("Feedback enrichment stopped at entry {}. {}", entry.getId(), e.getMessage());
                    geminiAvailable = false;
                }
            }

            if (insight != null) {
                feedback.setCategory(insight.category());
                feedback.setActionableInsight(insight.actionableInsight());
                currentInsights.put(key, insight);
            } else {
                feedback.setActionableInsight(geminiConfigured
                        ? "Gemini enrichment unavailable. Reload to retry."
                        : "Set GEMINI_API_KEY to enable enrichment.");
            }
            results.add(feedback);
        }

        // Keep successful results for the current report and retry missing results on the next request.
        cachedInsights = currentInsights;
        return results;
    }

    private record InsightKey(String comment, String department, String sentiment) {}

    /**
     * Generates a summary of the feedback data for the dashboard.
     *
     * @return FeedbackSummary object
     * @throws IOException If an I/O error occurs
     */
    public FeedbackSummary generateFeedbackSummary() throws IOException {
        List<EnhancedFeedback> allFeedback = getEnhancedFeedback();
        FeedbackSummary summary = new FeedbackSummary();

        // Set total feedback count
        summary.setTotalFeedback(allFeedback.size());

        // Count sentiments
        Map<String, Integer> sentimentCounts = new HashMap<>();
        for (EnhancedFeedback feedback : allFeedback) {
            String sentiment = feedback.getSentiment();
            sentimentCounts.put(sentiment, sentimentCounts.getOrDefault(sentiment, 0) + 1);
        }
        summary.setSentimentCounts(sentimentCounts);

        // Count categories
        Map<String, Integer> categoryCounts = new HashMap<>();
        for (EnhancedFeedback feedback : allFeedback) {
            String category = feedback.getCategory();
            if (category != null) {
                categoryCounts.merge(category, 1, Integer::sum);
            }
        }
        summary.setCategoryCounts(categoryCounts);
        int enrichedCount = categoryCounts.values().stream().mapToInt(Integer::intValue).sum();
        summary.setUnenrichedFeedback(allFeedback.size() - enrichedCount);

        // Count departments
        Map<String, Integer> departmentCounts = new HashMap<>();
        for (EnhancedFeedback feedback : allFeedback) {
            String department = feedback.getDepartment();
            departmentCounts.put(department, departmentCounts.getOrDefault(department, 0) + 1);
        }
        summary.setDepartmentCounts(departmentCounts);

        summary.setAllFeedback(allFeedback);

        // Get recent feedback (last 5 entries)
        List<EnhancedFeedback> recentFeedback = allFeedback.stream()
                .sorted(Comparator.comparing(EnhancedFeedback::getId).reversed())
                .limit(5)
                .collect(Collectors.toList());
        summary.setRecentFeedback(recentFeedback);

        return summary;
    }
}
