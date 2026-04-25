package com.healthvault.service;

import com.healthvault.config.DatabaseConfig;
import com.healthvault.crypto.PasswordService;
import com.healthvault.model.User;
import com.healthvault.util.AuditLogger;
import com.healthvault.util.HealthIdGenerator;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * User service for managing patient and doctor accounts
 */
public class UserService {
    
    /**
     * Register a new user
     */
    public User registerUser(String name, String email, String password, 
                            User.UserType userType, String phone, 
                            java.time.LocalDate dateOfBirth, String address, 
                            String emergencyContact) throws Exception {
        
        // Validate input
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (email == null || !isValidEmail(email)) {
            throw new IllegalArgumentException("Valid email is required");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        
        // Check password strength
        PasswordService.PasswordStrength strength = PasswordService.checkPasswordStrength(password);
        if (strength == PasswordService.PasswordStrength.WEAK) {
            throw new IllegalArgumentException("Password is too weak. Please use a stronger password with uppercase, lowercase, numbers, and special characters.");
        }
        
        // Check if email already exists
        if (emailExists(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        
        // Hash password
        String passwordHash = PasswordService.hashPassword(password);
        
        // Generate unique Health ID
        String healthId = HealthIdGenerator.generateHealthId();
        while (healthIdExists(healthId)) {
            healthId = HealthIdGenerator.generateHealthId();
        }
        
        // Generate OTP secret for 2FA
        String otpSecret = PasswordService.generateSecureToken(32);
        
        // Create user object
        User user = new User(name, email, passwordHash, healthId, userType, 
                           phone, dateOfBirth, address, emergencyContact);
        user.setOtpSecret(otpSecret);
        
        // Save to database
        user.setId(saveUserToDatabase(user));
        
        // Log registration
        AuditLogger.logUserAction(user.getId(), "USER_REGISTRATION", "USER", user.getId(), 
                                "User registered with Health ID: " + healthId);
        
        return user;
    }
    
    /**
     * Authenticate user with email and password
     */
    public Optional<User> authenticateUser(String email, String password) {
        String sql = "SELECT * FROM users WHERE email = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, email);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    
                    if (PasswordService.verifyPassword(password, storedHash)) {
                        User user = mapResultSetToUser(rs);
                        
                        // Log successful login
                        AuditLogger.logUserAction(user.getId(), "USER_LOGIN", "USER", user.getId(), 
                                                "User logged in successfully");
                        
                        return Optional.of(user);
                    } else {
                        // Log failed login attempt
                        AuditLogger.logSystemEvent("FAILED_LOGIN_ATTEMPT", "Email: " + email);
                    }
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Authentication failed: " + e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Generate and send OTP for 2FA
     */
    public String generateOTP(int userId) throws Exception {
        String otp = PasswordService.generateOTP();
        
        // In a real application, this would send OTP via SMS or email
        // For demo purposes, we'll just return the OTP
        
        // Log OTP generation
        AuditLogger.logUserAction(userId, "OTP_GENERATED", "USER", userId, 
                                "OTP generated for 2FA");
        
        return otp;
    }
    
    /**
     * Verify OTP for 2FA
     */
    public boolean verifyOTP(int userId, String providedOTP) {
        // In a real application, this would verify against stored OTP
        // For demo purposes, we'll implement a simple time-based verification
        
        String sql = "SELECT otp_secret FROM users WHERE id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // Log OTP verification attempt
                    AuditLogger.logUserAction(userId, "OTP_VERIFICATION", "USER", userId, 
                                            "OTP verification attempted");
                    
                    // For demo: accept any 6-digit OTP
                    return providedOTP != null && providedOTP.matches("\\d{6}");
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "OTP verification failed: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Get user by ID
     */
    public Optional<User> getUserById(int userId) {
        String sql = "SELECT * FROM users WHERE id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Get user by ID failed: " + e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Get user by Health ID
     */
    public Optional<User> getUserByHealthId(String healthId) {
        String sql = "SELECT * FROM users WHERE health_id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, healthId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Get user by Health ID failed: " + e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Update user profile
     */
    public boolean updateUserProfile(User user) {
        String sql = "UPDATE users SET name = ?, phone = ?, address = ?, " +
                    "emergency_contact = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, user.getName());
            stmt.setString(2, user.getPhone());
            stmt.setString(3, user.getAddress());
            stmt.setString(4, user.getEmergencyContact());
            stmt.setInt(5, user.getId());
            
            int rowsUpdated = stmt.executeUpdate();
            
            if (rowsUpdated > 0) {
                AuditLogger.logUserAction(user.getId(), "PROFILE_UPDATE", "USER", user.getId(), 
                                        "User profile updated");
                return true;
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Profile update failed: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Change user password
     */
    public boolean changePassword(int userId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            return false;
        }
        
        // Check password strength
        PasswordService.PasswordStrength strength = PasswordService.checkPasswordStrength(newPassword);
        if (strength == PasswordService.PasswordStrength.WEAK) {
            return false;
        }
        
        // Verify current password
        Optional<User> userOpt = getUserById(userId);
        if (!userOpt.isPresent()) {
            return false;
        }
        
        User user = userOpt.get();
        if (!PasswordService.verifyPassword(currentPassword, user.getPasswordHash())) {
            return false;
        }
        
        // Hash new password
        String newPasswordHash = PasswordService.hashPassword(newPassword);
        
        // Update password
        String sql = "UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, newPasswordHash);
            stmt.setInt(2, userId);
            
            int rowsUpdated = stmt.executeUpdate();
            
            if (rowsUpdated > 0) {
                AuditLogger.logUserAction(userId, "PASSWORD_CHANGE", "USER", userId, 
                                        "Password changed successfully");
                return true;
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Password change failed: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Search users by name or Health ID (for doctors)
     */
    public List<User> searchUsers(String searchTerm, User.UserType userType) {
        List<User> users = new ArrayList<>();
        String sql = "SELECT * FROM users WHERE (name LIKE ? OR health_id LIKE ?) " +
                    "AND user_type = ? AND is_verified = TRUE ORDER BY name";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            String searchPattern = "%" + searchTerm + "%";
            stmt.setString(1, searchPattern);
            stmt.setString(2, searchPattern);
            stmt.setString(3, userType.name());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    users.add(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "User search failed: " + e.getMessage());
        }
        
        return users;
    }
    
    // Private helper methods
    
    private int saveUserToDatabase(User user) throws SQLException {
        String sql = "INSERT INTO users (name, email, password_hash, health_id, user_type, " +
                    "phone, date_of_birth, address, emergency_contact, otp_secret, is_verified) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, user.getName());
            stmt.setString(2, user.getEmail());
            stmt.setString(3, user.getPasswordHash());
            stmt.setString(4, user.getHealthId());
            stmt.setString(5, user.getUserType().name());
            stmt.setString(6, user.getPhone());
            stmt.setDate(7, user.getDateOfBirth() != null ? Date.valueOf(user.getDateOfBirth()) : null);
            stmt.setString(8, user.getAddress());
            stmt.setString(9, user.getEmergencyContact());
            stmt.setString(10, user.getOtpSecret());
            stmt.setBoolean(11, user.isVerified());
            
            int rowsAffected = stmt.executeUpdate();
            
            if (rowsAffected > 0) {
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getInt(1);
                    }
                }
            }
        }
        
        throw new SQLException("Failed to save user to database");
    }
    
    private boolean emailExists(String email) {
        String sql = "SELECT COUNT(*) FROM users WHERE email = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, email);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Email existence check failed: " + e.getMessage());
        }
        
        return false;
    }
    
    private boolean healthIdExists(String healthId) {
        String sql = "SELECT COUNT(*) FROM users WHERE health_id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, healthId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            AuditLogger.logSystemEvent("DATABASE_ERROR", "Health ID existence check failed: " + e.getMessage());
        }
        
        return false;
    }
    
    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getInt("id"));
        user.setName(rs.getString("name"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setHealthId(rs.getString("health_id"));
        user.setUserType(User.UserType.valueOf(rs.getString("user_type")));
        user.setPhone(rs.getString("phone"));
        
        Date dob = rs.getDate("date_of_birth");
        if (dob != null) {
            user.setDateOfBirth(dob.toLocalDate());
        }
        
        user.setAddress(rs.getString("address"));
        user.setEmergencyContact(rs.getString("emergency_contact"));
        user.setOtpSecret(rs.getString("otp_secret"));
        user.setVerified(rs.getBoolean("is_verified"));
        
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            user.setCreatedAt(created.toLocalDateTime());
        }
        
        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) {
            user.setUpdatedAt(updated.toLocalDateTime());
        }
        
        return user;
    }
    
    private boolean isValidEmail(String email) {
        return email != null && email.matches("^[A-Za-z0-9+_.-]+@(.+)$");
    }
}
