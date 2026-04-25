package com.healthvault.service;

import com.healthvault.config.DatabaseConfig;
import com.healthvault.model.MedicalFile;
import com.healthvault.util.AuditLogger;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Smart tagging service for automatic categorization and tagging of medical files
 */
public class SmartTaggingService {
    
    // Medical term patterns for automatic tagging
    private static final Map<String, List<String>> MEDICAL_PATTERNS = new HashMap<>();
    
    static {
        // Blood test patterns
        MEDICAL_PATTERNS.put("Blood Test", Arrays.asList(
            "blood test", "cbc", "complete blood count", "hemoglobin", "wbc", "rbc",
            "glucose", "cholesterol", "triglycerides", "lipid profile"
        ));
        
        // Imaging patterns
        MEDICAL_PATTERNS.put("Imaging", Arrays.asList(
            "x-ray", "xray", "ct scan", "mri", "ultrasound", "sonography",
            "pet scan", "mammography", "radiology", "imaging"
        ));
        
        // Cardiology patterns
        MEDICAL_PATTERNS.put("Cardiology", Arrays.asList(
            "ecg", "ekg", "electrocardiogram", "echocardiogram", "stress test",
            "holter", "cardiac", "heart", "angiography"
        ));
        
        // Laboratory patterns
        MEDICAL_PATTERNS.put("Laboratory", Arrays.asList(
            "lab", "laboratory", "pathology", "histology", "cytology",
            "microbiology", "serology", "culture"
        ));
        
        // Prescription patterns
        MEDICAL_PATTERNS.put("Prescription", Arrays.asList(
            "prescription", "rx", "medication", "pharmacy", "drug",
            "dosage", "mg", "tablet", "capsule", "syrup"
        ));
        
        // Surgery patterns
        MEDICAL_PATTERNS.put("Surgery", Arrays.asList(
            "surgery", "surgical", "operation", "procedure", "biopsy",
            "endoscopy", "colonoscopy", "laparoscopy"
        ));
        
        // Emergency patterns
        MEDICAL_PATTERNS.put("Emergency", Arrays.asList(
            "emergency", "er", "accident", "trauma", "injury",
            "fracture", "wound", "burn", "critical"
        ));
    }
    
    // Common medical keywords
    private static final Set<String> MEDICAL_KEYWORDS = new HashSet<>(Arrays.asList(
        "diabetes", "hypertension", "cancer", "tumor", "infection", "inflammation",
        "allergy", "asthma", "arthritis", "heart disease", "stroke", "kidney",
        "liver", "lungs", "stomach", "brain", "bones", "joints", "muscles",
        "blood pressure", "pulse rate", "temperature", "weight", "height"
    ));
    
    /**
     * Generate smart tags for a medical file
     */
    public List<String> generateSmartTags(MedicalFile medicalFile) {
        List<String> tags = new ArrayList<>();
        
        // Extract text from file name and description
        String text = extractText(medicalFile);
        
        // Pattern-based tagging
        tags.addAll(patternBasedTagging(text));
        
        // Keyword-based tagging
        tags.addAll(keywordBasedTagging(text));
        
        // Context-based tagging
        tags.addAll(contextBasedTagging(medicalFile));
        
        // Remove duplicates and sort
        tags = new ArrayList<>(new LinkedHashSet<>(tags));
        Collections.sort(tags);
        
        // Limit to reasonable number of tags
        if (tags.size() > 10) {
            tags = tags.subList(0, 10);
        }
        
        return tags;
    }
    
    /**
     * Suggest category based on file content
     */
    public MedicalFile.FileCategory suggestCategory(MedicalFile medicalFile) {
        String text = extractText(medicalFile);
        
        // Check for prescription indicators
        if (containsAny(text, MEDICAL_PATTERNS.get("Prescription"))) {
            return MedicalFile.FileCategory.PRESCRIPTION;
        }
        
        // Check for lab report indicators
        if (containsAny(text, MEDICAL_PATTERNS.get("Laboratory")) || 
            containsAny(text, MEDICAL_PATTERNS.get("Blood Test"))) {
            return MedicalFile.FileCategory.LAB_REPORT;
        }
        
        // Check for imaging indicators
        if (containsAny(text, MEDICAL_PATTERNS.get("Imaging"))) {
            return MedicalFile.FileCategory.IMAGING;
        }
        
        // Default to medical history
        return MedicalFile.FileCategory.MEDICAL_HISTORY;
    }
    
    /**
     * Extract medical entities from text
     */
    public List<MedicalEntity> extractMedicalEntities(String text) {
        List<MedicalEntity> entities = new ArrayList<>();
        
        // Extract dates
        entities.addAll(extractDates(text));
        
        // Extract numbers (lab values, dosages)
        entities.addAll(extractNumbers(text));
        
        // Extract medical terms
        entities.addAll(extractMedicalTerms(text));
        
        return entities;
    }
    
    /**
     * Learn from user feedback to improve tagging
     */
    public void learnFromFeedback(int fileId, List<String> userTags, List<String> autoTags) {
        // Calculate precision and recall
        Set<String> userTagSet = new HashSet<>(userTags);
        Set<String> autoTagSet = new HashSet<>(autoTags);
        
        // True positives
        Set<String> truePositives = new HashSet<>(autoTagSet);
        truePositives.retainAll(userTagSet);
        
        // False positives
        Set<String> falsePositives = new HashSet<>(autoTagSet);
        falsePositives.removeAll(userTagSet);
        
        // False negatives
        Set<String> falseNegatives = new HashSet<>(userTagSet);
        falseNegatives.removeAll(autoTagSet);
        
        // Store feedback for machine learning
        storeTaggingFeedback(fileId, userTags, autoTags, truePositives, falsePositives, falseNegatives);
        
        AuditLogger.logUserAction(0, "TAGGING_FEEDBACK", "MEDICAL_FILE", fileId,
                                String.format("User tags: %s, Auto tags: %s", userTags, autoTags));
    }
    
    /**
     * Get popular tags for a user
     */
    public List<String> getPopularTags(int userId, int limit) {
        List<String> popularTags = new ArrayList<>();
        
        String sql = "SELECT tags FROM medical_files WHERE user_id = ? AND tags IS NOT NULL";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, Integer> tagCounts = new HashMap<>();
                
                while (rs.next()) {
                    String tagsJson = rs.getString("tags");
                    List<String> tags = parseTagsFromJson(tagsJson);
                    
                    for (String tag : tags) {
                        tagCounts.put(tag, tagCounts.getOrDefault(tag, 0) + 1);
                    }
                }
                
                // Sort by frequency and limit
                tagCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .forEach(entry -> popularTags.add(entry.getKey()));
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Get popular tags failed: " + e.getMessage());
        }
        
        return popularTags;
    }
    
    /**
     * Get tag suggestions for input
     */
    public List<String> getTagSuggestions(String input, int userId) {
        List<String> suggestions = new ArrayList<>();
        String inputLower = input.toLowerCase();
        
        // Get user's popular tags
        List<String> popularTags = getPopularTags(userId, 50);
        
        // Filter by input
        for (String tag : popularTags) {
            if (tag.toLowerCase().contains(inputLower)) {
                suggestions.add(tag);
            }
        }
        
        // Add medical keywords that match
        for (String keyword : MEDICAL_KEYWORDS) {
            if (keyword.toLowerCase().contains(inputLower) && !suggestions.contains(keyword)) {
                suggestions.add(keyword);
            }
        }
        
        // Limit suggestions
        if (suggestions.size() > 10) {
            suggestions = suggestions.subList(0, 10);
        }
        
        return suggestions;
    }
    
    // Private helper methods
    
    private String extractText(MedicalFile medicalFile) {
        StringBuilder text = new StringBuilder();
        
        if (medicalFile.getOriginalFileName() != null) {
            text.append(medicalFile.getOriginalFileName()).append(" ");
        }
        
        if (medicalFile.getDescription() != null) {
            text.append(medicalFile.getDescription()).append(" ");
        }
        
        if (medicalFile.getDoctorName() != null) {
            text.append(medicalFile.getDoctorName()).append(" ");
        }
        
        if (medicalFile.getHospitalName() != null) {
            text.append(medicalFile.getHospitalName()).append(" ");
        }
        
        return text.toString().toLowerCase();
    }
    
    private List<String> patternBasedTagging(String text) {
        List<String> tags = new ArrayList<>();
        
        for (Map.Entry<String, List<String>> entry : MEDICAL_PATTERNS.entrySet()) {
            if (containsAny(text, entry.getValue())) {
                tags.add(entry.getKey());
            }
        }
        
        return tags;
    }
    
    private List<String> keywordBasedTagging(String text) {
        List<String> tags = new ArrayList<>();
        
        for (String keyword : MEDICAL_KEYWORDS) {
            if (text.contains(keyword.toLowerCase())) {
                tags.add(keyword);
            }
        }
        
        return tags;
    }
    
    private List<String> contextBasedTagging(MedicalFile medicalFile) {
        List<String> tags = new ArrayList<>();
        
        // Add category as tag
        if (medicalFile.getCategory() != null) {
            tags.add(medicalFile.getCategory().getDisplayName());
        }
        
        // Add doctor name as tag
        if (medicalFile.getDoctorName() != null && !medicalFile.getDoctorName().trim().isEmpty()) {
            tags.add("Dr. " + medicalFile.getDoctorName().trim());
        }
        
        // Add hospital name as tag
        if (medicalFile.getHospitalName() != null && !medicalFile.getHospitalName().trim().isEmpty()) {
            tags.add(medicalFile.getHospitalName().trim());
        }
        
        // Add year-based tag
        if (medicalFile.getUploadDate() != null) {
            tags.add(String.valueOf(medicalFile.getUploadDate().getYear()));
        }
        
        return tags;
    }
    
    private boolean containsAny(String text, List<String> patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    private List<MedicalEntity> extractDates(String text) {
        List<MedicalEntity> dates = new ArrayList<>();
        
        // Simple date pattern matching
        Pattern datePattern = Pattern.compile("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\b");
        java.util.regex.Matcher matcher = datePattern.matcher(text);
        
        while (matcher.find()) {
            MedicalEntity entity = new MedicalEntity();
            entity.setType("DATE");
            entity.setValue(matcher.group());
            entity.setStartPosition(matcher.start());
            entity.setEndPosition(matcher.end());
            dates.add(entity);
        }
        
        return dates;
    }
    
    private List<MedicalEntity> extractNumbers(String text) {
        List<MedicalEntity> numbers = new ArrayList<>();
        
        // Pattern for numbers with units
        Pattern numberPattern = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*(mg|ml|mm|cm|kg|lb|°c|°f|%|bpm)\\b");
        java.util.regex.Matcher matcher = numberPattern.matcher(text);
        
        while (matcher.find()) {
            MedicalEntity entity = new MedicalEntity();
            entity.setType("MEDICAL_VALUE");
            entity.setValue(matcher.group());
            entity.setStartPosition(matcher.start());
            entity.setEndPosition(matcher.end());
            numbers.add(entity);
        }
        
        return numbers;
    }
    
    private List<MedicalEntity> extractMedicalTerms(String text) {
        List<MedicalEntity> terms = new ArrayList<>();
        
        for (String keyword : MEDICAL_KEYWORDS) {
            int index = text.toLowerCase().indexOf(keyword.toLowerCase());
            if (index != -1) {
                MedicalEntity entity = new MedicalEntity();
                entity.setType("MEDICAL_TERM");
                entity.setValue(keyword);
                entity.setStartPosition(index);
                entity.setEndPosition(index + keyword.length());
                terms.add(entity);
            }
        }
        
        return terms;
    }
    
    private List<String> parseTagsFromJson(String tagsJson) {
        List<String> tags = new ArrayList<>();
        if (tagsJson != null && tagsJson.startsWith("[") && tagsJson.endsWith("]")) {
            String content = tagsJson.substring(1, tagsJson.length() - 1);
            String[] tagArray = content.split(",");
            for (String tag : tagArray) {
                String cleanTag = tag.trim().replaceAll("[\"\\[\\]]", "");
                if (!cleanTag.isEmpty()) {
                    tags.add(cleanTag);
                }
            }
        }
        return tags;
    }
    
    private void storeTaggingFeedback(int fileId, List<String> userTags, List<String> autoTags,
                                   Set<String> truePositives, Set<String> falsePositives, Set<String> falseNegatives) {
        // In a real implementation, this would store feedback in a machine learning table
        // For now, we'll just log it
        AuditLogger.logSystemEvent("TAGGING_FEEDBACK", 
                                  String.format("File %d feedback - TP: %s, FP: %s, FN: %s", 
                                               fileId, truePositives, falsePositives, falseNegatives));
    }
    
    /**
     * Medical entity class
     */
    public static class MedicalEntity {
        private String type;
        private String value;
        private int startPosition;
        private int endPosition;
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        
        public int getStartPosition() { return startPosition; }
        public void setStartPosition(int startPosition) { this.startPosition = startPosition; }
        
        public int getEndPosition() { return endPosition; }
        public void setEndPosition(int endPosition) { this.endPosition = endPosition; }
        
        @Override
        public String toString() {
            return String.format("%s: '%s' [%d-%d]", type, value, startPosition, endPosition);
        }
    }
}
