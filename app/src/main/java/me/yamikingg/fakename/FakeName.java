package me.yamikingg.fakename;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class FakeName extends JavaPlugin implements Listener {

    private FileConfiguration cfg;
    private File configFile;
    private final Map<UUID, String> fakeNameMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> uuidToRealNameMap = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Create data folder if it doesn't exist
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Save and load config properly
        saveDefaultConfig();
        configFile = new File(getDataFolder(), "config.yml");
        cfg = YamlConfiguration.loadConfiguration(configFile);

        // Preload fake names from config using UUIDs for better reliability
        preloadFakeNames();

        // Register events and commands
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Register command (add to plugin.yml)
        if (getCommand("fakename") != null) {
            getCommand("fakename").setExecutor(this);
        }

        getLogger().info("FakeName enabled!");
    }

    @Override
    public void onDisable() {
        // Clean up all teams for online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            cleanupTeam(player);
        }
        
        getLogger().info("FakeName disabled.");
        fakeNameMap.clear();
        uuidToRealNameMap.clear();
    }

    private void preloadFakeNames() {
        if (cfg.isConfigurationSection("players")) {
            for (String uuidString : cfg.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    String path = "players." + uuidString + ".fakename";
                    if (cfg.contains(path)) {
                        String fakeName = cfg.getString(path);
                        if (fakeName != null && !fakeName.trim().isEmpty()) {
                            fakeNameMap.put(uuid, fakeName);
                        }
                        
                        // Store real name for reference
                        String realNamePath = "players." + uuidString + ".realname";
                        if (cfg.contains(realNamePath)) {
                            String realName = cfg.getString(realNamePath);
                            if (realName != null) {
                                uuidToRealNameMap.put(uuid, realName);
                            }
                        }
                    }
                } catch (IllegalArgumentException e) {
                    // Handle legacy username-based entries
                    migrateLegacyEntry(uuidString);
                }
            }
        }
        getLogger().info("Loaded " + fakeNameMap.size() + " fake names from config.");
    }

    private void migrateLegacyEntry(String username) {
        String path = "players." + username + ".fakename";
        if (cfg.contains(path)) {
            String fakeName = cfg.getString(path);
            
            if (fakeName != null && !fakeName.trim().isEmpty()) {
                // Try to find player by name (online or offline)
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
                if (offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline()) {
                    UUID uuid = offlinePlayer.getUniqueId();
                    fakeNameMap.put(uuid, fakeName);
                    uuidToRealNameMap.put(uuid, username);
                    
                    // Update config to use UUID
                    cfg.set("players." + username, null);
                    cfg.set("players." + uuid.toString() + ".fakename", fakeName);
                    cfg.set("players." + uuid.toString() + ".realname", username);
                    
                    saveFakeNameConfig();
                    getLogger().info("Migrated legacy entry for player: " + username);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String realName = player.getName();

        // Update real name mapping
        uuidToRealNameMap.put(uuid, realName);

        // Check if player has a fake name
        if (fakeNameMap.containsKey(uuid)) {
            String fakeName = fakeNameMap.get(uuid);
            
            // Apply fake name after a short delay to ensure everything is loaded
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        applyFakeName(player, fakeName);
                    }
                }
            }.runTaskLater(this, 5L); // 5 ticks = 0.25 seconds delay

            // Update join message with fake name
            if (event.joinMessage() != null) {
                Component joinMessage = Component.text(fakeName + " joined the game");
                event.joinMessage(joinMessage);
            }
        } else {
            // Remove any previous mapping and reset to default
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        resetPlayerDisplay(player);
                    }
                }
            }.runTaskLater(this, 5L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Update quit message if player has fake name
        if (fakeNameMap.containsKey(uuid)) {
            String fakeName = fakeNameMap.get(uuid);
            if (event.quitMessage() != null) {
                Component quitMessage = Component.text(fakeName + " left the game");
                event.quitMessage(quitMessage);
            }
        }

        // Clean up team when player leaves
        cleanupTeam(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
    
        Player sender = event.getPlayer();
        String fakeName = fakeNameMap.get(sender.getUniqueId());
    
    if (fakeName != null && !fakeName.isEmpty()) {
        // Cancel the original event and send custom formatted message
        event.setCancelled(true);
        
        // Get the original message as plain text
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        
        // Create formatted message
        String formattedMessage = "<" + fakeName + "> " + message;
        
        // Create component and send to all viewers
        Component messageComponent = Component.text(formattedMessage);
        
        // Send to all viewers (recipients) - FIXED: Handle Audience objects properly
            for (Audience audience : event.viewers()) {
                try {
                    if (audience instanceof Player) {
                        Player recipient = (Player) audience;
                        recipient.sendMessage(messageComponent);
                    } else if (audience instanceof CommandSender) {
                        // Handle console and other command senders
                        ((CommandSender) audience).sendMessage(formattedMessage);
                    } else {
                        // Fallback for other audience types
                        audience.sendMessage(messageComponent);
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Failed to send message to recipient", e);
                }
            }
        
        // Send to console as plain text (console is already included in viewers, but just in case)
            try {
                Bukkit.getConsoleSender().sendMessage(formattedMessage);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to send message to console", e);
            }
        }
    }

    private void applyFakeName(Player player, String fakeName) {
        if (!player.isOnline() || fakeName == null || fakeName.trim().isEmpty()) {
            return;
        }

        try {
            // 1) Display name
            player.displayName(Component.text(fakeName));

            // 2) Tab-list name (limited to 16 chars for older versions)
            String tabName = fakeName.length() > 16 ? fakeName.substring(0, 16) : fakeName;
            player.playerListName(Component.text(tabName));

            // 3) Nametag above head using scoreboard team
            setNametag(player, fakeName);
            
            getLogger().info("Applied fake name '" + fakeName + "' to player " + player.getName());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error applying fake name for player " + player.getName(), e);
        }
    }

    private void resetPlayerDisplay(Player player) {
        if (!player.isOnline()) {
            return;
        }

        try {
            String realName = player.getName();
            
            // Reset display names
            player.displayName(Component.text(realName));
            player.playerListName(Component.text(realName));
            
            // Reset nametag
            resetNametag(player);
            
            getLogger().info("Reset display for player " + realName);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error resetting display for player " + player.getName(), e);
        }
    }

    private void setNametag(Player player, String fakeName) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            if (sb == null) {
                getLogger().warning("Main scoreboard is null, cannot set nametag");
                return;
            }

            // Create unique team name based on player UUID (truncated for team name limit)
            String teamName = "fn_" + player.getUniqueId().toString().replace("-", "").substring(0, 13);

            Team team = sb.getTeam(teamName);
            if (team == null) {
                team = sb.registerNewTeam(teamName);
            }

            // Set team prefix (limited to 16 chars for prefix in older versions)
            String prefix = fakeName.length() > 16 ? fakeName.substring(0, 16) : fakeName;
            team.prefix(Component.text(prefix));
            team.suffix(Component.text(""));

            // Configure team options
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            team.setAllowFriendlyFire(true);
            team.setCanSeeFriendlyInvisibles(false);

            // Remove player from other teams and add to this one
            removePlayerFromTeams(player, sb);
            team.addEntry(player.getName());

            // Ensure player is using the main scoreboard
            if (!player.getScoreboard().equals(sb)) {
                player.setScoreboard(sb);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error setting nametag for player " + player.getName(), e);
        }
    }

    private void resetNametag(Player player) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            if (sb != null) {
                removePlayerFromTeams(player, sb);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error resetting nametag for player " + player.getName(), e);
        }
    }

    private void cleanupTeam(Player player) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            if (sb != null) {
                String teamName = "fn_" + player.getUniqueId().toString().replace("-", "").substring(0, 13);
                Team team = sb.getTeam(teamName);
                if (team != null) {
                    team.unregister();
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error cleaning up team for player " + player.getName(), e);
        }
    }

    private void removePlayerFromTeams(Player player, Scoreboard sb) {
        try {
            for (Team team : sb.getTeams()) {
                if (team.hasEntry(player.getName())) {
                    team.removeEntry(player.getName());
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error removing player from teams: " + player.getName(), e);
        }
    }

    private void saveFakeNameConfig() {
        try {
            cfg.save(configFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save config to " + configFile, e);
        }
    }

    // Command handler
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("fakename")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage("Usage: /fakename <set|remove|list> [player] [name]");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "set":
                if (args.length < 3) {
                    sender.sendMessage("Usage: /fakename set <player> <fakename>");
                    return true;
                }
                return handleSetCommand(sender, args[1], args[2]);

            case "remove":
                if (args.length < 2) {
                    sender.sendMessage("Usage: /fakename remove <player>");
                    return true;
                }
                return handleRemoveCommand(sender, args[1]);

            case "list":
                return handleListCommand(sender);

            default:
                sender.sendMessage("Unknown subcommand. Use: set, remove, or list");
                return true;
        }
    }

    // Tab completion
    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("fakename")) {
            return null;
        }

        if (args.length == 1) {
            return java.util.Arrays.asList("set", "remove", "list");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("remove"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(java.util.stream.Collectors.toList());
        }

        return null;
    }

    private boolean handleSetCommand(CommandSender sender, String playerName, String fakeName) {
        if (!sender.hasPermission("fakename.set")) {
            sender.sendMessage("You don't have permission to use this command.");
            return true;
        }

        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("Player not found: " + playerName);
            return true;
        }

        setFakeName(target, fakeName);
        sender.sendMessage("Set fake name '" + fakeName + "' for player " + target.getName());
        return true;
    }

    private boolean handleRemoveCommand(CommandSender sender, String playerName) {
        if (!sender.hasPermission("fakename.remove")) {
            sender.sendMessage("You don't have permission to use this command.");
            return true;
        }

        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("Player not found: " + playerName);
            return true;
        }

        removeFakeName(target);
        sender.sendMessage("Removed fake name for player " + target.getName());
        return true;
    }

    private boolean handleListCommand(CommandSender sender) {
        if (!sender.hasPermission("fakename.list")) {
            sender.sendMessage("You don't have permission to use this command.");
            return true;
        }

        if (fakeNameMap.isEmpty()) {
            sender.sendMessage("No players currently have fake names.");
            return true;
        }

        sender.sendMessage("Players with fake names:");
        for (Map.Entry<UUID, String> entry : fakeNameMap.entrySet()) {
            String realName = uuidToRealNameMap.get(entry.getKey());
            sender.sendMessage("- " + (realName != null ? realName : "Unknown") + " -> " + entry.getValue());
        }
        return true;
    }

    // Public API methods for other plugins
    public void setFakeName(Player player, String fakeName) {
        if (player == null || fakeName == null || fakeName.trim().isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        fakeNameMap.put(uuid, fakeName);
        uuidToRealNameMap.put(uuid, player.getName());
        
        // Update config
        cfg.set("players." + uuid.toString() + ".fakename", fakeName);
        cfg.set("players." + uuid.toString() + ".realname", player.getName());
        saveFakeNameConfig();
        
        // Apply immediately if player is online
        if (player.isOnline()) {
            applyFakeName(player, fakeName);
        }
    }

    public void removeFakeName(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        fakeNameMap.remove(uuid);
        
        // Remove from config
        cfg.set("players." + uuid.toString(), null);
        saveFakeNameConfig();
        
        // Reset immediately if player is online
        if (player.isOnline()) {
            resetPlayerDisplay(player);
        }
    }

    public String getFakeName(Player player) {
        if (player == null) {
            return null;
        }
        return fakeNameMap.get(player.getUniqueId());
    }

    public String getRealName(UUID uuid) {
        return uuidToRealNameMap.get(uuid);
    }

    public boolean hasFakeName(Player player) {
        return player != null && fakeNameMap.containsKey(player.getUniqueId());
    }
}