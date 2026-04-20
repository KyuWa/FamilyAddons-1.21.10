package org.kyowa.familyaddons.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.EnderDragonEntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.model.Model;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.kyowa.familyaddons.EntityRefAccessor;
import org.kyowa.familyaddons.features.PlayerDisguise;
import org.kyowa.familyaddons.features.SharedDisguiseSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Mixin(LivingEntityRenderer.class)
public abstract class PlayerDisguiseMixin<T extends LivingEntity,
        S extends LivingEntityRenderState,
        M extends Model<?>> {

    // ── Caches for normal (LivingEntity) mobs ────────────────────────
    private static final Map<Class<?>, Method> createRenderStateCache = new HashMap<>();
    private static final Map<String, LivingEntity> cachedMobs = new HashMap<>();
    private static final Map<String, String> cachedMobIds = new HashMap<>();
    private static final Map<String, Boolean> cachedBabies = new HashMap<>();
    private static final Map<String, Boolean> cachedSheareds = new HashMap<>();
    private static final Map<String, LivingEntityRenderState> cachedMobStates = new HashMap<>();
    private static final Map<String, Class<?>> cachedRendererClasses = new HashMap<>();
    private static final Map<String, Integer> mobAge = new HashMap<>();

    // ── Caches for Ender Dragon (non-LivingEntityRenderer path) ──────
    private static final Map<String, EnderDragonEntity> cachedDragons = new HashMap<>();
    private static final Map<String, EnderDragonEntityRenderState> cachedDragonStates = new HashMap<>();
    private static final Map<String, Integer> dragonAge = new HashMap<>();

    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRender(S state, MatrixStack matrixStack, OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        LivingEntity entity = ((EntityRefAccessor) state).familyaddons$getEntity();
        if (!(entity instanceof PlayerEntity player)) return;
        if (player.isInvisibleTo(client.player)) return;

        boolean isSelf = player == client.player;
        String username = player.getName().getString();

        String mobId = null;
        boolean baby = false;
        boolean sheared = false;

        if (isSelf) {
            if (!PlayerDisguise.INSTANCE.isEnabled()) return;
            mobId = PlayerDisguise.INSTANCE.getMobId();
            baby = PlayerDisguise.INSTANCE.isBaby();
            sheared = PlayerDisguise.INSTANCE.isSheared();
        } else {
            int scope = PlayerDisguise.INSTANCE.getScope();
            if (PlayerDisguise.INSTANCE.isEnabled() && scope == 1) {
                mobId = PlayerDisguise.INSTANCE.getMobId();
                baby = PlayerDisguise.INSTANCE.isBaby();
                sheared = PlayerDisguise.INSTANCE.isSheared();
            } else {
                SharedDisguiseSync.SyncedDisguise synced = SharedDisguiseSync.INSTANCE.getDisguise(username);
                if (synced == null) return;
                mobId = synced.getMobId();
                baby = synced.getBaby();
                sheared = synced.getSheared();
            }
        }

        if (mobId == null || mobId.isEmpty()) return;
        Identifier id = Identifier.tryParse(mobId);
        if (id == null) return;
        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        if (type == null || type == EntityType.PLAYER) return;

        // ── Ender Dragon special path ─────────────────────────────────
        if (type == EntityType.ENDER_DRAGON) {
            renderAsEnderDragon(client, player, playerState, matrixStack, queue, cameraRenderState, username, ci);
            return;
        }
        // ─────────────────────────────────────────────────────────────

        // ── Normal LivingEntity path ──────────────────────────────────
        String cachedId = cachedMobIds.get(username);
        LivingEntity cachedMob = cachedMobs.get(username);
        boolean cachedBaby = Boolean.TRUE.equals(cachedBabies.get(username));
        boolean cachedSheared = Boolean.TRUE.equals(cachedSheareds.get(username));

        if (cachedMob == null || !mobId.equals(cachedId) || baby != cachedBaby || sheared != cachedSheared) {
            try {
                cachedMob = (LivingEntity) type.create(player.getEntityWorld(), SpawnReason.COMMAND);
            } catch (Exception e) { cachedMobs.remove(username); return; }
            if (cachedMob == null) return;

            // Baby: works for animals, zombies, AND villagers
            if (baby) {
                if (cachedMob instanceof VillagerEntity villager) {
                    villager.setBreedingAge(-24000);
                } else if (cachedMob instanceof AnimalEntity animal) {
                    animal.setBreedingAge(-24000);
                } else if (cachedMob instanceof ZombieEntity zombie) {
                    zombie.setBaby(true);
                }
            }

            // Sheared: sheep removes wool, snow golem removes pumpkin
            if (sheared) {
                if (cachedMob instanceof SheepEntity sheep) {
                    sheep.setSheared(true);
                } else if (cachedMob instanceof SnowGolemEntity snowGolem) {
                    snowGolem.setHasPumpkin(false);
                }
            }

            cachedMobs.put(username, cachedMob);
            cachedMobIds.put(username, mobId);
            cachedBabies.put(username, baby);
            cachedSheareds.put(username, sheared);
            cachedMobStates.remove(username);
            cachedRendererClasses.remove(username);
            mobAge.remove(username);
        }

        LivingEntity mob = cachedMob;
        mob.setPos(player.getX(), player.getY(), player.getZ());
        mob.lastX = player.lastX;
        mob.lastY = player.lastY;
        mob.lastZ = player.lastZ;
        mob.setYaw(player.getHeadYaw());
        mob.lastYaw = player.lastYaw;
        mob.setPitch(player.getPitch());
        mob.lastPitch = player.lastPitch;
        mob.bodyYaw = player.bodyYaw;
        mob.lastBodyYaw = player.lastBodyYaw;
        mob.headYaw = player.headYaw;
        mob.lastHeadYaw = player.lastHeadYaw;

        EntityRenderManager dispatcher = client.getEntityRenderDispatcher();

        @SuppressWarnings("unchecked")
        EntityRenderer<LivingEntity, LivingEntityRenderState> renderer =
                (EntityRenderer<LivingEntity, LivingEntityRenderState>) dispatcher.getRenderer(mob);
        if (renderer == null) return;

        Method createRenderState = createRenderStateCache.get(renderer.getClass());
        if (createRenderState == null) {
            Class<?> cls = renderer.getClass();
            outer:
            while (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 &&
                            LivingEntityRenderState.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        createRenderState = m;
                        break outer;
                    }
                }
                cls = cls.getSuperclass();
            }
            if (createRenderState == null) return;
            createRenderStateCache.put(renderer.getClass(), createRenderState);
        }

        LivingEntityRenderState mobState = cachedMobStates.get(username);
        Class<?> cachedRendererClass = cachedRendererClasses.get(username);
        if (mobState == null || renderer.getClass() != cachedRendererClass) {
            try {
                mobState = (LivingEntityRenderState) createRenderState.invoke(renderer);
                cachedMobStates.put(username, mobState);
                cachedRendererClasses.put(username, renderer.getClass());
            } catch (Exception e) { return; }
        }

        // Tick the mob so flying/animated mobs have correct animation state
        try {
            int age = mobAge.getOrDefault(username, 0) + 1;
            mobAge.put(username, age);
            mob.age = age;
            mob.setVelocity(0.0, 0.1, 0.0);
        } catch (Exception ignored) {}

        try { renderer.updateRenderState(mob, mobState, 1.0f); } catch (Exception e) { return; }

        mobState.bodyYaw = playerState.bodyYaw;
        mobState.relativeHeadYaw = playerState.relativeHeadYaw;
        mobState.pitch = playerState.pitch;
        mobState.limbSwingAnimationProgress = playerState.limbSwingAnimationProgress;
        mobState.limbSwingAmplitude = playerState.limbSwingAmplitude;
        mobState.age = playerState.age;
        mobState.invisible = false;
        mobState.invisibleToPlayer = false;
        mobState.light = playerState.light;
        mobState.x = playerState.x;
        mobState.y = playerState.y;
        mobState.z = playerState.z;

        try { renderer.render(mobState, matrixStack, queue, cameraRenderState); } catch (Exception e) { return; }

        if (playerState.displayName != null && playerState.nameLabelPos != null) {
            queue.submitLabel(matrixStack, playerState.nameLabelPos, 0, playerState.displayName,
                    !playerState.sneaking, playerState.light, playerState.squaredDistanceToCamera, cameraRenderState);
        }
        ci.cancel();
    }

    // ── Ender Dragon render helper ────────────────────────────────────
    private void renderAsEnderDragon(
            MinecraftClient client,
            PlayerEntity player,
            PlayerEntityRenderState playerState,
            MatrixStack matrixStack,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraRenderState,
            String username,
            CallbackInfo ci) {

        // Get or create a cached dragon entity
        EnderDragonEntity dragon = cachedDragons.get(username);
        if (dragon == null) {
            try {
                dragon = (EnderDragonEntity) EntityType.ENDER_DRAGON.create(
                        player.getEntityWorld(), SpawnReason.COMMAND);
                if (dragon == null) return;
                cachedDragons.put(username, dragon);
            } catch (Exception e) { return; }
        }

        // Sync position & rotation to the player.
        // The dragon model faces the opposite direction to normal entities,
        // so we add 180 degrees to all yaw values to flip it the right way.
        dragon.setPos(player.getX(), player.getY(), player.getZ());
        dragon.lastX = player.lastX;
        dragon.lastY = player.lastY;
        dragon.lastZ = player.lastZ;
        dragon.setYaw(player.getYaw() + 180f);
        dragon.lastYaw = player.lastYaw + 180f;
        dragon.setPitch(player.getPitch());
        dragon.lastPitch = player.lastPitch;
        dragon.bodyYaw = player.bodyYaw + 180f;
        dragon.lastBodyYaw = player.lastBodyYaw + 180f;
        dragon.headYaw = player.headYaw + 180f;
        dragon.lastHeadYaw = player.lastHeadYaw + 180f;

        // Tick age counter and throttle tickMovement to ~20/sec (game tick rate).
        // Calling it every render frame (60+/sec) makes the wings flap way too fast.
        int age = dragonAge.getOrDefault(username, 0) + 1;
        dragonAge.put(username, age);
        dragon.age = age;
        dragon.setVelocity(0.0, 0.1, 0.0);

        // Only call tickMovement every 3rd render frame ≈ 20 times/sec at 60fps.
        // This advances prevWingPosition/wingPosition at the correct rate for natural wing flap speed.
        if (age % 3 == 0) {
            try { dragon.tickMovement(); } catch (Exception ignored) {}
        }

        // Get the dragon's renderer via the entity render dispatcher
        EntityRenderManager dispatcher = client.getEntityRenderDispatcher();
        @SuppressWarnings("unchecked")
        EntityRenderer<EnderDragonEntity, EnderDragonEntityRenderState> renderer =
                (EntityRenderer<EnderDragonEntity, EnderDragonEntityRenderState>)
                        dispatcher.getRenderer(dragon);
        if (renderer == null) return;

        // Get or create the render state reflectively
        EnderDragonEntityRenderState dragonState = cachedDragonStates.get(username);
        if (dragonState == null) {
            Method createRenderState = findNoArgRenderStateMethod(renderer.getClass());
            if (createRenderState == null) return;
            try {
                dragonState = (EnderDragonEntityRenderState) createRenderState.invoke(renderer);
                cachedDragonStates.put(username, dragonState);
            } catch (Exception e) { return; }
        }

        // Let the renderer populate all the dragon-specific fields
        // (partBodyYaws, partPitches, sinceLastAttackTick, wingProgress, etc.)
        try { renderer.updateRenderState(dragon, dragonState, 1.0f); } catch (Exception e) { return; }

        // Override position/light to match the player.
        // bodyYaw, pitch, invisibleToPlayer are not fields on EnderDragonEntityRenderState —
        // updateRenderState already populated them correctly from the dragon entity above.
        dragonState.x = playerState.x;
        dragonState.y = playerState.y;
        dragonState.z = playerState.z;
        dragonState.light = playerState.light;
        dragonState.age = playerState.age;
        dragonState.invisible = false;

        // Render the dragon in place of the player
        try { renderer.render(dragonState, matrixStack, queue, cameraRenderState); } catch (Exception e) { return; }

        // Still render the player's nametag above the dragon
        if (playerState.displayName != null && playerState.nameLabelPos != null) {
            queue.submitLabel(matrixStack, playerState.nameLabelPos, 0, playerState.displayName,
                    !playerState.sneaking, playerState.light, playerState.squaredDistanceToCamera, cameraRenderState);
        }

        ci.cancel();
    }

    /**
     * Finds a no-arg method on the renderer class (or any superclass) whose
     * return type is assignable to EntityRenderState. Used to call
     * createRenderState() reflectively without depending on the exact name
     * (which can be obfuscated / change between MC versions).
     */
    private static Method findNoArgRenderStateMethod(Class<?> cls) {
        while (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 &&
                        net.minecraft.client.render.entity.state.EntityRenderState.class
                                .isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }
}