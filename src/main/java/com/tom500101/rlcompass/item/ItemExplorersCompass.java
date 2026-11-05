package com.tom500101.rlcompass.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.tom500101.rlcompass.client.CompassAngleProperty;
import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.locate.StructureLocatorRegistry;
import com.tom500101.rlcompass.locate.StructureResult;
import com.tom500101.rlcompass.network.PacketHandler;
import com.tom500101.rlcompass.network.PacketStructureResult;
import com.tom500101.rlcompass.util.StructureDimensions;

/**
 * The Explorer's Compass.
 *
 * <p>Right-click asks the server for the list of searchable ids and opens the selection screen; the
 * pick comes back as a {@link com.tom500101.rlcompass.network.PacketSearchRequest} and the answer as a
 * {@link PacketStructureResult}. Nothing about the target is kept server-side — the HUD is a client
 * concern and per-player server state would just go stale on relog.
 */
public class ItemExplorersCompass extends Item {

    public static final String REGISTRY_NAME = "explorers_compass";

    /** The single registered instance, for identity checks ({@code stack.getItem() == INSTANCE}) on the client. */
    public static ItemExplorersCompass INSTANCE;

    public ItemExplorersCompass() {
        INSTANCE = this;
        // MCP stable_39 renamed setUnlocalizedName -> setTranslationKey (the older snapshot mapping
        // used the former name; the project is on stable_39).
        setTranslationKey("rlcompass." + REGISTRY_NAME);
        setRegistryName("rlcompass", REGISTRY_NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
        // Drives the 32-frame dial texture. The baked model overrides in models/item/
        // explorers_compass.json pick the frame from this 0..1 value; see CompassAngleProperty.
        addPropertyOverride(new ResourceLocation("angle"), new CompassAngleProperty());
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!world.isRemote && player instanceof EntityPlayerMP) {
            // P0: world-aware filter — hide ids whose locator has no data source in this world.
            PacketHandler.sendOpenGui((EntityPlayerMP) player,
                    StructureLocatorRegistry.get().getSelectableIds(world));
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    /**
     * Runs a search for {@code structureId} from the player's position and sends the outcome to that
     * player's client. Returns the result for logging, or {@code null} when nothing was found.
     */
    public static StructureResult search(EntityPlayerMP player, String structureId) {
        return search(player, structureId, null);
    }

    /** Variant-aware variant used by the picker; {@code variantId} may be null. */
    public static StructureResult search(EntityPlayerMP player, String structureId, String variantId) {
        World world = player.getEntityWorld();
        BlockPos origin = player.getPosition();
        int dimension = world.provider.getDimension();
        // A structure that only generates in another dimension cannot be "not found" — it was never
        // searched. Refuse early and let the client name the dimension it belongs to.
        if (!StructureDimensions.allows(structureId, variantId, dimension)) {
            PacketHandler.sendToPlayer(PacketStructureResult.wrongDimension(structureId, dimension,
                    variantId, StructureDimensions.of(structureId, variantId)), player);
            return null;
        }
        StructureResult result = StructureLocatorRegistry.get()
                .findNearest(world, origin, structureId, CompassConfig.searchRadius, variantId);
        if (result == null) {
            PacketHandler.sendToPlayer(PacketStructureResult.notFound(structureId, dimension, variantId), player);
        } else {
            // Every answer goes into the warehouse, so the next search moves on instead of
            // repeating this spot; the row can be deleted there to make it searchable again.
            com.tom500101.rlcompass.cache.SearchMemory memory =
                    com.tom500101.rlcompass.cache.SearchMemory.get(world);
            if (memory != null) {
                memory.remember(result.getStructureId(), result.getPos());
            }
            PacketHandler.sendToPlayer(PacketStructureResult.found(result.getStructureId(), result.getPos(),
                    dimension, result.getVariantId(), result.isVariantConfirmed()), player);
        }
        return result;
    }
}
