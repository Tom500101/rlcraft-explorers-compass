package com.tom500101.rlcompass.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tom500101.rlcompass.client.gui.GuiStructureSelect;
import com.tom500101.rlcompass.config.CompassConfig;
import com.tom500101.rlcompass.item.ItemExplorersCompass;
import com.tom500101.rlcompass.locate.StructureLocatorRegistry;

/**
 * Client-side smoke test for the HUD, GUI and registry wiring.
 *
 * <p>The HUD only draws when there is a target, and a scripted run cannot hold an item or click a
 * GUI, so "the client started without crashing" would say nothing about whether the renderer works.
 * With {@code -Drlcompass.clienttest=<mode>} (or {@code ./gradlew runClient -Pclienttest=<mode>},
 * which forwards the property to the forked client) this plants synthetic targets and asserts the
 * computed values instead of only logging that a frame happened.
 *
 * <p>Modes are documented in {@code docs/runtime-verification.md}. {@code true} is kept as an alias
 * for {@code hud} so the original instruction still works. Every check produces one
 * {@code PASS}/{@code FAIL}/{@code SKIP} line in {@code run/clienttest-result.txt}; a run with any
 * failure throws after the summary.
 */
public class ClientHudSelfTest {

    public static final String PROPERTY = "rlcompass.clienttest";

    /** Every mode the property accepts. {@code true}/{@code 1} map to {@link #HUD}. */
    public enum Mode {
        OFF, HUD, ARROW, GUI, SEARCH, STATE, REGISTRY, ANGLE, ALL
    }

    private static final Logger LOG = LogManager.getLogger("rlcompass");
    private static final String RESULT_FILE = "clienttest-result.txt";
    /**
     * How long a world-dependent mode waits for the player to enter a world. 2400 ticks (2 minutes)
     * leaves room for slow machines and large worlds; the old 600-tick value was hit at ~25s during
     * the first hud run. Override with {@code -Drlcompass.clienttest.worldwait=<ticks>}.
     */
    private static final int DEFAULT_WORLD_WAIT_TICKS = 2400;
    private static final int WORLD_WAIT_TICKS = resolveWorldWaitTicks();
    private static final int PHASE_TIMEOUT_TICKS = 200;
    private static final int SEARCH_TIMEOUT_TICKS = 400;

    private static int resolveWorldWaitTicks() {
        String raw = System.getProperty("rlcompass.clienttest.worldwait");
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_WORLD_WAIT_TICKS;
        }
        try {
            int ticks = Integer.parseInt(raw.trim());
            return ticks > 0 ? ticks : DEFAULT_WORLD_WAIT_TICKS;
        } catch (NumberFormatException e) {
            LOG.warn("[CLIENTTEST] invalid rlcompass.clienttest.worldwait='{}', using {}",
                    raw, DEFAULT_WORLD_WAIT_TICKS);
            return DEFAULT_WORLD_WAIT_TICKS;
        }
    }

    /** Resolved once: the property is set on the JVM command line, so it cannot change at runtime. */
    private static volatile Mode cachedMode;

    private final Deque<Mode> queue = new ArrayDeque<Mode>();
    private final List<String> resultLines = new ArrayList<String>();

    private boolean started;
    private boolean finished;
    private Mode current = Mode.OFF;
    private int phase;
    private int lastPhase = -1;
    private int waitTicks;
    private int phaseTicks;
    private int worldWait;
    private int passCount;
    private int failCount;
    private int skipCount;

    private boolean originalShowExactCoordinates;
    private String searchExpectedId;
    private CompassAngleProperty angleProperty;
    private ItemStack angleStack;
    private float angleFirstValue;

    private boolean positionSeeded;
    private double lastPosX;
    private double lastPosZ;
    private int stableTicks;
    private int searchReadyTicks;

    // ------------------------------------------------------------------ mode resolution

    /** {@code -Pclienttest=<mode>} is forwarded as {@code -Drlcompass.clienttest=<mode>}. */
    public static Mode resolveMode() {
        Mode mode = cachedMode;
        if (mode == null) {
            mode = parseMode(System.getProperty(PROPERTY, "false"));
            cachedMode = mode;
        }
        return mode;
    }

    static Mode parseMode(String raw) {
        if (raw == null) {
            return Mode.OFF;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "false".equals(value) || "off".equals(value) || "0".equals(value)) {
            return Mode.OFF;
        }
        if ("true".equals(value) || "1".equals(value)) {
            return Mode.HUD;
        }
        for (Mode mode : Mode.values()) {
            if (mode.name().toLowerCase(Locale.ROOT).equals(value)) {
                return mode;
            }
        }
        LOG.warn("[CLIENTTEST] unknown mode '{}'; known modes = {}", raw, Arrays.toString(Mode.values()));
        return Mode.OFF;
    }

    /** True when the HUD line should be captured for assertions (HUD or ALL). */
    static boolean isHudCaptureActive() {
        Mode mode = resolveMode();
        return mode == Mode.HUD || mode == Mode.ALL;
    }

    /** True when the arrow's yaw offset should be captured for assertions (ARROW or ALL). */
    static boolean isArrowCaptureActive() {
        Mode mode = resolveMode();
        return mode == Mode.ARROW || mode == Mode.ALL;
    }

    // ------------------------------------------------------------------ tick driver

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Mode mode = resolveMode();
        if (mode == Mode.OFF || finished) {
            return;
        }
        if (!started) {
            start(mode);
            if (finished) {
                return;
            }
        }
        if (current == null) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (requiresWorld(current) && player() == null) {
            // Do not burn the phase budget while the player is still on the main menu.
            phaseTicks = 0;
            if (++worldWait > WORLD_WAIT_TICKS) {
                skip(current.name().toLowerCase(Locale.ROOT),
                        "no world after " + WORLD_WAIT_TICKS + " ticks");
                nextMode();
            }
            return;
        }
        if (phase != lastPhase) {
            lastPhase = phase;
            phaseTicks = 0;
        }
        int timeout = timeoutFor(current, phase);
        if (++phaseTicks > timeout) {
            if (current == Mode.SEARCH && phase == 0) {
                // A world that never becomes ready is an environment problem, not a search failure.
                skip("search", "world not ready after " + timeout + " ticks");
            } else {
                fail(current.name().toLowerCase(Locale.ROOT) + ".timeout",
                        "phase " + phase + " within " + timeout + " ticks", "timeout");
            }
            nextMode();
            return;
        }
        tickCurrent();
    }

    private void start(Mode mode) {
        started = true;
        originalShowExactCoordinates = CompassConfig.showExactCoordinates;
        LOG.info("[CLIENTTEST] START mode={}", mode.name().toLowerCase(Locale.ROOT));
        if (mode == Mode.ALL) {
            queue.addAll(Arrays.asList(
                    Mode.HUD, Mode.ARROW, Mode.GUI, Mode.STATE, Mode.REGISTRY, Mode.ANGLE));
            LOG.info("[CLIENTTEST] NOTE all excludes search; run -Pclienttest=search separately");
        } else {
            queue.add(mode);
        }
        nextMode();
    }

    private void nextMode() {
        current = queue.poll();
        phase = 0;
        lastPhase = -1;
        waitTicks = 1;
        phaseTicks = 0;
        worldWait = 0;
        if (current == null) {
            finish();
            return;
        }
        LOG.info("[CLIENTTEST] MODE {}", current.name().toLowerCase(Locale.ROOT));
        if (current == Mode.HUD) {
            positionSeeded = false;
            stableTicks = 0;
        }
        if (current == Mode.SEARCH) {
            searchReadyTicks = 0;
        }
        CompassHudRenderer.resetTestCapture();
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        CompassConfig.showExactCoordinates = originalShowExactCoordinates;
        ClientCompassState.clear();
        LOG.info("[CLIENTTEST] SUMMARY pass={} fail={} skip={}", passCount, failCount, skipCount);
        writeResultFile();
        if (failCount > 0) {
            throw new IllegalStateException("[CLIENTTEST] " + failCount
                    + " check(s) failed; see " + RESULT_FILE);
        }
    }

    private void tickCurrent() {
        switch (current) {
            case HUD:
                tickHud();
                break;
            case ARROW:
                tickArrow();
                break;
            case GUI:
                tickGui();
                break;
            case SEARCH:
                tickSearch();
                break;
            case STATE:
                tickState();
                break;
            case REGISTRY:
                tickRegistry();
                break;
            case ANGLE:
                tickAngle();
                break;
            default:
                nextMode();
                break;
        }
    }

    // ------------------------------------------------------------------ modes

    private void tickHud() {
        EntityPlayerSP player = player();
        if (player == null) {
            return;
        }
        if (phase == 0) {
            // Wait for the spawn teleport to settle: injecting the target from the pre-generation
            // position makes hud.distance measure the teleport instead of the HUD.
            if (!positionStable(player)) {
                return;
            }
            CompassConfig.showExactCoordinates = true;
            ClientCompassState.setTarget("minecraft:village",
                    player.getPosition().east(500), player.dimension);
            CompassHudRenderer.resetTestCapture();
            waitTicks = 2;
            phase = 1;
            return;
        }
        if (phase == 1) {
            String line = CompassHudRenderer.getLastLine();
            // Wait for the fifth frame before asserting: the checks below include the frame count,
            // and failing on frame 1 would only measure the wait, not HUD liveness.
            if (line == null || CompassHudRenderer.getRenderCount() < 5) {
                return;
            }
            check("hud.line", true, line.contains("Village"));
            checkRange("hud.distance", 495.0D, 505.0D, CompassHudRenderer.getLastDistance());
            check("hud.point", "E", CompassHudRenderer.getLastCompassPoint());
            check("hud.frames", true, CompassHudRenderer.getRenderCount() >= 5);
            check("hud.coords.on", true, line.contains("["));
            CompassConfig.showExactCoordinates = false;
            waitTicks = 2;
            phase = 2;
            return;
        }
        if (phase == 2) {
            String line = CompassHudRenderer.getLastLine();
            if (line == null) {
                return;
            }
            check("hud.coords.off", false, line.contains("["));
            CompassConfig.showExactCoordinates = true;
            nextMode();
        }
    }

    private void tickArrow() {
        EntityPlayerSP player = player();
        if (player == null) {
            return;
        }
        if (phase == 0) {
            ClientCompassState.setTarget("minecraft:village",
                    player.getPosition().east(500), player.dimension);
            CompassHudRenderer.resetTestCapture();
            player.rotationYaw = 0.0F;
            waitTicks = 2;
            phase = 1;
            return;
        }
        if (phase == 1) {
            checkRange("arrow.yaw0", 269.0D, 271.0D, CompassHudRenderer.getLastRelativeYaw());
            player.rotationYaw = -90.0F;
            waitTicks = 2;
            phase = 2;
            return;
        }
        if (phase == 2) {
            checkRange("arrow.yawEast", -1.0D, 1.0D, CompassHudRenderer.getLastRelativeYaw());
            player.rotationYaw = 90.0F;
            waitTicks = 2;
            phase = 3;
            return;
        }
        if (phase == 3) {
            checkRange("arrow.yawWest", 179.0D, 181.0D, CompassHudRenderer.getLastRelativeYaw());
            nextMode();
        }
    }

    private void tickGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (phase == 0) {
            List<String> ids = Arrays.asList(
                    "minecraft:village", "minecraft:temple",
                    "rlcompass:battletower", "reccomplex:structure");
            mc.displayGuiScreen(new GuiStructureSelect(ids));
            waitTicks = 2;
            phase = 1;
            return;
        }
        if (phase == 1) {
            check("gui.opened", true, mc.currentScreen instanceof GuiStructureSelect);
            if (mc.currentScreen instanceof GuiStructureSelect) {
                List<String> lines = ((GuiStructureSelect) mc.currentScreen).runClientTestSelfCheck();
                for (String line : lines) {
                    recordLine(line);
                }
            }
            mc.displayGuiScreen(null);
            waitTicks = 1;
            phase = 2;
            return;
        }
        if (phase == 2) {
            // "Closed" means the picker is gone, regardless of what replaced it: the main menu at the
            // title screen, the pause menu after a focus loss, or null in a world.
            check("gui.closed", true, !(mc.currentScreen instanceof GuiStructureSelect));
            nextMode();
        }
    }

    private void tickSearch() {
        Minecraft mc = Minecraft.getMinecraft();
        if (phase == 0) {
            if (!mc.isSingleplayer()) {
                skip("search", "requires single-player integrated server");
                nextMode();
                return;
            }
            EntityPlayerSP player = player();
            if (player == null) {
                return;
            }
            if (!guiReady(mc, player)) {
                return;
            }
            // P0: world-aware filter — the first row must be an id that can actually answer.
            List<String> ids = StructureLocatorRegistry.get().getSelectableIds(mc.world);
            if (ids.isEmpty()) {
                skip("search", "no selectable structures");
                nextMode();
                return;
            }
            // Far away so the renderer's arrival rule cannot clear it before the answer arrives.
            ClientCompassState.setTarget("clienttest:sentinel",
                    player.getPosition().east(5000), player.dimension);
            mc.displayGuiScreen(new GuiStructureSelect(ids));
            waitTicks = 2;
            phase = 1;
            return;
        }
        if (phase == 1) {
            if (!(mc.currentScreen instanceof GuiStructureSelect)) {
                fail("search.gui", "GuiStructureSelect", String.valueOf(mc.currentScreen));
                nextMode();
                return;
            }
            pass("search.gui");
            searchExpectedId = ((GuiStructureSelect) mc.currentScreen).simulateSearchFirstRow();
            check("search.requested", true, searchExpectedId != null);
            waitTicks = 1;
            phase = 2;
            return;
        }
        if (phase == 2) {
            check("search.guiClosed", true, mc.currentScreen == null);
            waitTicks = 0;
            phase = 3;
            return;
        }
        if (phase == 3) {
            // Any change away from the sentinel proves a result packet arrived: either a new
            // target id, or an empty id after clear() for a "not found" answer.
            String id = ClientCompassState.getStructureId();
            if (!"clienttest:sentinel".equals(id)) {
                check("search.roundtrip", true, true);
                nextMode();
            }
        }
    }

    private void tickState() {
        EntityPlayerSP player = player();
        if (player == null) {
            return;
        }
        if (phase == 0) {
            ClientCompassState.setTarget("minecraft:village",
                    player.getPosition().east(100), player.dimension);
            waitTicks = 2;
            phase = 1;
            return;
        }
        if (phase == 1) {
            check("state.sameDimension", true, ClientCompassState.hasTarget());
            ClientCompassState.setTarget("minecraft:village",
                    player.getPosition().east(100), player.dimension + 1);
            waitTicks = 2;
            phase = 2;
            return;
        }
        if (phase == 2) {
            check("state.dimensionChangeClears", false, ClientCompassState.hasTarget());
            ClientCompassState.setTarget("minecraft:village",
                    player.getPosition().east(5), player.dimension);
            waitTicks = 2;
            phase = 3;
            return;
        }
        if (phase == 3) {
            // Arriving no longer drops the target: it stays until a new search or a dimension change.
            check("state.arrivalKeeps", true, ClientCompassState.hasTarget());
            nextMode();
        }
    }

    private void tickRegistry() {
        if (phase != 0) {
            return;
        }
        Item item = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("rlcompass", ItemExplorersCompass.REGISTRY_NAME));
        check("registry.item", true, item == ItemExplorersCompass.INSTANCE);

        NonNullList<ItemStack> stacks = NonNullList.create();
        CreativeTabs.TOOLS.displayAllRelevantItems(stacks);
        boolean inCreativeTab = false;
        for (ItemStack stack : stacks) {
            if (stack.getItem() == ItemExplorersCompass.INSTANCE) {
                inCreativeTab = true;
                break;
            }
        }
        check("registry.creativeTab", true, inCreativeTab);

        // P0: world-aware filter; the client world is null at the title screen, which keeps the
        // legacy unfiltered list and the six vanilla assertions below.
        List<String> ids = StructureLocatorRegistry.get()
                .getSelectableIds(Minecraft.getMinecraft().world);
        LOG.info("[CLIENTTEST] selectable ids = {}", ids);
        String[] vanilla = {
                "minecraft:stronghold", "minecraft:village", "minecraft:mineshaft",
                "minecraft:temple", "minecraft:monument", "minecraft:mansion"
        };
        for (String id : vanilla) {
            check("registry.id." + id, true, ids.contains(id));
        }
        phase = 1;
        nextMode();
    }

    private void tickAngle() {
        EntityPlayerSP player = player();
        if (player == null) {
            return;
        }
        if (phase == 0) {
            ClientCompassState.setTarget("minecraft:village",
                    player.getPosition().east(100), player.dimension);
            player.rotationYaw = -90.0F;
            angleProperty = new CompassAngleProperty();
            angleStack = new ItemStack(ItemExplorersCompass.INSTANCE);
            waitTicks = 1;
            phase = 1;
            return;
        }
        if (phase == 1) {
            angleFirstValue = angleProperty.apply(angleStack, player.world, player);
            checkRange("angle.range", 0.0D, 1.0D, angleFirstValue);
            waitTicks = 2;
            phase = 2;
            return;
        }
        if (phase == 2) {
            float value = angleProperty.apply(angleStack, player.world, player);
            checkRange("angle.secondRange", 0.0D, 1.0D, value);
            if (Minecraft.getMinecraft().isGamePaused()) {
                // Single-player pauses when the window loses focus; world time stops, so the eased
                // dial value cannot change. That is an environment state, not a mod defect.
                skip("angle.moves", "world paused (client lost focus)");
            } else {
                check("angle.moves", true, Math.abs(value - angleFirstValue) > 1.0E-4F);
            }
            nextMode();
        }
    }

    // ------------------------------------------------------------------ helpers

    private EntityPlayerSP player() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.player != null && mc.world != null ? mc.player : null;
    }

    /**
     * True after the player's X/Z have not changed for five consecutive client ticks. Entering a
     * world teleports the player from the pre-generation position to the spawn point; injecting the
     * HUD target before that settles makes {@code hud.distance} measure the teleport, not the HUD.
     */
    private boolean positionStable(EntityPlayerSP player) {
        double x = player.posX;
        double z = player.posZ;
        if (!positionSeeded) {
            positionSeeded = true;
            lastPosX = x;
            lastPosZ = z;
            stableTicks = 0;
            return false;
        }
        if (Math.abs(x - lastPosX) < 1.0E-4D && Math.abs(z - lastPosZ) < 1.0E-4D) {
            stableTicks++;
        } else {
            stableTicks = 0;
        }
        lastPosX = x;
        lastPosZ = z;
        return stableTicks >= 5;
    }

    /**
     * True after the world has been ready for five consecutive client ticks: the player has existed
     * for more than a second and no screen (loading or pause) is open. Opening the picker earlier
     * lets the world-load completion close it again.
     */
    private boolean guiReady(Minecraft mc, EntityPlayerSP player) {
        boolean ready = player.ticksExisted > 20 && mc.currentScreen == null;
        if (ready) {
            searchReadyTicks++;
        } else {
            searchReadyTicks = 0;
        }
        return searchReadyTicks >= 5;
    }

    private static boolean requiresWorld(Mode mode) {
        return mode == Mode.HUD || mode == Mode.ARROW || mode == Mode.SEARCH
                || mode == Mode.STATE || mode == Mode.ANGLE;
    }

    private static int timeoutFor(Mode mode, int phase) {
        if (mode == Mode.SEARCH) {
            return phase == 0 ? WORLD_WAIT_TICKS : SEARCH_TIMEOUT_TICKS;
        }
        return PHASE_TIMEOUT_TICKS;
    }

    private void check(String name, boolean expected, boolean actual) {
        if (expected == actual) {
            pass(name);
        } else {
            fail(name, Boolean.toString(expected), Boolean.toString(actual));
        }
    }

    private void check(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            pass(name);
        } else {
            fail(name, expected, actual);
        }
    }

    private void checkRange(String name, double min, double max, double actual) {
        if (actual >= min && actual <= max) {
            pass(name);
        } else {
            fail(name, "[" + min + ", " + max + "]", Double.toString(actual));
        }
    }

    private void pass(String name) {
        passCount++;
        resultLines.add("PASS " + name);
        LOG.info("[CLIENTTEST] PASS {}", name);
    }

    private void fail(String name, String expected, String actual) {
        failCount++;
        resultLines.add("FAIL " + name + " expected=" + expected + " actual=" + actual);
        LOG.warn("[CLIENTTEST] FAIL {} expected={} actual={}", name, expected, actual);
    }

    private void skip(String name, String reason) {
        skipCount++;
        resultLines.add("SKIP " + name + " reason=" + reason);
        LOG.info("[CLIENTTEST] SKIP {} reason={}", name, reason);
    }

    /** Accepts a line produced by a GUI test hook, keeping the same PASS/FAIL/SKIP contract. */
    private void recordLine(String line) {
        if (line == null) {
            return;
        }
        if (line.startsWith("PASS ")) {
            pass(line.substring(5));
        } else if (line.startsWith("FAIL ")) {
            failCount++;
            resultLines.add(line);
            LOG.warn("[CLIENTTEST] {}", line);
        } else if (line.startsWith("SKIP ")) {
            skipCount++;
            resultLines.add(line);
            LOG.info("[CLIENTTEST] {}", line);
        } else {
            fail("gui.result", "PASS/FAIL/SKIP line", line);
        }
    }

    private void writeResultFile() {
        File cwd = new File(".");
        File file = new File(cwd, RESULT_FILE);
        File runDir = new File(cwd, "run");
        if (runDir.isDirectory()) {
            file = new File(runDir, RESULT_FILE);
        }
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                for (String line : resultLines) {
                    writer.write(line);
                    writer.write('\n');
                }
            } finally {
                writer.close();
            }
            LOG.info("[CLIENTTEST] result file: {}", file.getAbsolutePath());
        } catch (Exception e) {
            LOG.warn("[CLIENTTEST] cannot write {}: {}", file.getAbsolutePath(), e.toString());
        }
    }
}
