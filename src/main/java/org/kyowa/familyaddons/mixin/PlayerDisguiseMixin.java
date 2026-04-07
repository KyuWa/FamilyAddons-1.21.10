package org.kyowa.familyaddons.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.entity.state.SlimeEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.model.Model;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.kyowa.familyaddons.EntityRefAccessor;
import org.kyowa.familyaddons.features.PlayerDisguise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Mixin(LivingEntityRenderer.class)
public abstract class PlayerDisguiseMixin<T extends LivingEntity,
        S extends LivingEntityRenderState,
        M extends Model<?>> {

    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRender(S state, MatrixStack matrixStack, OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (!PlayerDisguise.INSTANCE.isEnabled()) return;
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        LivingEntity entity = ((EntityRefAccessor) state).familyaddons$getEntity();
        if (!(entity instanceof PlayerEntity player)) return;

        // scope: 0 = self only, 1 = everyone
        int scope = PlayerDisguise.INSTANCE.getScope();
        boolean isSelf = player == client.player;
        if (scope == 0 && !isSelf) return;

        if (player.isInvisibleTo(client.player)) return;

        String mobId = PlayerDisguise.INSTANCE.getMobId();
        Identifier id = Identifier.tryParse(mobId);
        if (id == null) return;

        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        if (type == null || type == EntityType.PLAYER) return;

        LivingEntity mob;
        try {
            mob = (LivingEntity) type.create(player.getEntityWorld(), SpawnReason.COMMAND);
        } catch (Exception e) {
            return;
        }
        if (mob == null) return;

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
        float partialTick = client.getRenderTickCounter().getTickProgress(true);

        @SuppressWarnings("unchecked")
        EntityRenderer<LivingEntity, LivingEntityRenderState> renderer =
                (EntityRenderer<LivingEntity, LivingEntityRenderState>) dispatcher.getRenderer(mob);
        if (renderer == null) return;

        // Use reflection to call createRenderState() — avoids @Shadow refmap issues entirely
        LivingEntityRenderState mobState;
        try {
            Method createRenderState = null;
            Class<?> cls = renderer.getClass();
            while (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 &&
                            LivingEntityRenderState.class.isAssignableFrom(m.getReturnType())) {
                        createRenderState = m;
                        break;
                    }
                }
                if (createRenderState != null) break;
                cls = cls.getSuperclass();
            }
            if (createRenderState == null) return;
            createRenderState.setAccessible(true);
            mobState = (LivingEntityRenderState) createRenderState.invoke(renderer);
        } catch (Exception e) {
            return;
        }

        try {
            renderer.updateRenderState(mob, mobState, partialTick);
        } catch (Exception e) {
            return;
        }

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

        try {
            renderer.render(mobState, matrixStack, queue, cameraRenderState);
        } catch (Exception e) {
            return;
        }

        ci.cancel();
    }
}
