package com.healthvault;

import com.healthvault.config.DatabaseConfig;
import com.healthvault.ui.LoginView;
import com.healthvault.util.AuditLogger;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Main application class for Cryptographically Secure Health-ID Vault.
 * All UI is built programmatically in pure Java — no FXML.
 */
public class HealthVaultApplication extends Application {
    private static final Logger logger = LoggerFactory.getLogger(HealthVaultApplication.class);

    @Override
    public void start(Stage primaryStage) {
        try {
            initializeDatabase();

            LoginView loginView = new LoginView(primaryStage);
            Scene scene = new Scene(loginView.getRoot(), 860, 620);
            scene.getStylesheets().add(
                    getClass().getResource("/css/styles.css").toExternalForm());

            primaryStage.setTitle("Health-ID Vault – Secure Medical Records");
            primaryStage.setScene(scene);
            primaryStage.setResizable(false);
            primaryStage.show();

            AuditLogger.logSystemEvent("APPLICATION_STARTUP");
            logger.info("Health-ID Vault started successfully");

        } catch (SQLException e) {
            logger.error("Database initialization failed", e);
            showErrorDialog("Database Error", "Failed to connect to the database:\n" + e.getMessage());
        }
    }

    private void initializeDatabase() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection()) {
            if (connection == null) {
                throw new SQLException("Failed to establish database connection");
            }
            logger.info("Database connection established successfully");
        }
    }

    private void showErrorDialog(String title, String message) {
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        AuditLogger.logSystemEvent("APPLICATION_SHUTDOWN");
        logger.info("Health-ID Vault shutdown");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
