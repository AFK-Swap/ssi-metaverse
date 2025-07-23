package com.ssi.verification;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SSIVerificationPlugin extends JavaPlugin implements Listener {
    
    private static final String INTEGRATION_URL = "http://localhost:8080";
    private Map<String, VerificationSession> verificationSessions = new HashMap<>();
    private Map<String, Boolean> verifiedPlayers = new HashMap<>();
    
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SSI Verification Plugin enabled!");
        getLogger().info("Integration server should be running on: " + INTEGRATION_URL);
        
        // Check if integration server is running
        checkIntegrationServer();
    }
    
    @Override
    public void onDisable() {
        getLogger().info("SSI Verification Plugin disabled!");
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }
        
        Player player = (Player) sender;
        
        switch (command.getName().toLowerCase()) {
            case "verify":
                handleVerifyCommand(player);
                return true;
                
            case "ssiverify":
                if (args.length == 0) {
                    handleSSIVerifyCommand(player, player.getName());
                } else {
                    handleSSIVerifyCommand(player, args[0]);
                }
                return true;
        }
        
        return false;
    }
    
    private void handleVerifyCommand(Player player) {
        String playerName = player.getName();
        
        // Check if already verified
        if (verifiedPlayers.getOrDefault(playerName, false)) {
            player.sendMessage(ChatColor.GREEN + "✓ You are already verified!");
            return;
        }
        
        // Check if verification in progress
        VerificationSession currentSession = verificationSessions.get(playerName);
        if (currentSession != null && !currentSession.isExpired()) {
            player.sendMessage(ChatColor.YELLOW + "Verification already in progress...");
            if (currentSession.qrUrl != null) {
                player.sendMessage(ChatColor.AQUA + "QR Code: " + ChatColor.BLUE + currentSession.qrUrl);
            }
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + "=== SSI Identity Verification ===");
        player.sendMessage(ChatColor.YELLOW + "Starting verification process...");
        player.sendMessage(ChatColor.GRAY + "You will need an SSI wallet with valid credentials.");
        
        // Start verification asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                startVerificationProcess(player);
            } catch (Exception e) {
                getLogger().severe("Verification failed for " + playerName + ": " + e.getMessage());
                player.sendMessage(ChatColor.RED + "Verification system unavailable. Try again later.");
            }
        });
    }
    
    private void handleSSIVerifyCommand(Player sender, String targetPlayerName) {
        CompletableFuture.runAsync(() -> {
            try {
                String response = makeHttpRequest("GET", INTEGRATION_URL + "/status/" + targetPlayerName, null);
                
                // Parse response (simple parsing)
                boolean isVerified = response.contains("\"verified\":true");
                
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(ChatColor.GOLD + "=== Verification Status ===");
                    sender.sendMessage(ChatColor.WHITE + "Player: " + ChatColor.YELLOW + targetPlayerName);
                    sender.sendMessage(ChatColor.WHITE + "Status: " + 
                        (isVerified ? ChatColor.GREEN + "✓ VERIFIED" : ChatColor.RED + "✗ NOT VERIFIED"));
                });
                
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(ChatColor.RED + "Failed to check verification status");
                });
            }
        });
    }
    
    private void startVerificationProcess(Player player) throws Exception {
        String playerName = player.getName();
        
        // Create request body
        String requestBody = String.format("{\"playerName\":\"%s\"}", playerName);
        
        // Make request to integration server
        String response = makeHttpRequest("POST", INTEGRATION_URL + "/verify-player", requestBody);
        
        // Parse response (simple parsing for now)
        if (response.contains("\"success\":true")) {
            // Extract QR URL
            String qrUrl = extractJsonValue(response, "qrUrl");
            String sessionId = extractJsonValue(response, "sessionId");
            
            if (qrUrl != null && sessionId != null) {
                // Create session
                VerificationSession session = new VerificationSession();
                session.playerName = playerName;
                session.sessionId = sessionId;
                session.qrUrl = qrUrl;
                session.startTime = System.currentTimeMillis();
                
                verificationSessions.put(playerName, session);
                
                // Send messages to player on main thread
                Bukkit.getScheduler().runTask(this, () -> {
                    player.sendMessage(ChatColor.GREEN + "✓ QR code generated!");
                    player.sendMessage(ChatColor.AQUA + "Scan this QR with your SSI wallet:");
                    player.sendMessage(ChatColor.BLUE + "" + ChatColor.BOLD + qrUrl);
                    player.sendMessage(ChatColor.GRAY + "Or visit the URL in your browser and scan with your phone");
                });
                
                // Start monitoring verification status
                startVerificationMonitoring(player, session);
                
            } else {
                Bukkit.getScheduler().runTask(this, () -> {
                    player.sendMessage(ChatColor.RED + "Failed to generate QR code");
                });
            }
            
        } else {
            String message = extractJsonValue(response, "message");
            final String errorMessage = message != null ? message : "Verification failed to start";
            
            Bukkit.getScheduler().runTask(this, () -> {
                player.sendMessage(ChatColor.RED + "✗ " + errorMessage);
            });
        }
    }
    
    private void startVerificationMonitoring(Player player, VerificationSession session) {
        // Check verification status every 3 seconds
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            private int attempts = 0;
            private final int maxAttempts = 100; // 5 minutes
            
            @Override
            public void run() {
                attempts++;
                
                try {
                    String response = makeHttpRequest("GET", INTEGRATION_URL + "/status/" + session.playerName, null);
                    
                    if (response.contains("\"verified\":true")) {
                        // Verification successful!
                        verifiedPlayers.put(session.playerName, true);
                        verificationSessions.remove(session.playerName);
                        
                        Bukkit.getScheduler().runTask(SSIVerificationPlugin.this, () -> {
                            player.sendMessage(ChatColor.GREEN + "✓ Identity verification completed successfully!");
                            
                            // Apply verified benefits
                            applyVerifiedBenefits(player);
                            
                            // Broadcast to server
                            Bukkit.broadcastMessage(ChatColor.GOLD + player.getName() + 
                                ChatColor.GREEN + " has been verified with SSI credentials!");
                        });
                        
                        // Stop monitoring
                        return;
                    }
                    
                } catch (Exception e) {
                    getLogger().warning("Failed to check verification status: " + e.getMessage());
                }
                
                // Stop monitoring after max attempts
                if (attempts >= maxAttempts) {
                    verificationSessions.remove(session.playerName);
                    
                    Bukkit.getScheduler().runTask(SSIVerificationPlugin.this, () -> {
                        player.sendMessage(ChatColor.RED + "Verification timeout. Try /verify again.");
                    });
                }
            }
        }, 60L, 60L); // Start after 3 seconds, repeat every 3 seconds
    }
    
    private void applyVerifiedBenefits(Player player) {
        // Give glowing effect
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
            "effect give " + player.getName() + " minecraft:glowing 999999 0 true");
        
        player.sendMessage(ChatColor.GREEN + "✓ You now have verified player benefits!");
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Check if player is verified
        if (verifiedPlayers.getOrDefault(player.getName(), false)) {
            player.sendMessage(ChatColor.GREEN + "Welcome back! You are verified.");
            applyVerifiedBenefits(player);
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                player.sendMessage(ChatColor.YELLOW + "Use " + ChatColor.GOLD + "/verify " + 
                    ChatColor.YELLOW + "to verify your identity with SSI credentials!");
            }, 60L); // 3 seconds delay
        }
    }
    
    private void checkIntegrationServer() {
        CompletableFuture.runAsync(() -> {
            try {
                makeHttpRequest("GET", INTEGRATION_URL + "/status/test", null);
                getLogger().info("✓ Integration server is running");
            } catch (Exception e) {
                getLogger().warning("⚠ Integration server not accessible: " + e.getMessage());
                getLogger().warning("  Please run: cd plugins/SSIVerification && ./start.sh");
            }
        });
    }
    
    private String makeHttpRequest(String method, String urlString, String requestBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        
        if (requestBody != null) {
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes());
            }
        }
        
        int responseCode = connection.getResponseCode();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                responseCode >= 200 && responseCode < 300 ? 
                connection.getInputStream() : connection.getErrorStream()))) {
            
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            if (responseCode >= 200 && responseCode < 300) {
                return response.toString();
            } else {
                throw new Exception("HTTP " + responseCode + ": " + response.toString());
            }
        }
    }
    
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return null;
        
        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return null;
        
        return json.substring(startIndex, endIndex);
    }
    
    private static class VerificationSession {
        String playerName;
        String sessionId;
        String qrUrl;
        long startTime;
        
        boolean isExpired() {
            return System.currentTimeMillis() - startTime > 600000; // 10 minutes
        }
    }
}