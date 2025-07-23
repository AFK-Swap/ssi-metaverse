package com.ssi.verification;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SimpleSSIPlugin extends JavaPlugin {
    
    private OkHttpClient httpClient;
    private Gson gson;
    private String acapyAdminUrl;
    private String credentialDefinitionId;
    private final ConcurrentHashMap<String, Boolean> verifiedPlayers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        acapyAdminUrl = getConfig().getString("acapy.admin-url", "http://localhost:8021");
        credentialDefinitionId = getConfig().getString("acapy.credential-definition-id", "");
        
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        
        gson = new Gson();
        
        // Note: Credential definition ID is no longer required for flexible verification
        // The plugin now accepts credentials from any issuer with required attributes
        
        getLogger().info("Simple SSI Plugin enabled! Using flexible attribute-only verification.");
    }
    
    // Credential definition discovery is no longer needed for flexible verification
    // The plugin now accepts any credential containing required attributes (department, age)

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if ("verify".equals(command.getName())) {
            handleVerify(player);
            return true;
        } else if ("ssiverify".equals(command.getName())) {
            String targetPlayer = args.length > 0 ? args[0] : player.getName();
            handleSSIVerify(player, targetPlayer);
            return true;
        }
        return false;
    }
    
    private void handleVerify(Player player) {
        if (verifiedPlayers.getOrDefault(player.getName(), false)) {
            player.sendMessage(Component.text("✓ Already verified!", NamedTextColor.GREEN));
            return;
        }
        
        player.sendMessage(Component.text("Creating QR code...", NamedTextColor.YELLOW));
        CompletableFuture.runAsync(() -> createVerification(player));
    }
    
    private void createVerification(Player player) {
        try {
            getLogger().info("Creating verification for player: " + player.getName());
            
            // Use ssi-tutorial verifier API (simple approach)
            JsonObject request = new JsonObject();
            request.addProperty("label", "Minecraft-Server-" + player.getName());
            request.addProperty("alias", "minecraft-player-" + player.getName());
            
            RequestBody body = RequestBody.create(request.toString(), MediaType.get("application/json"));
            Request httpRequest = new Request.Builder()
                .url("http://localhost:4002/v2/create-invitation")
                .post(body)
                .build();
            
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "No response body";
                getLogger().info("ACA-Py response: " + response.code() + " - " + responseBody);
                
                if (!response.isSuccessful()) {
                    sendMessage(player, Component.text("Failed to create invitation: " + responseBody, NamedTextColor.RED));
                    return;
                }
                
                JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();
                
                if (!responseJson.has("invitation_url")) {
                    sendMessage(player, Component.text("Invalid response from verification service", NamedTextColor.RED));
                    return;
                }
                
                String invitationUrl = responseJson.get("invitation_url").getAsString();
                String connectionId = responseJson.get("connection_id").getAsString();
                getLogger().info("Generated invitation URL: " + invitationUrl);
                getLogger().info("Connection ID: " + connectionId);
                
                // Give QR map
                Bukkit.getScheduler().runTask(this, () -> {
                    giveQRMap(player, invitationUrl);
                    player.sendMessage(Component.text("✓ QR Code created! Scan with your SSI wallet.", NamedTextColor.GREEN));
                });
                
                // Start monitoring this specific connection
                monitorConnection(connectionId, player);
                
            }
        } catch (Exception e) {
            getLogger().severe("Verification failed for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            sendMessage(player, Component.text("System error: " + e.getMessage(), NamedTextColor.RED));
        }
    }
    
    private void giveQRMap(Player player, String qrData) {
        try {
            QRCodeWriter qrWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrWriter.encode(qrData, BarcodeFormat.QR_CODE, 128, 128);
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
            
            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta mapMeta = (MapMeta) mapItem.getItemMeta();
            
            MapView mapView = Bukkit.createMap(player.getWorld());
            mapView.getRenderers().clear();
            
            mapView.addRenderer(new MapRenderer() {
                @Override
                public void render(MapView map, MapCanvas canvas, Player player) {
                    for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                            if (x < qrImage.getWidth() && y < qrImage.getHeight()) {
                                int rgb = qrImage.getRGB(x, y);
                                byte color = (rgb == -1) ? (byte) 0 : (byte) 119;
                                canvas.setPixel(x, y, color);
                            }
                        }
                    }
                }
            });
            
            mapMeta.setMapView(mapView);
            mapMeta.setDisplayName("SSI Verification QR Code");
            mapItem.setItemMeta(mapMeta);
            
            player.getInventory().addItem(mapItem);
            
        } catch (Exception e) {
            getLogger().warning("Failed to create QR map: " + e.getMessage());
        }
    }
    
    private void monitorConnection(String connectionId, Player player) {
        final int[] taskId = new int[1];
        
        taskId[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            private int attempts = 0;
            private boolean proofSent = false;
            
            @Override
            public void run() {
                attempts++;
                
                if (attempts > 40) { // 2 minutes timeout
                    sendMessage(player, Component.text("Verification timeout", NamedTextColor.RED));
                    Bukkit.getScheduler().cancelTask(taskId[0]);
                    return;
                }
                
                try {
                    // Check specific connection status
                    Request request = new Request.Builder()
                        .url("http://localhost:4002/v2/connections?connectionId=" + connectionId)
                        .build();
                    
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String responseBody = response.body().string();
                            getLogger().info("Connection status check #" + attempts);
                            
                            JsonObject connectionData = JsonParser.parseString(responseBody).getAsJsonObject();
                            String state = connectionData.get("state").getAsString();
                            
                            if ("active".equals(state) && !proofSent) {
                                proofSent = true;
                                getLogger().info("Connection is active! Sending proof request...");
                                
                                sendMessage(player, Component.text("✓ Wallet connected! Sending proof request...", NamedTextColor.GREEN));
                                
                                // Remove QR map
                                Bukkit.getScheduler().runTask(SimpleSSIPlugin.this, () -> removeQRMaps(player));
                                
                                // Send proof request
                                sendProofRequest(connectionId, player);
                                
                                // Stop this monitoring task
                                Bukkit.getScheduler().cancelTask(taskId[0]);
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Connection monitoring error: " + e.getMessage());
                }
            }
        }, 60L, 60L).getTaskId(); // Check every 3 seconds
    }
    
    private void sendProofRequest(String connectionId, Player player) {
        try {
            getLogger().info("Sending proof request for connection: " + connectionId);
            
            // Use ssi-tutorial verifier API approach (like in proof.controller.ts)
            JsonObject proofRequest = new JsonObject();
            proofRequest.addProperty("proofRequestlabel", "Minecraft Server Verification");
            proofRequest.addProperty("connectionId", connectionId);
            proofRequest.addProperty("version", "1.0");
            
            RequestBody body = RequestBody.create(proofRequest.toString(), MediaType.get("application/json"));
            Request httpRequest = new Request.Builder()
                .url("http://localhost:4002/v2/send-proof-request")
                .post(body)
                .build();
            
            getLogger().info("Proof request payload: " + proofRequest.toString());
            
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "No response";
                getLogger().info("Proof request response: " + response.code() + " - " + responseBody);
                
                if (response.isSuccessful()) {
                    sendMessage(player, Component.text("Proof request sent! Please approve in your wallet.", NamedTextColor.YELLOW));
                    
                    JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();
                    if (responseJson.has("pres_ex_id")) {
                        String proofExchangeId = responseJson.get("pres_ex_id").getAsString();
                        monitorProofStatus(proofExchangeId, player);
                    } else {
                        // Fallback: monitor all proof records for this connection
                        monitorProofStatusByConnection(connectionId, player);
                    }
                    
                } else {
                    getLogger().warning("Proof request failed: " + response.code() + " - " + responseBody);
                    sendMessage(player, Component.text("Failed to send proof request", NamedTextColor.RED));
                }
            }
        } catch (Exception e) {
            getLogger().severe("Proof request failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private JsonArray buildFlexibleRestrictions() {
        JsonArray restrictions = new JsonArray();
        
        try {
            // Query all available credential definitions
            Request request = new Request.Builder()
                .url(acapyAdminUrl + "/credential-definitions/created")
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject responseJson = JsonParser.parseString(response.body().string()).getAsJsonObject();
                    JsonArray credDefIds = responseJson.getAsJsonArray("credential_definition_ids");
                    
                    // Add each credential definition as a valid restriction
                    for (int i = 0; i < credDefIds.size(); i++) {
                        JsonObject restriction = new JsonObject();
                        restriction.addProperty("cred_def_id", credDefIds.get(i).getAsString());
                        restrictions.add(restriction);
                    }
                    
                    getLogger().info("Built flexible restrictions with " + restrictions.size() + " credential definitions");
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to build flexible restrictions: " + e.getMessage());
        }
        
        // If no credential definitions found, add a fallback broad restriction
        if (restrictions.size() == 0) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("schema_name", "Identity_Schema");
            restrictions.add(fallback);
            getLogger().info("Using fallback restriction: Identity_Schema");
        }
        
        return restrictions;
    }
    
    private void monitorProofStatus(String proofExchangeId, Player player) {
        final int[] taskId = new int[1];
        
        taskId[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            private int attempts = 0;
            
            @Override
            public void run() {
                attempts++;
                
                if (attempts > 60) { // 3 minutes timeout  
                    sendMessage(player, Component.text("Proof verification timeout", NamedTextColor.RED));
                    Bukkit.getScheduler().cancelTask(taskId[0]);
                    return;
                }
                
                try {
                    // Check proof status using ACA-Py API
                    Request request = new Request.Builder()
                        .url(acapyAdminUrl + "/present-proof-2.0/records/" + proofExchangeId)
                        .build();
                    
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String responseBody = response.body().string();
                            getLogger().info("Proof status check #" + attempts + ": " + responseBody);
                            
                            JsonObject proofData = JsonParser.parseString(responseBody).getAsJsonObject();
                            String state = proofData.get("state").getAsString();
                            
                            if ("presentation-received".equals(state) || "done".equals(state)) {
                                // Proof was received and verified
                                verifiedPlayers.put(player.getName(), true);
                                sendMessage(player, Component.text("✓ Verification completed successfully!", NamedTextColor.GREEN));
                                
                                // Give glowing effect
                                Bukkit.getScheduler().runTask(SimpleSSIPlugin.this, () -> {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                                        "effect give " + player.getName() + " minecraft:glowing 999999 0 true");
                                });
                                
                                Bukkit.getScheduler().cancelTask(taskId[0]);
                                return;
                                
                            } else if ("abandoned".equals(state) || "request-rejected".equals(state)) {
                                sendMessage(player, Component.text("Verification was rejected or abandoned", NamedTextColor.RED));
                                Bukkit.getScheduler().cancelTask(taskId[0]);
                                return;
                            }
                            
                            // Still waiting - continue monitoring
                            if (attempts == 1) {
                                sendMessage(player, Component.text("Please check your wallet and approve the proof request!", NamedTextColor.GOLD));
                            }
                            
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Proof status monitoring error: " + e.getMessage());
                }
            }
        }, 60L, 60L).getTaskId(); // Check every 3 seconds
    }
    
    private void monitorProofStatusByConnection(String connectionId, Player player) {
        final int[] taskId = new int[1];
        
        taskId[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            private int attempts = 0;
            
            @Override
            public void run() {
                attempts++;
                
                if (attempts > 60) { // 3 minutes timeout  
                    sendMessage(player, Component.text("Proof verification timeout", NamedTextColor.RED));
                    Bukkit.getScheduler().cancelTask(taskId[0]);
                    return;
                }
                
                try {
                    // Check all proof records to find one for this connection
                    Request request = new Request.Builder()
                        .url(acapyAdminUrl + "/present-proof-2.0/records")
                        .build();
                    
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String responseBody = response.body().string();
                            getLogger().info("Proof records check #" + attempts);
                            
                            JsonObject recordsData = JsonParser.parseString(responseBody).getAsJsonObject();
                            JsonArray records = recordsData.getAsJsonArray("results");
                            
                            for (int i = 0; i < records.size(); i++) {
                                JsonObject record = records.get(i).getAsJsonObject();
                                if (record.has("connection_id") && 
                                    connectionId.equals(record.get("connection_id").getAsString())) {
                                    
                                    String state = record.get("state").getAsString();
                                    
                                    if ("presentation-received".equals(state) || "done".equals(state)) {
                                        // Proof was received and verified
                                        verifiedPlayers.put(player.getName(), true);
                                        sendMessage(player, Component.text("✓ Verification completed successfully!", NamedTextColor.GREEN));
                                        
                                        // Give glowing effect
                                        Bukkit.getScheduler().runTask(SimpleSSIPlugin.this, () -> {
                                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
                                                "effect give " + player.getName() + " minecraft:glowing 999999 0 true");
                                        });
                                        
                                        Bukkit.getScheduler().cancelTask(taskId[0]);
                                        return;
                                        
                                    } else if ("abandoned".equals(state) || "request-rejected".equals(state)) {
                                        sendMessage(player, Component.text("Verification was rejected or abandoned", NamedTextColor.RED));
                                        Bukkit.getScheduler().cancelTask(taskId[0]);
                                        return;
                                    }
                                }
                            }
                            
                            // Still waiting - continue monitoring
                            if (attempts == 1) {
                                sendMessage(player, Component.text("Please check your wallet and approve the proof request!", NamedTextColor.GOLD));
                            }
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Proof status monitoring error: " + e.getMessage());
                }
            }
        }, 60L, 60L).getTaskId(); // Check every 3 seconds
    }
    
    private void removeQRMaps(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.FILLED_MAP) {
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName() &&
                    item.getItemMeta().getDisplayName().contains("SSI Verification")) {
                    player.getInventory().setItem(i, null);
                    break;
                }
            }
        }
    }
    
    private void sendMessage(Player player, Component message) {
        Bukkit.getScheduler().runTask(this, () -> player.sendMessage(message));
    }
    
    private void handleSSIVerify(Player sender, String targetPlayerName) {
        sender.sendMessage(Component.text("=== Verification Status ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Player: " + targetPlayerName, NamedTextColor.WHITE));
        
        if (verifiedPlayers.getOrDefault(targetPlayerName, false)) {
            sender.sendMessage(Component.text("Status: ✓ VERIFIED", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Status: ✗ NOT VERIFIED", NamedTextColor.RED));
        }
    }
}