package com.dc.perms;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PermsPlugin extends JavaPlugin implements Listener {

    private String apiUrl;
    private long updateIntervalSeconds;
    private String defaultRole;

    private final Map<String, Map<String, Long>> roles = new ConcurrentHashMap<>();
    private final Map<String, String> playerRoles = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        updatePermissions();
        long ticks = updateIntervalSeconds * 20L;
        new BukkitRunnable() {
            @Override
            public void run() {
                updatePermissions();
            }
        }.runTaskTimerAsynchronously(this, ticks, ticks);
        getLogger().info("PermsPlugin включён!");
    }

    @Override
    public void onDisable() {
        getLogger().info("PermsPlugin выключен.");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        apiUrl = config.getString("api-url", "http://example.com/api/permissions");
        updateIntervalSeconds = config.getLong("update-interval-seconds", 60L);
        defaultRole = config.getString("default-role", "default");
        config.set("api-url", apiUrl);
        config.set("update-interval-seconds", updateIntervalSeconds);
        config.set("default-role", defaultRole);
        saveConfig();
    }

    private void updatePermissions() {
        getLogger().info("Запрос прав к API: " + apiUrl);
        try {
            String response = fetchApiData(apiUrl);
            if (response == null || response.isEmpty()) {
                getLogger().warning("Ответ от API пустой или null");
                return;
            }
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            roles.clear();
            playerRoles.clear();

            JsonObject rolesObj = root.getAsJsonObject("roles");
            if (rolesObj != null) {
                for (Map.Entry<String, JsonElement> roleEntry : rolesObj.entrySet()) {
                    String roleName = roleEntry.getKey();
                    JsonObject commandsObj = roleEntry.getValue().getAsJsonObject();
                    Map<String, Long> commandCooldowns = new HashMap<>();
                    for (Map.Entry<String, JsonElement> cmdEntry : commandsObj.entrySet()) {
                        String command = cmdEntry.getKey();
                        long cooldown = cmdEntry.getValue().getAsLong();
                        if (!command.startsWith("/")) {
                            command = "/" + command;
                        }
                        commandCooldowns.put(command, cooldown);
                    }
                    roles.put(roleName, commandCooldowns);
                }
            }

            JsonObject playersObj = root.getAsJsonObject("players");
            if (playersObj != null) {
                for (Map.Entry<String, JsonElement> playerEntry : playersObj.entrySet()) {
                    String playerName = playerEntry.getKey();
                    String roleName = playerEntry.getValue().getAsString();
                    playerRoles.put(playerName.toLowerCase(), roleName);
                }
            }
            getLogger().info("Данные обновлены. Ролей: " + roles.size() + ", игроков: " + playerRoles.size());
        } catch (Exception e) {
            getLogger().severe("Ошибка при обновлении данных: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String fetchApiData(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP ошибка: " + responseCode);
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        conn.disconnect();
        return response.toString();
    }

    private String getPlayerRole(Player player) {
        String role = playerRoles.get(player.getName().toLowerCase());
        return role != null ? role : defaultRole;
    }

    private boolean isCommandAllowed(Player player, String command) {
        String role = getPlayerRole(player);
        Map<String, Long> roleCommands = roles.get(role);
        if (roleCommands == null) return false;
        return roleCommands.containsKey(command);
    }

    private long getCooldownForCommand(String role, String command) {
        Map<String, Long> roleCommands = roles.get(role);
        if (roleCommands == null) return 0;
        return roleCommands.getOrDefault(command, 0L);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String fullCommand = event.getMessage();
        String command = fullCommand.split(" ")[0];
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        if (!isCommandAllowed(player, command)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды.");
            return;
        }

        String role = getPlayerRole(player);
        long cooldownMillis = getCooldownForCommand(role, command);
        if (cooldownMillis > 0) {
            UUID uuid = player.getUniqueId();
            Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
            long now = System.currentTimeMillis();
            Long lastUsed = playerCooldowns.get(command);
            if (lastUsed != null && (now - lastUsed) < cooldownMillis) {
                long remaining = cooldownMillis - (now - lastUsed);
                long seconds = remaining / 1000;
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Подождите " + seconds + " секунд перед повторным использованием команды.");
                return;
            }
            playerCooldowns.put(command, now);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("perms")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Использование: /perms update | /perms check <игрок>");
            return true;
        }
        if (args[0].equalsIgnoreCase("update")) {
            if (!sender.hasPermission("perms.admin")) {
                sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Запрос обновления...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    updatePermissions();
                    Bukkit.getScheduler().runTask(PermsPlugin.this, () -> {
                        sender.sendMessage(ChatColor.GREEN + "Обновление завершено.");
                    });
                }
            }.runTaskAsynchronously(this);
            return true;
        }
        if (args[0].equalsIgnoreCase("check")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Укажите имя игрока: /perms check <игрок>");
                return true;
            }
            String playerName = args[1];
            String role = playerRoles.get(playerName.toLowerCase());
            if (role == null) {
                role = defaultRole + " (по умолчанию)";
            }
            sender.sendMessage(ChatColor.GREEN + "Роль игрока " + playerName + ": " + role);
            Map<String, Long> commands = roles.get(role);
            if (commands != null && !commands.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Разрешённые команды:");
                for (Map.Entry<String, Long> entry : commands.entrySet()) {
                    String cmd = entry.getKey();
                    long cd = entry.getValue();
                    String cdStr = cd > 0 ? " (задержка " + cd/1000 + "с)" : "";
                    sender.sendMessage(ChatColor.WHITE + " - " + cmd + cdStr);
                }
            } else {
                sender.sendMessage(ChatColor.RED + "У роли нет разрешённых команд.");
            }
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда. Используйте /perms update или /perms check");
        return true;
    }
}
