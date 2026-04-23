package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientConnectedEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.mc.block.Direction;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetHeldItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

public class Auto128Bot extends AbstractInventoryModule {

    private int counter = 1;   // Account counter (1-128), formatted as 3 digits
    private boolean hasLoggedIn = false;  // Track if we've sent login command
    private int currentTargetIndex = 1;  // Which target position we're going to (1-4)
    private boolean isNavigating = false; // Are we currently navigating to a target
    private boolean needsReconnect = false; // Flag to trigger reconnect with new account
    private boolean justDisconnected = false; // Just disconnected, waiting to reconnect
    
    private static final int MAX_COUNTER = 128;
    private static final String LOG_FILE = "128bot.log";
    
    // State machine states
    private enum BotState {
        WAITING_FOR_LOGIN,      // Just connected, need to login
        NAVIGATING_TO_TARGET,   // Moving to a target position
        AT_TARGET,              // Reached target, need to interact
        RETURNING_TO_START,     // Going back to target1 after completing all 4
        FINISHED_CYCLE          // Completed cycle, ready to switch account
    }
    
    private BotState currentState = BotState.WAITING_FOR_LOGIN;

    public Auto128Bot() {
        super(AbstractInventoryModule.HandRestriction.EITHER, 3);
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.auto128Bot.enabled;
    }

    @Override
    public boolean itemPredicate(ItemStack itemStack) {
        return false;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleClientTick),
            of(ClientConnectedEvent.class, this::handleClientConnected),
            of(ClientDisconnectEvent.class, this::handleClientDisconnected)
        );
    }

    @Override
    public int getPriority() {
        return 10000;
    }

    @Override
    public void onEnable() { 
        reset(); 
        info("Auto128Bot 已启用。当前计数器：" + formatCounter(counter));
        logDebug("Auto128Bot enabled, counter=" + formatCounter(counter) + ", prefix=" + CONFIG.client.extra.auto128Bot.prefix);
    }

    @Override
    public void onDisable() { 
        reset(); 
        info("Auto128Bot 已禁用.");
        logDebug("Auto128Bot disabled");
    }

    private void reset() {
        hasLoggedIn = false;
        currentTargetIndex = 1;
        isNavigating = false;
        needsReconnect = false;
        justDisconnected = false;
        currentState = BotState.WAITING_FOR_LOGIN;
    }
    
    // Format counter as 3-digit string (e.g., 1 -> "001", 12 -> "012", 128 -> "128")
    private String formatCounter(int counter) {
        return String.format("%03d", counter);
    }
    
    // Get current username based on prefix and counter
    private String getCurrentUsername() {
        return CONFIG.client.extra.auto128Bot.prefix + formatCounter(counter);
    }
    
    // Parse yaw direction string to float value
    private float parseYawDirection(String direction) {
        return switch (direction.toLowerCase()) {
            case "south" -> 0.0f;
            case "west" -> 90.0f;
            case "north" -> 180.0f;
            case "east" -> -90.0f;
            default -> 0.0f;
        };
    }
    
    // Get target coordinates based on index
    private double[] getTargetCoords(int index) {
        return switch (index) {
            case 1 -> new double[]{CONFIG.client.extra.auto128Bot.targetX1, CONFIG.client.extra.auto128Bot.targetY1, CONFIG.client.extra.auto128Bot.targetZ1};
            case 2 -> new double[]{CONFIG.client.extra.auto128Bot.targetX2, CONFIG.client.extra.auto128Bot.targetY2, CONFIG.client.extra.auto128Bot.targetZ2};
            case 3 -> new double[]{CONFIG.client.extra.auto128Bot.targetX3, CONFIG.client.extra.auto128Bot.targetY3, CONFIG.client.extra.auto128Bot.targetZ3};
            case 4 -> new double[]{CONFIG.client.extra.auto128Bot.targetX4, CONFIG.client.extra.auto128Bot.targetY4, CONFIG.client.extra.auto128Bot.targetZ4};
            default -> new double[]{0, 0, 0};
        };
    }
    
    // Log debug message to both console and file
    private void logDebug(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String logMessage = "[" + timestamp + "] " + message;
        info(logMessage);
        
        // Append to log file in jar directory
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(LOG_FILE), true))) {
            writer.println(logMessage);
        } catch (IOException e) {
            // Silently fail if we can't write to log file
        }
    }

    private double distanceSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        return (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2) + (z1 - z2) * (z1 - z2);
    }
    
    private boolean isAtTarget(double px, double py, double pz, int targetIndex) {
        double[] coords = getTargetCoords(targetIndex);
        // Check if player is within 1 block of target
        return distanceSq(px, py, pz, coords[0], coords[1], coords[2]) < 1.0;
    }

    public void handleClientConnected(final ClientConnectedEvent e) {
        if (!enabledSetting()) return;
        
        String username = getCurrentUsername();
        logDebug("Connected to server with username: " + username);
        logDebug("Current state: prefix=" + CONFIG.client.extra.auto128Bot.prefix + 
                 ", counter=" + formatCounter(counter) + 
                 ", status=WAITING_FOR_LOGIN");
        
        hasLoggedIn = false;
        currentState = BotState.WAITING_FOR_LOGIN;
    }
    
    public void handleClientDisconnected(final ClientDisconnectEvent e) {
        if (!enabledSetting()) return;
        
        logDebug("Disconnected from server. Reason: " + e.reason());
        justDisconnected = true;
        
        // Switch to next account
        switchNextBot();
    }

    public void handleClientTick(final ClientBotTick e) {
        if (!enabledSetting()) return;
        
        INVENTORY.submit(InventoryActionRequest.noAction(this, getPriority()));

        if (justDisconnected) {
            // Wait for disconnect to complete
            return;
        }
        
        if (needsReconnect) {
            // Trigger reconnect with new username
            needsReconnect = false;
            return;
        }

        if (CACHE.getPlayerCache().getThePlayer() == null || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return;
        }

        double px = CACHE.getPlayerCache().getX();
        double py = CACHE.getPlayerCache().getY();
        double pz = CACHE.getPlayerCache().getZ();

        // State machine
        switch (currentState) {
            case WAITING_FOR_LOGIN -> {
                if (!hasLoggedIn) {
                    // Send login command
                    String password = CONFIG.client.extra.auto128Bot.password;
                    logDebug("Sending login command: /login " + password);
                    logDebug("Current state: prefix=" + CONFIG.client.extra.auto128Bot.prefix + 
                             ", counter=" + formatCounter(counter) + 
                             ", status=SENDING_LOGIN");
                    sendClientPacketAsync(new ServerboundChatPacket("/login " + password));
                    hasLoggedIn = true;
                    
                    // After login, start navigating to target1
                    EXECUTOR.schedule(() -> {
                        currentState = BotState.NAVIGATING_TO_TARGET;
                        currentTargetIndex = 1;
                        logDebug("Login sent, now navigating to target " + currentTargetIndex);
                    }, 2000); // Wait 2 seconds for login to process
                }
            }
            
            case NAVIGATING_TO_TARGET -> {
                double[] targetCoords = getTargetCoords(currentTargetIndex);
                logDebug("Navigating to target " + currentTargetIndex + 
                         " at (" + targetCoords[0] + ", " + targetCoords[1] + ", " + targetCoords[2] + ")");
                logDebug("Current state: prefix=" + CONFIG.client.extra.auto128Bot.prefix + 
                         ", counter=" + formatCounter(counter) + 
                         ", status=NAVIGATING_TO_TARGET" + currentTargetIndex);
                
                // Use pathfinder to navigate to target
                GoalNear goal = new GoalNear(
                    (int) targetCoords[0],
                    (int) targetCoords[1],
                    (int) targetCoords[2],
                    1 // range squared (1 = very close)
                );
                
                if (!BARITONE.isActive()) {
                    BARITONE.pathTo(goal);
                    isNavigating = true;
                }
                
                // Check if we've reached the target
                if (isAtTarget(px, py, pz, currentTargetIndex)) {
                    logDebug("Reached target " + currentTargetIndex);
                    currentState = BotState.AT_TARGET;
                    BARITONE.stop();
                    isNavigating = false;
                }
            }
            
            case AT_TARGET -> {
                logDebug("At target " + currentTargetIndex + ", performing interaction sequence");
                logDebug("Current state: prefix=" + CONFIG.client.extra.auto128Bot.prefix + 
                         ", counter=" + formatCounter(counter) + 
                         ", status=PERFORMING_INTERACTION");
                
                // Perform the interaction sequence: turn, switch slot, right-click
                performInteractionSequence();
                
                // Move to next target or return to start
                currentTargetIndex++;
                if (currentTargetIndex > 4) {
                    // All 4 targets done, return to target1
                    logDebug("All 4 targets completed, returning to target1");
                    currentTargetIndex = 1;
                    currentState = BotState.NAVIGATING_TO_TARGET;
                } else {
                    // Go to next target
                    currentState = BotState.NAVIGATING_TO_TARGET;
                }
            }
            
            case RETURNING_TO_START -> {
                // This state is handled by NAVIGATING_TO_TARGET with currentTargetIndex=1
                currentState = BotState.NAVIGATING_TO_TARGET;
            }
            
            case FINISHED_CYCLE -> {
                // Disconnect to switch accounts
                logDebug("Cycle finished, disconnecting to switch account");
                Proxy.getInstance().getClient().disconnect("Auto128Bot switching account");
            }
        }
    }
    
    // Perform the interaction sequence: turn to targetYaw, switch to slot 1, right-click
    private void performInteractionSequence() {
        float targetYaw = parseYawDirection(CONFIG.client.extra.auto128Bot.targetYaw);
        float pitch = 0.0f; // Look straight ahead
        
        logDebug("Turning to yaw=" + targetYaw + " (" + CONFIG.client.extra.auto128Bot.targetYaw + "), pitch=" + pitch);
        
        // Set rotation
        CACHE.getPlayerCache().setYaw(targetYaw);
        CACHE.getPlayerCache().setPitch(pitch);
        sendClientPacketAsync(new ServerboundMovePlayerRotPacket(true, false, targetYaw, pitch));
        
        // Switch to slot 1 (hotbar slot 0 in protocol)
        logDebug("Switching hotbar to slot 1");
        sendClientPacketAsync(new ServerboundSetHeldItemPacket(0));
        
        // Send right-click interaction packet (use item on block)
        // We'll interact with the block at the target position
        double[] coords = getTargetCoords(currentTargetIndex);
        Vector3i pos = Vector3i.from((int) coords[0], (int) coords[1], (int) coords[2]);
        
        Direction face = Direction.UP; // Default to interacting with top face
        
        logDebug("Sending right-click interaction at " + pos + " with face " + face);
        ServerboundUseItemOnPacket packet = new ServerboundUseItemOnPacket(
            pos.getX(), pos.getY(), pos.getZ(), face.mcpl(), Hand.MAIN_HAND, 0.5f, 0.5f, 0.5f, false, false, 0
        );
        sendClientPacketAsync(packet);
        
        logDebug("Interaction sequence completed for target " + currentTargetIndex);
    }

    private void switchNextBot() {
        // Increment counter
        counter++;
        if (counter > MAX_COUNTER) {
            counter = 1; // Reset to 1 after 128
            logDebug("Counter reset to 001 after reaching 128");
        }
        
        String newUsername = getCurrentUsername();
        CONFIG.authentication.username = newUsername;
        
        logDebug("Switching to next bot account: " + newUsername);
        logDebug("New state: prefix=" + CONFIG.client.extra.auto128Bot.prefix + 
                 ", counter=" + formatCounter(counter) + 
                 ", status=RECONNECTING");
        
        justDisconnected = false;
        reset();
        
        // Reconnect with new username
        EXECUTOR.schedule(() -> {
            logDebug("Initiating reconnect with username: " + newUsername);
            Proxy.getInstance().connectAndCatchExceptions();
        }, 3000); // Wait 3 seconds before reconnecting
    }
}
