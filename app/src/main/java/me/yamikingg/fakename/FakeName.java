package me.yamikingg.fakename;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public class FakeName extends JavaPlugin implements Listener {

    private FileConfiguration cfg;
    private Scoreboard scoreboard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("FakeName enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("FakeName disabled.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String realName = player.getName();
        String path = "players." + realName + ".fakename";

        // load or create fakename only once and persist it
        if (!cfg.contains(path)) {
            String defaultFake = "Anon" + (int) (Math.random() * 9000 + 1000);
            cfg.set(path, defaultFake);
            saveConfig();
            getLogger().info("Assigned default fake name " + defaultFake + " to " + realName);
        }
        String fakeName = cfg.getString(path, realName);

        // ensure we respect tab length limit
        String tabName = fakeName;
        if (tabName.length() > 16) tabName = tabName.substring(0, 16);

        // 1) chat display name (used by some plugins/places)
        player.setDisplayName(ChatColor.RESET + fakeName);

        // 2) tab list name (exactly what appears when pressing TAB)
        try {
            player.setPlayerListName(tabName);
        } catch (IllegalArgumentException ex) {
            player.setPlayerListName(tabName.substring(0, Math.min(16, tabName.length())));
        }

        // 3) nametag above the head using scoreboard team
        setNametag(player, fakeName);

        // 4) Replace the default join message so it never shows prefix+realname
        event.setJoinMessage(ChatColor.RESET + fakeName + " joined the game");
    }

    private void setNametag(Player player, String fakeName) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "fake_" + player.getName();
        if (teamName.length() > 16) teamName = teamName.substring(0, 16);

        Team team = sb.getTeam(teamName);
        if (team == null) {
            team = sb.registerNewTeam(teamName);
            // ensure we don't append the real name again — we use only prefix for visual name
            team.setAllowFriendlyFire(true); // optional
        }

        // Remove this specific player from any other team that might contain them.
        // (A player can only be entry of one team for display reasons.)
        for (Team t : sb.getTeams()) {
            if (t.hasEntry(player.getName()) && t != team) {
                t.removeEntry(player.getName());
            }
        }

        // Add player to this team (if not already)
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        // Use prefix to show the fake name. Keep suffix empty.
        // If fakeName is too long for visible niceties, you can split with prefix/suffix.
        if (fakeName.length() > 32) { // safe guard for very long names
            fakeName = fakeName.substring(0, 32);
        }
        team.setPrefix(ChatColor.RESET + fakeName);
        team.setSuffix(""); // avoid accidentally appending the real name elsewhere

        // assign scoreboard to player so name changes update for them
        player.setScoreboard(sb);
    }
}