package com.healthvault.service;

import com.healthvault.config.DatabaseConfig;
import com.healthvault.crypto.EncryptionService;
import com.healthvault.model.MedicalFile;
import com.healthvault.util.AuditLogger;
import com.healthvault.util.HealthIdGenerator;

import javax.crypto.SecretKey;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * File vault service for managing encrypted medical files
 */
public class FileVaultService {
    private static final String VAULT_DIRECTORY = "vault";
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    
    static {
        // Create vault directory if it doesn't exist
        Path vaultPath = Paths.get(VAULT_DIRECTORY);
        if (!Files.exists(vaultPath)) {
            try {
                Files.createDirectories(vaultPath);
            } catch (IOException e) {
                System.err.println("Failed to create vault directory: " + e.getMessage());
            }
        }
    }
    
    /**
     * Upload and encrypt a medical file
     */
    public MedicalFile uploadFile(int userId, File inputFile, MedicalFile.FileCategory category, 
                                String description, String doctorName, String hospitalName, 
                                LocalDate uploadDate, List<String> tags, SecretKey userKey) throws Exception {
        
        // Validate input
        if (inputFile == null || !inputFile.exists()) {
            throw new IllegalArgumentException("File does not exist");
        }
        
        if (inputFile.length() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum limit of 50MB");
        }
        
        // Generate file hash for integrity verification
        byte[] fileData = Files.readAllBytes(inputFile.toPath());
        String fileHash = EncryptionService.generateHash(fileData);
        
        // Generate encrypted file name
        String encryptedFileName = HealthIdGenerator.generateFileReference() + ".enc";
        Path encryptedFilePath = Paths.get(VAULT_DIRECTORY, encryptedFileName);
        
        // Encrypt and save file
        EncryptionService.encryptAndSaveFile(inputFile, encryptedFilePath.toFile(), userKey);
        
        // Get file information
        String originalFileName = inputFile.getName();
        String fileType = getFileExtension(originalFileName);
        long fileSize = inputFile.length();
        
        // Create medical file object
        MedicalFile medicalFile = new MedicalFile(
                userId, originalFileName, fileType, fileSize,
                encryptedFilePath.toString(), fileHash, category,
                description, doctorName, hospitalName, uploadDate, tags
        );
        
        // Save to database
        medicalFile.setId(saveFileToDatabase(medicalFile));
        
        // Log file upload
        AuditLogger.logUserAction(userId, "FILE_UPLOAD", "MEDICAL_FILE", medicalFile.getId(),
                                String.format("Uploaded file: %s (%s)", originalFileName, category.getDisplayName()));
        
        return medicalFile;
    }
    
    /**
     * Download and decrypt a medical file
     */
    public Optional<byte[]> downloadFile(int userId, int fileId, SecretKey userKey) throws Exception {
        MedicalFile medicalFile = getFileById(fileId);
        
        if (medicalFile == null) {
            return Optional.empty();
        }
        
        // Check access permissions
        if (!hasFileAccess(userId, fileId)) {
            AuditLogger.logSecurityEvent("UNAUTHORIZED_FILE_ACCESS", 
                                      String.format("User %d attempted to access file %d", userId, fileId), 
                                      null);
            throw new SecurityException("Access denied to this file");
        }
        
        // Decrypt file
        File encryptedFile = new File(medicalFile.getEncryptedPath());
        if (!encryptedFile.exists()) {
            throw new FileNotFoundException("Encrypted file not found");
        }
        
        byte[] decryptedData = EncryptionService.loadAndDecryptFile(encryptedFile, userKey);
        
        // Verify file integrity
        String actualHash = EncryptionService.generateHash(decryptedData);
        if (!actualHash.equals(medicalFile.getFileHash())) {
            AuditLogger.logSecurityEvent("FILE_INTEGRITY_CHECK_FAILED", 
                                      String.format("File %d integrity check failed", fileId), 
                                      null);
            throw new SecurityException("File integrity check failed");
        }
        
        // Log file download
        AuditLogger.logFileAccess(userId, fileId, "DOWNLOADED", null);
        
        return Optional.of(decryptedData);
    }
    
    /**
     * Get file by ID
     */
    public MedicalFile getFileById(int fileId) {
        String sql = "SELECT * FROM medical_files WHERE id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, fileId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToMedicalFile(rs);
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Get file by ID failed: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Get files for a user
     */
    public List<MedicalFile> getUserFiles(int userId, MedicalFile.FileCategory category, 
                                         String searchTerm, LocalDate startDate, LocalDate endDate) {
        List<MedicalFile> files = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM medical_files WHERE user_id = ?"
        );
        
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        
        if (category != null) {
            sql.append(" AND category = ?");
            parameters.add(category.name());
        }
        
        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            sql.append(" AND (original_file_name LIKE ? OR description LIKE ? OR doctor_name LIKE ? OR hospital_name LIKE ?)");
            String searchPattern = "%" + searchTerm + "%";
            for (int i = 0; i < 4; i++) {
                parameters.add(searchPattern);
            }
        }
        
        if (startDate != null) {
            sql.append(" AND upload_date >= ?");
            parameters.add(Date.valueOf(startDate));
        }
        
        if (endDate != null) {
            sql.append(" AND upload_date <= ?");
            parameters.add(Date.valueOf(endDate));
        }
        
        sql.append(" ORDER BY created_at DESC");
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            
            for (int i = 0; i < parameters.size(); i++) {
                stmt.setObject(i + 1, parameters.get(i));
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    files.add(mapResultSetToMedicalFile(rs));
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Get user files failed: " + e.getMessage());
        }
        
        return files;
    }
    
    /**
     * Delete a medical file
     */
    public boolean deleteFile(int userId, int fileId) {
        MedicalFile medicalFile = getFileById(fileId);
        
        if (medicalFile == null || medicalFile.getUserId() != userId) {
            return false;
        }
        
        // Delete encrypted file from disk
        try {
            File encryptedFile = new File(medicalFile.getEncryptedPath());
            if (encryptedFile.exists()) {
                encryptedFile.delete();
            }
        } catch (Exception e) {
            AuditLogger.logSystemEvent("FILE_DELETION_ERROR", 
                                    "Failed to delete encrypted file: " + e.getMessage());
        }
        
        // Delete from database
        String sql = "DELETE FROM medical_files WHERE id = ? AND user_id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, fileId);
            stmt.setInt(2, userId);
            
            int rowsDeleted = stmt.executeUpdate();
            
            if (rowsDeleted > 0) {
                AuditLogger.logUserAction(userId, "FILE_DELETE", "MEDICAL_FILE", fileId,
                                        String.format("Deleted file: %s", medicalFile.getOriginalFileName()));
                return true;
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "File deletion failed: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Update file metadata
     */
    public boolean updateFileMetadata(int userId, int fileId, String description, 
                                    MedicalFile.FileCategory category, List<String> tags) {
        MedicalFile medicalFile = getFileById(fileId);
        
        if (medicalFile == null || medicalFile.getUserId() != userId) {
            return false;
        }
        
        String sql = "UPDATE medical_files SET description = ?, category = ?, tags = ?, " +
                    "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, description);
            stmt.setString(2, category.name());
            
            // Convert tags list to JSON string
            String tagsJson = tags != null ? tags.toString() : null;
            stmt.setString(3, tagsJson);
            
            stmt.setInt(4, fileId);
            stmt.setInt(5, userId);
            
            int rowsUpdated = stmt.executeUpdate();
            
            if (rowsUpdated > 0) {
                AuditLogger.logUserAction(userId, "FILE_METADATA_UPDATE", "MEDICAL_FILE", fileId,
                                        String.format("Updated metadata for file: %s", medicalFile.getOriginalFileName()));
                return true;
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "File metadata update failed: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Get file statistics for a user
     */
    public FileStatistics getFileStatistics(int userId) {
        String sql = "SELECT " +
                    "COUNT(*) as total_files, " +
                    "SUM(file_size) as total_size, " +
                    "COUNT(CASE WHEN category = 'PRESCRIPTION' THEN 1 END) as prescriptions, " +
                    "COUNT(CASE WHEN category = 'LAB_REPORT' THEN 1 END) as lab_reports, " +
                    "COUNT(CASE WHEN category = 'IMAGING' THEN 1 END) as imaging, " +
                    "COUNT(CASE WHEN category = 'MEDICAL_HISTORY' THEN 1 END) as medical_history " +
                    "FROM medical_files WHERE user_id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new FileStatistics(
                        rs.getInt("total_files"),
                        rs.getLong("total_size"),
                        rs.getInt("prescriptions"),
                        rs.getInt("lab_reports"),
                        rs.getInt("imaging"),
                        rs.getInt("medical_history")
                    );
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Get file statistics failed: " + e.getMessage());
        }
        
        return new FileStatistics();
    }
    
    /**
     * Check if user has access to file
     */
    private boolean hasFileAccess(int userId, int fileId) {
        String sql = "SELECT COUNT(*) FROM medical_files WHERE id = ? AND user_id = ? " +
                    "UNION " +
                    "SELECT COUNT(*) FROM access_control ac " +
                    "JOIN medical_files mf ON ac.file_id = mf.id " +
                    "WHERE ac.file_id = ? AND ac.shared_with_user_id = ? " +
                    "AND ac.is_active = TRUE AND (ac.expires_at IS NULL OR ac.expires_at > NOW())";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, fileId);
            stmt.setInt(2, userId);
            stmt.setInt(3, fileId);
            stmt.setInt(4, userId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                int totalAccess = 0;
                while (rs.next()) {
                    totalAccess += rs.getInt(1);
                }
                return totalAccess > 0;
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "File access check failed: " + e.getMessage());
        }
        
        return false;
    }
    
    // Private helper methods
    
    private int saveFileToDatabase(MedicalFile medicalFile) throws SQLException {
        String sql = "INSERT INTO medical_files (user_id, file_name, original_file_name, file_type, " +
                    "file_size, encrypted_path, file_hash, category, description, doctor_name, " +
                    "hospital_name, upload_date, tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setInt(1, medicalFile.getUserId());
            stmt.setString(2, medicalFile.getFileName());
            stmt.setString(3, medicalFile.getOriginalFileName());
            stmt.setString(4, medicalFile.getFileType());
            stmt.setLong(5, medicalFile.getFileSize());
            stmt.setString(6, medicalFile.getEncryptedPath());
            stmt.setString(7, medicalFile.getFileHash());
            stmt.setString(8, medicalFile.getCategory().name());
            stmt.setString(9, medicalFile.getDescription());
            stmt.setString(10, medicalFile.getDoctorName());
            stmt.setString(11, medicalFile.getHospitalName());
            stmt.setDate(12, medicalFile.getUploadDate() != null ? Date.valueOf(medicalFile.getUploadDate()) : null);
            
            // Convert tags to JSON string
            String tagsJson = medicalFile.getTags() != null ? medicalFile.getTags().toString() : null;
            stmt.setString(13, tagsJson);
            
            int rowsAffected = stmt.executeUpdate();
            
            if (rowsAffected > 0) {
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getInt(1);
                    }
                }
            }
        }
        
        throw new SQLException("Failed to save medical file to database");
    }
    
    private MedicalFile mapResultSetToMedicalFile(ResultSet rs) throws SQLException {
        MedicalFile medicalFile = new MedicalFile();
        medicalFile.setId(rs.getInt("id"));
        medicalFile.setUserId(rs.getInt("user_id"));
        medicalFile.setFileName(rs.getString("file_name"));
        medicalFile.setOriginalFileName(rs.getString("original_file_name"));
        medicalFile.setFileType(rs.getString("file_type"));
        medicalFile.setFileSize(rs.getLong("file_size"));
        medicalFile.setEncryptedPath(rs.getString("encrypted_path"));
        medicalFile.setFileHash(rs.getString("file_hash"));
        medicalFile.setCategory(MedicalFile.FileCategory.valueOf(rs.getString("category")));
        medicalFile.setDescription(rs.getString("description"));
        medicalFile.setDoctorName(rs.getString("doctor_name"));
        medicalFile.setHospitalName(rs.getString("hospital_name"));
        
        Date uploadDate = rs.getDate("upload_date");
        if (uploadDate != null) {
            medicalFile.setUploadDate(uploadDate.toLocalDate());
        }
        
        // Parse tags from JSON (simplified for demo)
        String tagsJson = rs.getString("tags");
        if (tagsJson != null && !tagsJson.isEmpty()) {
            // In a real implementation, use a proper JSON parser
            medicalFile.setTags(parseTagsFromJson(tagsJson));
        }
        
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            medicalFile.setCreatedAt(created.toLocalDateTime());
        }
        
        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) {
            medicalFile.setUpdatedAt(updated.toLocalDateTime());
        }
        
        return medicalFile;
    }
    
    private List<String> parseTagsFromJson(String tagsJson) {
        // Simplified tag parsing - in production, use Jackson or similar
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
    
    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.lastIndexOf('.') == -1) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
    
    /**
     * File statistics class
     */
    public static class FileStatistics {
        private final int totalFiles;
        private final long totalSize;
        private final int prescriptions;
        private final int labReports;
        private final int imaging;
        private final int medicalHistory;
        
        public FileStatistics() {
            this(0, 0, 0, 0, 0, 0);
        }
        
        public FileStatistics(int totalFiles, long totalSize, int prescriptions, 
                           int labReports, int imaging, int medicalHistory) {
            this.totalFiles = totalFiles;
            this.totalSize = totalSize;
            this.prescriptions = prescriptions;
            this.labReports = labReports;
            this.imaging = imaging;
            this.medicalHistory = medicalHistory;
        }
        
        // Getters
        public int getTotalFiles() { return totalFiles; }
        public long getTotalSize() { return totalSize; }
        public int getPrescriptions() { return prescriptions; }
        public int getLabReports() { return labReports; }
        public int getImaging() { return imaging; }
        public int getMedicalHistory() { return medicalHistory; }
        
        public String getFormattedTotalSize() {
            if (totalSize < 1024) {
                return totalSize + " B";
            } else if (totalSize < 1024 * 1024) {
                return String.format("%.1f KB", totalSize / 1024.0);
            } else if (totalSize < 1024 * 1024 * 1024) {
                return String.format("%.1f MB", totalSize / (1024.0 * 1024.0));
            } else {
                return String.format("%.1f GB", totalSize / (1024.0 * 1024.0 * 1024.0));
            }
        }
    }
}
