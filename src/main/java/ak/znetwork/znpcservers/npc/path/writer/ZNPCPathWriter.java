package ak.znetwork.znpcservers.npc.path.writer;

import ak.znetwork.znpcservers.ServersNPC;
import ak.znetwork.znpcservers.configuration.enums.ZNConfigValue;
import ak.znetwork.znpcservers.configuration.enums.type.ZNConfigType;
import ak.znetwork.znpcservers.manager.ConfigManager;
import ak.znetwork.znpcservers.npc.ZNPC;
import ak.znetwork.znpcservers.npc.path.ZNPCPathReader;
import ak.znetwork.znpcservers.user.ZNPCUser;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

import lombok.Getter;

/**
 * <p>Copyright (c) ZNetwork, 2020.</p>
 *
 * @author ZNetwork
 * @since 07/02/2020
 */
@Getter
public final class ZNPCPathWriter {

    /**
     * The logger.
     */
    private static final Logger LOGGER = Bukkit.getLogger();

    /**
     * Represents one game-tick (50 milliseconds).
     */
    private static final int TICK = 50;

    /**
     * The maximum elements that the npc path can have.
     */
    private static final int MAX_LOCATIONS = ConfigManager.getByType(ZNConfigType.CONFIG).getValue(ZNConfigValue.MAX_PATH_LOCATIONS);

    /**
     * The executor service to delegate work.
     */
    private static final ExecutorService pathExecutorService;

    static {
        pathExecutorService = Executors.newCachedThreadPool();
    }

    /**
     * The user who creates the path.
     */
    private final ZNPCUser npcUser;

    /**
     * The path name.
     */
    private final String name;

    /**
     * The file where the path will be saved.
     */
    private final File file;

    /**
     * A list of locations.
     *
     * Represents the npc path.
     */
    private final List<Location> locationsCache;

    /**
     * The plugin instance.
     */
    private final ServersNPC serversNPC;

    /**
     * Creates a new path creator.
     *
     * @param serversNPC The plugin instance.
     * @param npcUser The user who creates the path.
     * @param name The path name.
     */
    public ZNPCPathWriter(ServersNPC serversNPC,
                          ZNPCUser npcUser,
                          String name) {
        this.serversNPC = serversNPC;
        this.npcUser = npcUser;
        this.name = name;

        this.file = new File(serversNPC.getDataFolder().getAbsolutePath() + "/paths", name + ".path");
        this.locationsCache = new ArrayList<>();

        try {
            this.file.createNewFile();

            this.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Starts the path task.
     *
     * The task will record every movement of the player.
     */
    public void start() {
        // Set has path for user.
        getNpcUser().setHasPath(true);

        // Schedule npc path task.
        pathExecutorService.execute(() -> {
            // This while loop will continue recording new locations for path & blocking the current thread.
            // As long the player is connected & the locations size hasn't reached the limit.
            // Once the loop is broken the thread will write the recorded locations to the path file.
            while (getNpcUser().toPlayer() != null && getNpcUser().isHasPath() && MAX_LOCATIONS > locationsCache.size()) {
                Location location = getNpcUser().toPlayer().getLocation();

                // Check if location is valid
                if (checkEntry(location)) {
                    locationsCache.add(location);

                    // Lock current thread for 1 TICK
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(TICK));
                }
            }

            try {
                // Write locations to file
                write();
            } catch (IOException e) {
                getNpcUser().setHasPath(false);

                LOGGER.log(Level.WARNING, String.format("Path %s could not be created", name), e);
            }
        });
    }

    /**
     * Write saved locations to path file.
     *
     * @throws IOException If the file cannot be written.
     */
    public void write() throws IOException {
        if (locationsCache.isEmpty()) return;

        try(FileOutputStream inputStream = new FileOutputStream(file);
            DataOutputStream dataOutputStream = new DataOutputStream(inputStream)) {

            Iterator<Location> locationIterator = locationsCache.iterator();
            while (locationIterator.hasNext()) {
                Location location = locationIterator.next();

                // Location world name
                dataOutputStream.writeUTF(location.getWorld().getName());

                // Location x,y,z,yaw,pitch
                dataOutputStream.writeDouble(location.getX());
                dataOutputStream.writeDouble(location.getY());
                dataOutputStream.writeDouble(location.getZ());
                dataOutputStream.writeFloat(location.getYaw());
                dataOutputStream.writeFloat(location.getPitch());

                boolean last = !locationIterator.hasNext();
                if (last) {
                    getNpcUser().setHasPath(false);

                    // Create path
                    ZNPCPathReader.register(file);
                }
            }
        }
    }

    /**
     * Checks if a location can be added to path.
     *
     * @param location      The path location to add.
     * @return {@code true} If location can be added.
     */
    public boolean checkEntry(Location location) {
        if (locationsCache.isEmpty())
            return true;

        Location last = locationsCache.get(locationsCache.size() - 1);

        double xDiff = Math.abs(last.getX() - location.getX());
        double yDiff = Math.abs(last.getY() - location.getY());
        double zDiff = Math.abs(last.getZ() - location.getZ());

        return (xDiff + yDiff + zDiff) > 0.01;
    }

    /**
     * Returns the player who is creating the path.
     *
     * @return The player who is creating the path.
     */
    public Player getPlayer() {
        return getNpcUser().toPlayer();
    }
}
