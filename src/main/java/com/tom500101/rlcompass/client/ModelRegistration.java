package com.tom500101.rlcompass.client;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import com.tom500101.rlcompass.item.ItemExplorersCompass;

/**
 * Registers the item's inventory model.
 *
 * <p>Without this call Forge has no {@code ModelResourceLocation} for the item and bakes the
 * missing-model checkerboard, which is what the purple/black square in the hotbar is. The 32 frame
 * models already exist under {@code models/item/}; they only need to be pointed at.
 */
public final class ModelRegistration {

    @SubscribeEvent
    public void onModelRegistry(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(ItemExplorersCompass.INSTANCE, 0,
                new ModelResourceLocation("rlcompass:" + ItemExplorersCompass.REGISTRY_NAME, "inventory"));
    }
}
