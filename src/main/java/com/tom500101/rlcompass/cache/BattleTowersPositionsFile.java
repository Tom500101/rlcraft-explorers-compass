package com.tom500101.rlcompass.cache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads Battle Towers' own position file directly from disk.
 *
 * <p>Normally {@code WorldGenHandler.eventWorldLoad} imports this file into its in-memory
 * {@code worldMap}. RLTweaker's {@code Consistent Tower Generation} replaces that handler and
 * suppresses the import, so the map stays empty even though the save may still hold a valid file
 * (for example a world created before the option was enabled). Reading the file ourselves restores
 * that data without touching BattleTowers or RLTweaker.
 *
 * <p>Format written by BattleTowers ({@code WorldGenHandler.TowerPosition.toString}):
 * {@code x y z type underground}, one tower per line, {@code #} starts a comment line. The mod
 * splits on a single space; we accept any whitespace. Files are per dimension (the dimension is
 * implied by the directory), and this class never creates or modifies one.
 */
public final class BattleTowersPositionsFile {

    private static final Logger LOG = LogManager.getLogger("rlcompass");

    /** Match {@code WorldGenHandler.fileName}. */
    public static final String FILE_NAME = "BattletowerPositionsFile.txt";

    private static final Map<String, Cached> CACHE = new HashMap<String, Cached>();

    private BattleTowersPositionsFile() {
    }

    /** Parsed towers for {@code world}'s dimension; empty when the file is absent or unreadable. */
    public static List<BlockPos> read(World world) {
        File file = fileFor(world);
        if (file == null || !file.isFile()) {
            return Collections.emptyList();
        }
        String key = file.getAbsolutePath();
        long lastModified = file.lastModified();
        synchronized (CACHE) {
            Cached cached = CACHE.get(key);
            if (cached != null && cached.lastModified == lastModified) {
                return cached.positions;
            }
        }
        List<BlockPos> parsed = parse(file);
        List<BlockPos> immutable = Collections.unmodifiableList(parsed);
        synchronized (CACHE) {
            CACHE.put(key, new Cached(lastModified, immutable));
        }
        return immutable;
    }

    /**
     * Mirrors {@code WorldGenHandler.getWorldHandle}: the save directory, plus the dimension's save
     * folder when the provider has one. {@code getSaveFolder()} returns {@code null} for dimensions
     * without a folder — BattleTowers would concatenate the string "null" there, which is a bug we
     * deliberately do not copy.
     */
    static File fileFor(World world) {
        if (world == null || world.getSaveHandler() == null || world.provider == null) {
            return null;
        }
        File directory = world.getSaveHandler().getWorldDirectory();
        if (directory == null) {
            return null;
        }
        if (world.provider.getDimension() != 0) {
            String folder = world.provider.getSaveFolder();
            if (folder != null && !folder.isEmpty()) {
                directory = new File(directory, folder);
            }
        }
        try {
            directory = directory.getCanonicalFile();
        } catch (IOException ignored) {
            // Keep the absolute path; the file lookup below will simply miss if it is wrong.
        }
        return new File(directory, FILE_NAME);
    }

    private static List<BlockPos> parse(File file) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 5) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int z = Integer.parseInt(parts[2]);
                    Integer.parseInt(parts[3]); // tower type, unused by the compass
                    parseUnderground(parts[4]);
                    out.add(new BlockPos(x, y, z));
                } catch (NumberFormatException ignored) {
                    // BattleTowers skips malformed lines too; keep reading the rest.
                }
            }
        } catch (IOException e) {
            LOG.debug("[BT] could not read {} ({})", file, e.toString());
            return Collections.emptyList();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
        LOG.debug("[BT] parsed {} tower positions from {}", out.size(), file.getName());
        return out;
    }

    /** The file writes {@code true}/{@code false}; accept {@code 1}/{@code 0} as well. */
    private static boolean parseUnderground(String raw) {
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    private static final class Cached {
        final long lastModified;
        final List<BlockPos> positions;

        Cached(long lastModified, List<BlockPos> positions) {
            this.lastModified = lastModified;
            this.positions = positions;
        }
    }
}
