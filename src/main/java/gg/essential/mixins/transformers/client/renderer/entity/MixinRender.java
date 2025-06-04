/*
 * Copyright (c) 2024 ModCore Inc. All rights reserved.
 *
 * This code is part of ModCore Inc.'s Essential Mod repository and is protected
 * under copyright registration # TX0009138511. For the full license, see:
 * https://github.com/EssentialGG/Essential/blob/main/LICENSE
 *
 * You may not use, copy, reproduce, modify, sell, license, distribute,
 * commercialize, or otherwise exploit, or create derivative works based
 * upon, this file or any other in this repository, all of which is reserved by Essential.
 */
package gg.essential.mixins.transformers.client.renderer.entity;

import com.llamalad7.mixinextras.sugar.Local;
import gg.essential.handlers.OnlineIndicator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Render.class)
public class MixinRender {
    @Inject(method = "renderLivingLabel", at = @At("RETURN"))
    private void setNametagEntity(CallbackInfo ci) {
        OnlineIndicator.currentlyDrawingPlayerEntityName.set(false);
    }

    //#if MC==11202
    @Inject(method = "renderLivingLabel", at = @At(value = "HEAD"))
    private <T extends Entity> void setNametagEntity(CallbackInfo ci, @Local(argsOnly = true) T entityIn) {
        OnlineIndicator.nametagEntity = entityIn;
    }

    @Inject(method = "renderLivingLabel", at = @At(value = "RETURN"))
    private void resetNametagEntity(CallbackInfo ci) {
        OnlineIndicator.nametagEntity = null;
    }
    //#endif
}
