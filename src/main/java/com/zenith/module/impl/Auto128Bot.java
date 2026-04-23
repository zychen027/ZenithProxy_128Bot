package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.mc.block.Direction;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

public class Auto128Bot extends AbstractInventoryModule {

    private int currentBotIndex = 1;   
    private boolean hasMoved = false;  
    private Vector3i firstVaultPos = null; 
    private double startX, startY, startZ;
    private int stateTicks = 0;        
    private int cooldownTicks = 0;     
    private boolean needsLogin = false;
    private int loginCooldownTicks = 0;

    private static final int INTERACT_RANGE = 4; 
    private static final int STUCK_CHECK_DELAY = 100; 
    private static final int MAX_BOT_INDEX = 128;

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
        return List.of(of(ClientBotTick.class, this::handleClientTick));
    }

    @Override
    public int getPriority() {
        return 10000;
    }

    @Override
    public void onEnable() { 
        reset(); 
        info("Auto128Bot 已启用. 当前Bot编号: " + currentBotIndex);
    }

    @Override
    public void onDisable() { 
        reset(); 
        info("Auto128Bot 已禁用.");
    }

    private void reset() {
        firstVaultPos = null;
        startX = startY = startZ = 0;
        hasMoved = false;
        stateTicks = 0;
        cooldownTicks = 0;
        needsLogin = false;
        loginCooldownTicks = 0;
    }

    private double distanceSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        return (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2) + (z1 - z2) * (z1 - z2);
    }

    public void handleClientTick(final ClientBotTick e) {
        if (!enabledSetting()) return;
        
        INVENTORY.submit(InventoryActionRequest.noAction(this, getPriority()));

        if (needsLogin) {
            if (CACHE.getPlayerCache().getThePlayer() != null) {
                info("检测到需要登录，正在发送 /login 指令...");
                sendClientPacketAsync(new ServerboundChatPacket("/login " + CONFIG.client.extra.auto128Bot.password));
                needsLogin = false;
                loginCooldownTicks = 60; 
            }
            return;
        }

        if (loginCooldownTicks > 0) {
            loginCooldownTicks--;
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (CACHE.getPlayerCache().getThePlayer() == null || !CACHE.getPlayerCache().getThePlayer().isAlive()) return;

        stateTicks++;
        double px = CACHE.getPlayerCache().getX();
        double py = CACHE.getPlayerCache().getY();
        double pz = CACHE.getPlayerCache().getZ();

        if (firstVaultPos != null) {
            boolean isStuck = !hasMoved && stateTicks > STUCK_CHECK_DELAY && distanceSq(px, py, pz, startX, startY, startZ) < 2.0;
            boolean isLooped = hasMoved && distanceSq(px, py, pz, firstVaultPos.getX(), firstVaultPos.getY(), firstVaultPos.getZ()) < 4.0; 

            if (isStuck || isLooped) {
                info(String.format("触发换号! 原因: %s. 当前坐标: %.1f, %.1f, %.1f", isStuck ? "卡住未移动" : "完成循环", px, py, pz));
                switchNextBot();
                return;
            }
        }

        if (distanceSq(px, py, pz, startX, startY, startZ) > 9.0) {
            if (!hasMoved) info("检测到Bot已移动，标记 hasMoved = true.");
            hasMoved = true;
        }

        Vector3i vaultPos = findNearbyVault();
        if (vaultPos != null) {
            info("找到目标! 准备与宝库交互，坐标: " + vaultPos);
            interactWithBlock(vaultPos);
            if (firstVaultPos == null) {
                firstVaultPos = vaultPos;
                startX = px;
                startY = py;
                startZ = pz;
                stateTicks = 0;
                info("记录第一个宝库坐标: " + vaultPos + "，Bot起始坐标: " + px + ", " + py + ", " + pz);
            }
            cooldownTicks = 20; 
        } else {
            if (stateTicks % 100 == 0) info("附近未找到宝库，继续寻找...");
        }
    }

    private Vector3i findNearbyVault() {
        int px = (int) Math.floor(CACHE.getPlayerCache().getX());
        int py = (int) Math.floor(CACHE.getPlayerCache().getY());
        int pz = (int) Math.floor(CACHE.getPlayerCache().getZ());

        for (int x = px - INTERACT_RANGE; x <= px + INTERACT_RANGE; x++) {
            for (int y = py - INTERACT_RANGE; y <= py + INTERACT_RANGE; y++) {
                for (int z = pz - INTERACT_RANGE; z <= pz + INTERACT_RANGE; z++) {
                    var section = CACHE.getChunkCache().getChunkSection(x, y, z);
                    if (section == null) continue;

                    int blockId = section.getBlock(x & 15, y & 15, z & 15);
                    var blockData = BLOCK_DATA.getBlockDataFromBlockStateId(blockId);
                    if (blockData != null && blockData.name().equals("minecraft:vault")) {
                        return Vector3i.from(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    private void interactWithBlock(Vector3i pos) {
        double px = CACHE.getPlayerCache().getX();
        double py = CACHE.getPlayerCache().getY();
        double pz = CACHE.getPlayerCache().getZ();
        
        double targetX = pos.getX() + 0.5;
        double targetY = pos.getY() + 0.5; 
        double targetZ = pos.getZ() + 0.5;
        
        double dx = targetX - px;
        double dy = targetY - (py + 1.62); 
        double dz = targetZ - pz;
        
        double r = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, r));
        
        info(String.format("计算视角: 目标XZ=%.1f,%.1f | 偏航=%.2f, 俯仰=%.2f", targetX, targetZ, yaw, pitch));

        CACHE.getPlayerCache().setYaw(yaw);
        CACHE.getPlayerCache().setPitch(pitch);
        sendClientPacketAsync(new ServerboundMovePlayerRotPacket(true, false, yaw, pitch));
        info("已发送视角转动包");

        Direction face;
        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > Math.abs(dz)) {
            face = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (Math.abs(dy) > Math.abs(dz)) {
            face = dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        info("计算交互面: " + face.name() + " (dx=" + String.format("%.2f", dx) + ", dy=" + String.format("%.2f", dy) + ", dz=" + String.format("%.2f", dz) + ")");

        ServerboundUseItemOnPacket packet = new ServerboundUseItemOnPacket(
            pos.getX(), pos.getY(), pos.getZ(), face.mcpl(), Hand.MAIN_HAND, 0.5f, 0.5f, 0.5f, false, false, 0
        );
        sendClientPacketAsync(packet);
        info("已发送右键交互包! BlockPos=" + pos + ", Face=" + face.mcpl());
    }

    private void switchNextBot() {
        currentBotIndex++;
        if (currentBotIndex > MAX_BOT_INDEX) currentBotIndex = 1; 

        String newUsername = String.format("%s%03d", CONFIG.client.extra.auto128Bot.prefix, currentBotIndex);
        info("切换到下一个Bot账号: " + newUsername);

        CONFIG.authentication.username = newUsername;
        
        needsLogin = true; 
        reset();
        cooldownTicks = 60; 
        Proxy.getInstance().getClient().disconnect("Auto128Bot switching to " + newUsername);
    }
}
