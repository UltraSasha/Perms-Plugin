package com.dc.perms;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PermsPlugin extends JavaPlugin implements Listener {

    private String source;          // "api" или "local"
    private String apiUrl;
    private long updateIntervalSeconds;
    private String defaultRole;
    private List<String> updateTimes;
    private boolean opRoleEnabled;
    private String opRoleName;
    private List<String> timeRequiredCommands;

    private final Map<String, Map<String, Long>> roles = new ConcurrentHashMap<>();
    private final Map<String, String> playerRoles = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    private BukkitTask scheduledTask = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);

        // Загружаем права (из API или локально)
        updatePermissions();

        // Запускаем расписание только если source == "api"
        if ("api".equalsIgnoreCase(source)) {
            scheduleNextUpdate();
        } else {
            getLogger().info("Режим 'local' – автоматическое обновление отключено. Используйте /perms update для перезагрузки конфига.");
        }

        getLogger().info("PermsPlugin включён! Режим: " + source);
    }

    @Override
    public void onDisable() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        getLogger().info("PermsPlugin выключен.");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        source = config.getString("source", "api").toLowerCase();
        apiUrl = config.getString("api-url", "http://example.com/api/permissions");
        updateIntervalSeconds = config.getLong("update-interval-seconds", 60L);
        defaultRole = config.getString("default-role", "default");
        updateTimes = config.getStringList("update-times");
        opRoleEnabled = config.getBoolean("op-role-enabled", true);
        opRoleName = config.getString("op-role-name", "OP");
        timeRequiredCommands = config.getStringList("time-required-commands");

        // Сохраняем настройки обратно в конфиг, чтобы они были видны
        config.set("source", source);
        config.set("api-url", apiUrl);
        config.set("update-interval-seconds", updateIntervalSeconds);
        config.set("default-role", defaultRole);
        config.set("update-times", updateTimes);
        config.set("op-role-enabled", opRoleEnabled);
        config.set("op-role-name", opRoleName);
        config.set("time-required-commands", timeRequiredCommands);
        saveConfig();
    }

    private void scheduleNextUpdate() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }

        // Если режим не "api", не запускаем расписание
        if (!"api".equalsIgnoreCase(source)) {
            return;
        }

        if (updateTimes != null && !updateTimes.isEmpty()) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT"));
            List<LocalTime> times = new ArrayList<>();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            for (String timeStr : updateTimes) {
                try {
                    LocalTime lt = LocalTime.parse(timeStr, formatter);
                    times.add(lt);
                } catch (Exception e) {
                    getLogger().warning("Некорректное время в update-times: " + timeStr);
                }
            }
            if (times.isEmpty()) {
                getLogger().warning("Нет корректных времён в update-times, переключаюсь на интервальный режим");
                scheduleIntervalUpdate();
                return;
            }

            ZonedDateTime next = null;
            for (LocalTime lt : times) {
                ZonedDateTime candidate = now.with(lt);
                if (candidate.isBefore(now) || candidate.equals(now)) {
                    candidate = candidate.plusDays(1);
                }
                if (next == null || candidate.isBefore(next)) {
                    next = candidate;
                }
            }

            if (next == null) {
                getLogger().warning("Не удалось определить следующее время обновления");
                scheduleIntervalUpdate();
                return;
            }

            long delayMillis = next.toInstant().toEpochMilli() - System.currentTimeMillis();
            if (delayMillis < 0) delayMillis = 0;
            long delayTicks = delayMillis / 50;

            getLogger().info("Следующее обновление запланировано на " + next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " GMT (через " + (delayMillis / 1000) + " секунд)");

            scheduledTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                updatePermissions();
                scheduleNextUpdate();
            }, delayTicks);
        } else {
            scheduleIntervalUpdate();
        }
    }

    private void scheduleIntervalUpdate() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        // Если режим не "api", не запускаем интервальное обновление
        if (!"api".equalsIgnoreCase(source)) {
            return;
        }
        long ticks = updateIntervalSeconds * 20L;
        if (ticks <= 0) {
            getLogger().warning("Интервал обновления равен 0, обновления по расписанию отключены");
            return;
        }
        getLogger().info("Включён интервальный режим обновления (каждые " + updateIntervalSeconds + " секунд)");
        scheduledTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            updatePermissions();
        }, ticks, ticks);
    }

    private void updatePermissions() {
        if ("local".equalsIgnoreCase(source)) {
            // Загружаем из конфига
            loadLocalPermissions();
        } else {
            // Загружаем из API
            fetchPermissionsFromApi();
        }
    }

    private void loadLocalPermissions() {
        getLogger().info("Загрузка прав из локального конфига...");
        FileConfiguration config = getConfig();
        ConfigurationSection rolesSection = config.getConfigurationSection("local-roles");
        ConfigurationSection playersSection = config.getConfigurationSection("local-players");

        roles.clear();
        playerRoles.clear();

        if (rolesSection != null) {
            for (String roleName : rolesSection.getKeys(false)) {
                ConfigurationSection commandsSection = rolesSection.getConfigurationSection(roleName);
                if (commandsSection != null) {
                    Map<String, Long> cmdMap = new HashMap<>();
                    for (String command : commandsSection.getKeys(false)) {
                        long cooldown = commandsSection.getLong(command, 0);
                        String cmd = command.trim().toLowerCase();
                        if (!cmd.startsWith("/")) {
                            cmd = "/" + cmd;
                        }
                        cmdMap.put(cmd, cooldown);
                    }
                    roles.put(roleName, cmdMap);
                }
            }
        }

        if (playersSection != null) {
            for (String playerName : playersSection.getKeys(false)) {
                String roleName = playersSection.getString(playerName);
                if (roleName != null) {
                    playerRoles.put(playerName.toLowerCase(), roleName);
                }
            }
        }

        getLogger().info("Локальные данные загружены. Ролей: " + roles.size() + ", игроков: " + playerRoles.size());
    }

    private void fetchPermissionsFromApi() {
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
                        command = command.trim().toLowerCase();
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
            getLogger().severe("Ошибка при обновлении данных из API: " + e.getMessage());
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
        if (opRoleEnabled && player.isOp()) {
            return opRoleName;
        }
        String role = playerRoles.get(player.getName().toLowerCase());
        return role != null ? role : defaultRole;
    }

    private boolean hasTimeArgument(String command) {
        return command.matches(".*\\d+[smhd].*");
    }

    private boolean isCommandAllowed(Player player, String fullCommand) {
        String cmd = fullCommand.trim().toLowerCase();
        String baseCmd = cmd.split(" ")[0];

        if (baseCmd.startsWith("/perms")) return true;
        if (opRoleEnabled && player.isOp()) return true;

        String role = getPlayerRole(player);
        if (role.equals(opRoleName) && opRoleEnabled) return true;

        Map<String, Long> roleCommands = roles.get(role);
        if (roleCommands == null) return false;

        boolean allowed = roleCommands.containsKey(cmd) || roleCommands.containsKey(baseCmd);
        if (!allowed) return false;

        if (timeRequiredCommands.contains(baseCmd)) {
            if (!hasTimeArgument(cmd)) {
                return false;
            }
        }

        return true;
    }

    private long getCooldownForCommand(String role, String fullCommand) {
        String cmd = fullCommand.trim().toLowerCase();
        String baseCmd = cmd.split(" ")[0];

        if (baseCmd.startsWith("/perms")) return 0;
        if (opRoleEnabled && role.equals(opRoleName)) return 0;

        Map<String, Long> roleCommands = roles.get(role);
        if (roleCommands == null) return 0;

        if (roleCommands.containsKey(cmd)) {
            return roleCommands.get(cmd);
        }
        return roleCommands.getOrDefault(baseCmd, 0L);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String fullCommand = event.getMessage().trim();

        if (fullCommand.toLowerCase().startsWith("/perms")) {
            return;
        }

        if (!isCommandAllowed(player, fullCommand)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды.");
            return;
        }

        String role = getPlayerRole(player);
        long cooldownMillis = getCooldownForCommand(role, fullCommand);
        if (cooldownMillis > 0) {
            UUID uuid = player.getUniqueId();
            Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
            long now = System.currentTimeMillis();
            Long lastUsed = playerCooldowns.get(fullCommand.toLowerCase());
            if (lastUsed != null && (now - lastUsed) < cooldownMillis) {
                long remaining = cooldownMillis - (now - lastUsed);
                long seconds = remaining / 1000;
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Подождите " + seconds + " секунд перед повторным использованием команды.");
                return;
            }
            playerCooldowns.put(fullCommand.toLowerCase(), now);
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
                    // Перезагружаем конфиг и обновляем права
                    reloadConfig();
                    loadConfig();
                    updatePermissions();
                    Bukkit.getScheduler().runTask(PermsPlugin.this, () -> {
                        sender.sendMessage(ChatColor.GREEN + "Обновление завершено. Текущий режим: " + source);
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
            Player target = Bukkit.getPlayer(playerName);
            String role;
            boolean isOp = false;
            if (opRoleEnabled && target != null && target.isOp()) {
                role = opRoleName + " (оператор)";
                isOp = true;
            } else {
                String apiRole = playerRoles.get(playerName.toLowerCase());
                role = apiRole != null ? apiRole : defaultRole + " (по умолчанию)";
            }
            sender.sendMessage(ChatColor.GREEN + "Роль игрока " + playerName + ": " + role);
            if (isOp || (opRoleEnabled && role.startsWith(opRoleName))) {
                sender.sendMessage(ChatColor.GRAY + "Это ОП-роль – разрешены все команды без задержек.");
            } else {
                Map<String, Long> commands = roles.get(role);
                if (commands != null && !commands.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Разрешённые команды (точное или базовое совпадение):");
                    for (Map.Entry<String, Long> entry : commands.entrySet()) {
                        String cmd = entry.getKey();
                        long cd = entry.getValue();
                        String cdStr = cd > 0 ? " (задержка " + cd/1000 + "с)" : "";
                        sender.sendMessage(ChatColor.WHITE + " - " + cmd + cdStr);
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "У роли нет разрешённых команд.");
                }
            }
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда. Используйте /perms update или /perms check");
        return true;
    }
}
