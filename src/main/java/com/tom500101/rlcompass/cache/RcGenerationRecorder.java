package com.tom500101.rlcompass.cache;

import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.util.Reflect;

/**
 * Records Recurrent Complex structures the moment RC places them.
 *
 * <p>RC fires {@code StructureGenerationEventLite.Post} (and a fuller {@code Post} carrying the
 * spawn context) after every placement. Listening there is strictly better than reading RC's data
 * file afterwards: RLTweaker deletes the recorded entries on every world load, and the file only
 * ever describes the session that saved it, so anything read from it is already incomplete.
 *
 * <p>The event class lives in RC, which this mod does not compile against, so the listener is typed
 * on {@link WorldEvent} — Forge delivers subclasses to a superclass listener — and the payload is
 * read reflectively. If RC changes the accessors, recording simply stops while the save-file
 * fallback in {@link RecurrentComplexData} keeps working.
 */
public class RcGenerationRecorder {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    private static final String LITE_POST = "ivorius.reccomplex.events.StructureGenerationEventLite$Post";
    private static final String FULL_POST = "ivorius.reccomplex.events.StructureGenerationEvent$Post";

    private boolean warned;

    @SubscribeEvent
    public void onWorldEvent(WorldEvent event) {
        String type = event.getClass().getName();
        if (!LITE_POST.equals(type) && !FULL_POST.equals(type)) {
            return;
        }
        World world = event.getWorld();
        Object id = Reflect.invoke(event, "getStructureName");
        Object box = Reflect.invoke(event, "getBoundingBox");
        if (world == null || !(id instanceof String) || !(box instanceof StructureBoundingBox)) {
            if (!warned) {
                warned = true;
                LOG.info("[RC] generation event {} carries no usable id/box; relying on the save file",
                        type);
            }
            return;
        }
        RecurrentComplexData.record(world, (String) id, (StructureBoundingBox) box);
    }
}
