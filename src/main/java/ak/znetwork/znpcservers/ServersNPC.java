package ak.znetwork.znpcservers;

import ak.znetwork.znpcservers.commands.ZNCommand;
import ak.znetwork.znpcservers.commands.list.DefaultCommand;
import ak.znetwork.znpcservers.configuration.ZNConfig;
import ak.znetwork.znpcservers.listeners.PlayerListeners;
import ak.znetwork.znpcservers.utility.location.ZLocation;
import ak.znetwork.znpcservers.manager.CommandsManager;
import ak.znetwork.znpcservers.manager.ConfigManager;
import ak.znetwork.znpcservers.manager.NPCManager;
import ak.znetwork.znpcservers.tasks.NPCManagerTask;
import ak.znetwork.znpcservers.npc.ZNPC;
import ak.znetwork.znpcservers.npc.enums.NPCType;
import ak.znetwork.znpcservers.npc.path.ZNPCPathReader;
import ak.znetwork.znpcservers.tasks.NPCSaveTask;
import ak.znetwork.znpcservers.types.ConfigTypes;
import ak.znetwork.znpcservers.user.ZNPCUser;
import ak.znetwork.znpcservers.utility.location.ZLocationSerialize;
import ak.znetwork.znpcservers.utility.MetricsLite;
import ak.znetwork.znpcservers.npc.skin.ZNPCSkin;

import ak.znetwork.znpcservers.utility.SchedulerUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.logging.Level;

import lombok.Getter;

/**
 * <p>Copyright (c) ZNetwork, 2020.</p>
 *
 * @author ZNetwork
 * @since 07/02/2020
 */
@Getter
public class ServersNPC extends JavaPlugin {

    /**
     * The plugin name.
     */
    private static final String PLUGIN_NAME = "ServersNPC";

    /**
     * The plugin folder.
     */
    public static final File PLUGIN_FOLDER = new File("plugins/" + PLUGIN_NAME);

    static {
        // Create the plugin folder if it doesn't exist.
        PLUGIN_FOLDER.mkdirs();
    }

    /**
     * The plugin metrics id.
     */
    private static final int PLUGIN_ID = 8054;

    /**
     * Creates a new Gson instance with
     * custom type adapters.
     */
    public final static Gson GSON = new GsonBuilder().
            registerTypeAdapter(ZLocation.class, new ZLocationSerialize()).
            excludeFieldsWithoutExposeAnnotation().
                    setPrettyPrinting().
                    create();

    /**
     * The scheduler instance.
     */
    public static SchedulerUtils SCHEDULER;

    /**
     * The commands manager.
     */
    private CommandsManager commandsManager;

    @Override
    public void onEnable() {
        // Load entity type cache
        for (NPCType npcType : NPCType.values()) {
            npcType.load();
        }

        // Load paths
        loadAllPaths();

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // Load commands
        commandsManager = new CommandsManager(this, "znpcs");
        commandsManager.addCommand(new ZNCommand(new DefaultCommand(this)));

        // Setup metrics
        new MetricsLite(this, PLUGIN_ID);

        // Default executor
        SCHEDULER = new SchedulerUtils(this);

        // Setup netty again for online players
        Bukkit.getOnlinePlayers().forEach(ServersNPC.this::setupNetty);

        // Init NPC task
        new NPCManagerTask(this);
        new NPCSaveTask(this, ConfigTypes.SAVE_DELAY);

        // Register listeners
        new PlayerListeners(this);
    }

    @Override
    public void onDisable() {
        // Save configurations
        ConfigManager.getConfigurations().forEach(ZNConfig::save);

        // Delete all npc for viewers
        removeAllViewers();
    }

    /**
     * Loads all npc paths.
     */
    public void loadAllPaths() {
        File npcPaths = PLUGIN_FOLDER.toPath().resolve("paths").toFile();
        if (!npcPaths.exists()) npcPaths.mkdirs();

        File[] listFiles = npcPaths.listFiles();
        if (listFiles == null) return;

        for (File file : listFiles) {
            // Check if file is path
            if (file.getName().endsWith(".path")) {
                try {
                    ZNPCPathReader.register(file);
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, String.format("The path %s could not be loaded", file.getName()));
                }
            }
        }
    }

    /**
     * Deletes all NPC for viewers.
     */
    public void removeAllViewers() {
        ConfigTypes.NPC_LIST.forEach(ZNPC::deleteViewers);
    }

    /**
     * Setup netty for player.
     *
     * @param player The player.
     */
    public void setupNetty(Player player) {
        try {
            NPCManager.getNpcUsers().add(new ZNPCUser(this, player));
        } catch (Exception exception) {
            throw new RuntimeException("An exception occurred while trying to setup netty for player " + player.getName(), exception);
        }
    }

    /**
     * Creates a new npc.
     *
     * @param id       The npc identifier.
     * @param npcType  The npc entity type.
     * @param location The npc location.
     * @param name     The npc skin name.
     * @return         The created zNPC.
     */
    public ZNPC createNPC(int id, NPCType npcType, Location location, String name) {
        if (ConfigTypes.NPC_LIST.stream().anyMatch(npc -> npc.getId() == id))
            return null;

        ZNPC znpc = new ZNPC(id, name, ZNPCSkin.forName(name), new ZLocation(location), npcType);
        ConfigTypes.NPC_LIST.add(znpc);

        return znpc;
    }

    /**
     * Deletes a npc.
     *
     * @param npcID The npc ID.
     */
    public void deleteNPC(int npcID) {
        ZNPC npc = ConfigTypes.NPC_LIST.stream().filter(npc1 -> npc1.getId() == npcID).findFirst().orElse(null);

        if (npc == null)
            return;

        ConfigTypes.NPC_LIST.remove(npc);

        npc.deleteViewers();
    }

    /**
     * Sends a player to a bungee server.
     *
     * @param player The player to send to the server.
     * @param server The server name.
     */
    public void sendPlayerToServer(Player player, String server) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);

        try {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            e.printStackTrace();
        }
        player.sendPluginMessage(this, "BungeeCord", b.toByteArray());
    }
}
