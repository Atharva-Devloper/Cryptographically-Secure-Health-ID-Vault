package com.healthvault.ui;

import com.healthvault.model.User;
import com.healthvault.service.UserService;
import com.healthvault.util.AuditLogger;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.util.Optional;

/**
 * Login screen built entirely in pure Java — no FXML.
 */
public class LoginView {

    private final VBox root;
    private final Stage stage;

    // Form controls
    private final TextField emailField      = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField otpField         = new TextField();
    private final Button loginButton         = new Button("Login");
    private final Button generateOTPButton   = new Button("Generate OTP");
    private final Label  statusLabel         = new Label();

    private final UserService userService = new UserService();
    private User currentUser;
    private String generatedOTP;

    public LoginView(Stage stage) {
        this.stage = stage;
        this.root  = buildRoot();
        wireEvents();
        validateFields();
    }

    public VBox getRoot() { return root; }

    // ─── Layout ────────────────────────────────────────────────────────────────

    private VBox buildRoot() {
        VBox container = new VBox(28);
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(40));
        container.getStyleClass().add("main-container");
        container.getChildren().addAll(buildHeader(), buildCard(), buildFooter());
        return container;
    }

    private VBox buildHeader() {
        Label title = new Label("🏥  Health-ID Vault");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 30));
        title.getStyleClass().add("header-label");

        Label subtitle = new Label("Secure Medical Records Management");
        subtitle.getStyleClass().add("user-info");
        subtitle.setFont(Font.font(14));

        VBox header = new VBox(6, title, subtitle);
        header.setAlignment(Pos.CENTER);
        header.getStyleClass().add("card");
        return header;
    }

    private VBox buildCard() {
        Label cardTitle = new Label("Sign In");
        cardTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
        cardTitle.getStyleClass().add("card-title");

        // Email row
        Label emailLbl = styledLabel("Email:");
        emailField.setPromptText("Enter your email");
        emailField.setPrefWidth(280);
        emailField.getStyleClass().add("text-field");

        // Password row
        Label passLbl = styledLabel("Password:");
        passwordField.setPromptText("Enter your password");
        passwordField.setPrefWidth(280);
        passwordField.getStyleClass().add("password-field");

        // OTP row
        Label otpLbl = styledLabel("OTP (2FA):");
        otpField.setPromptText("Enter 6-digit OTP");
        otpField.setPrefWidth(280);
        otpField.getStyleClass().add("text-field");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        grid.addRow(0, emailLbl,  emailField);
        grid.addRow(1, passLbl,   passwordField);
        grid.addRow(2, otpLbl,    otpField);

        // Buttons
        loginButton.setPrefWidth(120);
        loginButton.getStyleClass().addAll("button", "primary");

        generateOTPButton.setPrefWidth(140);
        generateOTPButton.getStyleClass().addAll("button", "secondary");

        HBox buttons = new HBox(12, loginButton, generateOTPButton);
        buttons.setAlignment(Pos.CENTER);

        // Links
        Hyperlink forgotLink    = new Hyperlink("Forgot Password?");
        Hyperlink registerLink  = new Hyperlink("Create Account");
        forgotLink.setOnAction(e   -> showInfo("Forgot Password", "Password reset is not yet implemented."));
        registerLink.setOnAction(e -> showInfo("Create Account",  "Registration is not yet implemented."));

        HBox links = new HBox(24, forgotLink, registerLink);
        links.setAlignment(Pos.CENTER);

        // Status
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(400);

        VBox card = new VBox(16, cardTitle, grid, buttons, links, statusLabel);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(20, 60, 20, 60));
        card.getStyleClass().add("card");
        return card;
    }

    private VBox buildFooter() {
        Label copy  = small("© 2024 Health-ID Vault. All rights reserved.");
        Label enc   = small("Built with AES-256 Encryption & Secure Authentication");
        VBox footer = new VBox(4, copy, enc);
        footer.setAlignment(Pos.CENTER);
        return footer;
    }

    // ─── Events ────────────────────────────────────────────────────────────────

    private void wireEvents() {
        loginButton.setOnAction(e       -> handleLogin());
        generateOTPButton.setOnAction(e -> handleGenerateOTP());

        emailField.textProperty().addListener((o, ov, nv)    -> validateFields());
        passwordField.textProperty().addListener((o, ov, nv) -> validateFields());
        otpField.textProperty().addListener((o, ov, nv)      -> validateFields());
    }

    private void handleLogin() {
        String email    = emailField.getText().trim();
        String password = passwordField.getText();
        String otp      = otpField.getText().trim();

        clearStatus();

        if (email.isEmpty() || password.isEmpty()) { showStatus("Please enter email and password", "error");  return; }
        if (otp.isEmpty())                          { showStatus("Please generate and enter OTP",   "error");  return; }

        try {
            Optional<User> userOpt = userService.authenticateUser(email, password);
            if (!userOpt.isPresent()) {
                showStatus("Invalid email or password", "error");
                AuditLogger.logAuthenticationEvent(email, "LOGIN", null, false);
                return;
            }
            currentUser = userOpt.get();

            if (!userService.verifyOTP(currentUser.getId(), otp)) {
                showStatus("Invalid OTP", "error");
                AuditLogger.logSecurityEvent("INVALID_OTP",
                        "User " + email + " provided invalid OTP", null);
                return;
            }

            showStatus("Login successful! Redirecting…", "success");
            AuditLogger.logAuthenticationEvent(email, "LOGIN", null, true);
            navigateToDashboard();

        } catch (Exception e) {
            showStatus("Login failed: " + e.getMessage(), "error");
            AuditLogger.logSystemEvent("LOGIN_ERROR", "Login failed: " + e.getMessage());
        }
    }

    private void handleGenerateOTP() {
        String email    = emailField.getText().trim();
        String password = passwordField.getText();

        if (email.isEmpty() || password.isEmpty()) {
            showStatus("Please enter email and password first", "error");
            return;
        }

        try {
            Optional<User> userOpt = userService.authenticateUser(email, password);
            if (!userOpt.isPresent()) { showStatus("Invalid email or password", "error"); return; }

            currentUser  = userOpt.get();
            generatedOTP = userService.generateOTP(currentUser.getId());

            showStatus("OTP generated: " + generatedOTP +
                    " (In production this would be sent via SMS/Email)", "success");
            AuditLogger.logUserAction(currentUser.getId(), "OTP_GENERATED", "USER",
                    currentUser.getId(), "OTP generated for login");

            otpField.setDisable(false);
            loginButton.setDisable(false);

        } catch (Exception e) {
            showStatus("Failed to generate OTP: " + e.getMessage(), "error");
            AuditLogger.logSystemEvent("OTP_GENERATION_ERROR", "OTP generation failed: " + e.getMessage());
        }
    }

    private void navigateToDashboard() {
        DashboardView dashboardView = new DashboardView(stage, currentUser);
        javafx.scene.Scene scene = new javafx.scene.Scene(dashboardView.getRoot(), 1280, 820);
        scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());

        stage.setTitle("Health-ID Vault – Dashboard");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setMaximized(true);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private void validateFields() {
        boolean hasEmail    = !emailField.getText().trim().isEmpty();
        boolean hasPassword = !passwordField.getText().isEmpty();
        boolean hasOTP      = !otpField.getText().trim().isEmpty();

        generateOTPButton.setDisable(!hasEmail || !hasPassword);
        loginButton.setDisable(!hasEmail || !hasPassword || !hasOTP || generatedOTP == null);
    }

    private void clearStatus() {
        statusLabel.setText("");
        statusLabel.getStyleClass().removeAll("status-success", "status-error", "status-warning");
    }

    private void showStatus(String message, String type) {
        clearStatus();
        statusLabel.setText(message);
        statusLabel.getStyleClass().add("status-" + type);
    }

    private static Label styledLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        l.setFont(Font.font(null, FontWeight.BOLD, 13));
        return l;
    }

    private static Label small(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("user-info");
        l.setFont(Font.font(10));
        return l;
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title); a.setHeaderText(null); a.setContentText(msg);
        a.showAndWait();
    }
}
