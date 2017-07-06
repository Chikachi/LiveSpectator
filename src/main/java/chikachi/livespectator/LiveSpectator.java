package chikachi.livespectator;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.CPacketSpectate;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameType;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.*;

@SuppressWarnings({"WeakerAccess"})
@Mod(modid = Constants.MODID, name = Constants.MODNAME, version = Constants.VERSION, acceptableRemoteVersions = "*", clientSideOnly = true)
public class LiveSpectator {
    @SuppressWarnings("unused")
    @Mod.Instance(value = Constants.MODID)
    public static LiveSpectator instance;

    private static final int radius = 5;

    private static Minecraft minecraft;
    private static KeyBinding toggleKeyBinding;
    private static Timer timer;
    private static PlayerPickTask task;
    private static double degree = 0;
    private static boolean isActive = false;
    private static GameProfile currentPlayer = null;

    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        toggleKeyBinding = new KeyBinding("Toggle LiveSpectator", Keyboard.KEY_NUMPAD6, Constants.MODNAME);
        ClientRegistry.registerKeyBinding(toggleKeyBinding);

        MinecraftForge.EVENT_BUS.register(this);

        minecraft = Minecraft.getMinecraft();
    }

    /**
     * Handle key press to toggle
     */
    @SubscribeEvent
    public void onKeyPress(InputEvent.KeyInputEvent event) {
        if (toggleKeyBinding.isPressed()) {
            isActive = !isActive;
            EntityPlayerSP player = minecraft.player;

            if (isActive) {
                minecraft.gameSettings.pauseOnLostFocus = false;
                player.setGameType(GameType.SPECTATOR);
                task = new LiveSpectator.PlayerPickTask();
                degree = 0;
                timer = new Timer(true);
                timer.scheduleAtFixedRate(task, 0, (long) 3e4);
            } else {
                currentPlayer = null;
                task.cancel();
                timer.cancel();
            }
        } else if (isActive) {
            event.setResult(Event.Result.DENY);
        }
    }

    /**
     * Disable player nametags
     */
    @SubscribeEvent
    public void onNametagRender(RenderLivingEvent.Specials.Pre event) {
        if (!event.isCancelable() || !isActive) return;

        if (event.getEntity() instanceof EntityPlayer) {
            if (minecraft.gameSettings.showDebugInfo) {
                return;
            }

            event.setCanceled(true);
        }
    }

    /**
     * Write name of player in top left corner
     */
    @SubscribeEvent
    public void OnGUIRenderPost(RenderGameOverlayEvent.Post event) {
        if (!isActive || currentPlayer == null || event.getType() != RenderGameOverlayEvent.ElementType.EXPERIENCE || minecraft.gameSettings.showDebugInfo) {
            return;
        }

        minecraft.fontRendererObj.drawStringWithShadow(currentPlayer.getName(), 5, 5, 0xFFAA00);
    }

    /**
     * Pick a new player every 30 seconds
     */
    private class PlayerPickTask extends TimerTask {
        @Override
        public void run() {
            if (isActive) {
                pickRandomPlayer();
            }
        }
    }

    /**
     * Pick a random player
     */
    public void pickRandomPlayer() {
        final EntityPlayerSP player = minecraft.player;
        final NetHandlerPlayClient netHandlerPlayClient = minecraft.getConnection();

        if (netHandlerPlayClient != null) {
            final Collection<NetworkPlayerInfo> playerInfoMap = netHandlerPlayClient.getPlayerInfoMap();
            final List<GameProfile> players = new ArrayList<>();
            playerInfoMap.forEach(networkPlayerInfo -> {
                if (networkPlayerInfo.getGameProfile().equals(player.getGameProfile())) {
                    return;
                }

                if (!networkPlayerInfo.getGameType().isSurvivalOrAdventure()) {
                    return;
                }

                if (currentPlayer != null && networkPlayerInfo.getGameProfile().equals(currentPlayer)) {
                    return;
                }

                players.add(networkPlayerInfo.getGameProfile());
            });

            if (players.size() > 0) {
                if (players.size() > 1) {
                    Collections.shuffle(players);
                }
                GameProfile randomPlayer = players.get(0);
                netHandlerPlayClient.sendPacket(new CPacketSpectate(randomPlayer.getId()));
                currentPlayer = randomPlayer;
            }
        } else {
            timer.cancel();
            isActive = false;
        }
    }

    /**
     * Camera movements
     */
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (!isActive || minecraft.player == null || minecraft.player.world == null || currentPlayer == null) {
            return;
        }

        // Update rotation degree
        degree -= 0.1;
        if (degree >= 360) {
            degree -= 360;
        }

        // Get the player entity
        EntityPlayer playerEntity = minecraft.player.world.getPlayerEntityByUUID(currentPlayer.getId());
        if (playerEntity == null) {
            // If the player wasn't found, try to get it again
            final NetHandlerPlayClient netHandlerPlayClient = minecraft.getConnection();
            if (netHandlerPlayClient != null) {
                netHandlerPlayClient.sendPacket(new CPacketSpectate(currentPlayer.getId()));
            }
        } else {
            // Calculate new position
            double radians = degree * (Math.PI / 180);
            double x = Math.cos(radians) * radius;
            double z = Math.sin(radians) * radius;

            Vec3d playerPos = new Vec3d(playerEntity.posX, playerEntity.posY + 1.5d, playerEntity.posZ);
            Vec3d newPos = new Vec3d(playerEntity.posX + x, playerEntity.posY + 2.5d, playerEntity.posZ + z);
            RayTraceResult rayTraceResult = playerEntity.world.rayTraceBlocks(playerPos, newPos, false, true, false);
            double y = 1.75d;
            if (rayTraceResult != null && rayTraceResult.typeOfHit == RayTraceResult.Type.BLOCK) {
                newPos = rayTraceResult.hitVec.subtract(rayTraceResult.hitVec.subtract(playerPos).normalize().scale(0.25d));
                y = Math.max(1.75d, ((newPos.distanceTo(playerPos) + 0.2) / radius) * y);
            }
            minecraft.player.move(newPos.xCoord - minecraft.player.posX, newPos.yCoord - minecraft.player.posY - y, newPos.zCoord - minecraft.player.posZ);

            // Update rotation of view
            double dirX = newPos.xCoord - playerEntity.posX;
            double dirY = newPos.yCoord - playerEntity.posY - y;
            double dirZ = newPos.zCoord - playerEntity.posZ;

            dirX /= radius;
            dirY /= radius;
            dirZ /= radius;

            double pitch = Math.asin(dirY) * (180.0 / Math.PI);
            double yaw = Math.atan2(dirZ, dirX) * (180.0 / Math.PI);

            if (pitch != Double.NaN) {
                minecraft.player.rotationPitch = (float) pitch;
            }

            yaw += 90f;
            minecraft.player.rotationYaw = (float) yaw;
        }
    }
}
