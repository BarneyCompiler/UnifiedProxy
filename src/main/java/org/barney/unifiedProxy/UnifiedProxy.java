package org.barney.unifiedProxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import org.slf4j.Logger;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Set;

@Plugin(id = "unifiedproxy", name = "UnifiedProxy", version = "1.0",
        description = "Syncs player counts across multiple Velocity proxies using Redis.",
        authors = {"BarneyTheGod", "SaigeDev"})
public class UnifiedProxy {

    @Inject
    private Logger logger;
    @Inject
    private ProxyServer server;
    @Inject
    @DataDirectory
    private Path dataDirectory;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private RedisPubSubAsyncCommands<String, String> pubSubAsyncCommands;
    private RedisCommands<String, String> redisSyncCommands;

    private String proxyId;
    private ConcurrentHashMap<String, Integer> playerCounts = new ConcurrentHashMap<>();

    private static final String REDIS_CHANNEL = "unifiedproxy:player_counts";
    private static final String REDIS_KEY_PREFIX = "unifiedproxy:proxy:";

    private RedisConfig redisConfig;
    private PluginConfig pluginConfig;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        Component startupMessage = miniMessage.deserialize(
                "<gradient:#00C9FF:#92FE9D><bold>UnifiedProxy</bold></gradient> <white>is initializing...</white>"
        );
        server.getConsoleCommandSource().sendMessage(startupMessage);

        loadConfig();

        this.proxyId = UUID.randomUUID().toString();
        logger.info("This proxy's ID: " + proxyId);

        try {
            URI redisUri = new URI("redis://" + (redisConfig.password.isEmpty() ? "" : redisConfig.password + "@") +
                    redisConfig.host + ":" + redisConfig.port + "/" + redisConfig.database);

            redisClient = RedisClient.create(redisUri.toString());
            connection = redisClient.connect();
            pubSubConnection = redisClient.connectPubSub();
            pubSubAsyncCommands = pubSubConnection.async();
            redisSyncCommands = connection.sync();

            Component successMessage = miniMessage.deserialize("<green>Successfully connected to Redis!</green>");
            server.getConsoleCommandSource().sendMessage(successMessage);

            pubSubConnection.addListener(new RedisPubSubListener<String, String>() {
                @Override
                public void message(String channel, String message) {
                    if (channel.equals(REDIS_CHANNEL)) {
                        String[] parts = message.split(":");
                        if (parts.length == 2) {
                            String sourceProxyId = parts[0];
                            try {
                                int playerCount = Integer.parseInt(parts[1]);
                                playerCounts.put(sourceProxyId, playerCount);
                                logger.debug("Received player count from " + sourceProxyId + ": " + playerCount);
                            } catch (NumberFormatException e) {
                                logger.warn("Received invalid player count format from Redis: " + message);
                            }
                        }
                    }
                }

                @Override public void message(String pattern, String channel, String message) {}
                @Override public void subscribed(String channel, long count) {}
                @Override public void psubscribed(String pattern, long count) {}
                @Override public void unsubscribed(String channel, long longCount) {}
                @Override public void punsubscribed(String pattern, long longCount) {}
            });

            pubSubAsyncCommands.subscribe(REDIS_CHANNEL).thenRun(() -> {
                Component subscribedMessage = miniMessage.deserialize("<gold>Subscribed to Redis channel: <yellow>" + REDIS_CHANNEL + "</yellow></gold>");
                server.getConsoleCommandSource().sendMessage(subscribedMessage);
            }).exceptionally(throwable -> {
                Component errorMessage = miniMessage.deserialize("<red>Failed to subscribe to Redis channel: <white>" + throwable.getMessage() + "</white></red>");
                server.getConsoleCommandSource().sendMessage(errorMessage);
                return null;
            });

            server.getScheduler().buildTask(this, this::sendHeartbeat)
                    .repeat(pluginConfig.heartbeatIntervalSeconds, TimeUnit.SECONDS)
                    .schedule();

            Component completeMessage = miniMessage.deserialize("<green><bold>UnifiedProxy initialization complete!</bold></green>");
            server.getConsoleCommandSource().sendMessage(completeMessage);

        } catch (Exception e) {
            Component errorMessage = miniMessage.deserialize("<red><bold>UnifiedProxy initialization failed!</bold></red> <white>" + e.getMessage() + "</white>");
            server.getConsoleCommandSource().sendMessage(errorMessage);
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        logger.debug("Attempting to load configuration...");

        if (!Files.exists(dataDirectory)) {
            logger.debug("Data directory does not exist. Creating directories: " + dataDirectory);
            try {
                Files.createDirectories(dataDirectory);
                logger.debug("Data directory created: " + dataDirectory);
            } catch (IOException e) {
                logger.error("Failed to create data directory: " + e.getMessage());
                useDefaultConfig();
                return;
            }
        } else {
            logger.debug("Data directory already exists: " + dataDirectory);
        }

        Path configFile = dataDirectory.resolve("config.yaml");
        logger.debug("Config file path: " + configFile);

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(configFile)
                .build();

        try {
            if (!Files.exists(configFile)) {
                logger.info("Config file does not exist. Creating default config at: " + configFile);
                createDefaultConfig(configFile);
            } else {
                logger.debug("Config file already exists: " + configFile);
            }

            if (Files.exists(configFile) && Files.size(configFile) > 0) {
                org.spongepowered.configurate.CommentedConfigurationNode root = loader.load();
                redisConfig = root.node("redis").get(RedisConfig.class);
                pluginConfig = root.node("plugin").get(PluginConfig.class);

                if (redisConfig == null) {
                    logger.warn("Redis configuration section not found in " + configFile + ". Using default values.");
                    redisConfig = new RedisConfig();
                }
                if (pluginConfig == null) {
                    logger.warn("Plugin configuration section not found in " + configFile + ". Using default values.");
                    pluginConfig = new PluginConfig();
                }
                logger.info("Configuration loaded successfully from " + configFile);
            } else {
                logger.warn("Config file doesn't exist or is empty after creation attempt. Using default values.");
                useDefaultConfig();
            }
        } catch (ConfigurateException e) {
            logger.error("Failed to load config from " + configFile + ": " + e.getMessage());
            useDefaultConfig();
        } catch (IOException e) {
            logger.error("IO error while loading config from " + configFile + ": " + e.getMessage());
            useDefaultConfig();
        }
    }

    private void createDefaultConfig(Path configFile) {
        try {
            try (InputStream defaultConfigStream = getClass().getClassLoader().getResourceAsStream("config.yaml")) {
                if (defaultConfigStream != null) {
                    Files.copy(defaultConfigStream, configFile);
                    logger.info("Created default config.yaml from plugin resources.");
                } else {
                    logger.warn("Default config.yaml not found in plugin resources. Generating a basic config file.");
                    String defaultConfig =
                            "# UnifiedProxy Plugin Configuration\n\n" +
                                    "redis:\n" +
                                    "  host: \"localhost\"\n" +
                                    "  port: 6379\n" +
                                    "  password: \"\"\n" +
                                    "  database: 0\n\n" +
                                    "plugin:\n" +
                                    "  heartbeat_interval_seconds: 5\n";

                    Files.writeString(configFile, defaultConfig);
                    logger.info("Created basic config.yaml with default values.");
                }
            }
        } catch (IOException e) {
            logger.error("Failed to create default config file at " + configFile + ": " + e.getMessage());
        }
    }

    private void useDefaultConfig() {
        logger.info("Using default configuration values for Redis and Plugin settings.");
        redisConfig = new RedisConfig();
        pluginConfig = new PluginConfig();
    }

    private void sendHeartbeat() {
        int localPlayerCount = server.getPlayerCount();
        String message = proxyId + ":" + localPlayerCount;

        try {
            pubSubAsyncCommands.publish(REDIS_CHANNEL, message)
                    .exceptionally(throwable -> {
                        logger.error("Failed to publish heartbeat to Redis PubSub: " + throwable.getMessage());
                        return null;
                    });
            logger.debug("Sent heartbeat PubSub message: " + message);

            redisSyncCommands.setex(REDIS_KEY_PREFIX + proxyId, pluginConfig.heartbeatIntervalSeconds * 2, String.valueOf(localPlayerCount));
            logger.debug("Stored local player count " + localPlayerCount + " in Redis key: " + REDIS_KEY_PREFIX + proxyId);

        } catch (Exception e) {
            logger.error("Error sending heartbeat: " + e.getMessage());
        }
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        int totalPlayers = 0;
        try {
            List<String> proxyKeys = redisSyncCommands.keys(REDIS_KEY_PREFIX + "*");
            logger.debug("Found " + proxyKeys.size() + " proxy keys in Redis.");

            for (String key : proxyKeys) {
                String playerCountStr = redisSyncCommands.get(key);
                if (playerCountStr != null && !playerCountStr.isEmpty()) {
                    try {
                        totalPlayers += Integer.parseInt(playerCountStr);
                        logger.debug("Adding " + playerCountStr + " from key " + key);
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid player count stored in Redis for key " + key + ": " + playerCountStr);
                    }
                }
            }
            logger.debug("Calculated total players from Redis keys: " + totalPlayers);

        } catch (Exception e) {
            logger.error("Error fetching player counts from Redis for MOTD: " + e.getMessage());
            totalPlayers = server.getPlayerCount();
            logger.info("Falling back to local player count for MOTD due to Redis error: " + totalPlayers);
        }

        ServerPing.Builder builder = event.getPing().asBuilder();
        builder.onlinePlayers(totalPlayers);

        event.setPing(builder.build());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        Component shutdownMessage = miniMessage.deserialize("<red>UnifiedProxy is shutting down. Closing Redis connections...</red>");
        server.getConsoleCommandSource().sendMessage(shutdownMessage);

        try {
            if (redisSyncCommands != null) {
                redisSyncCommands.del(REDIS_KEY_PREFIX + proxyId);
                logger.info("Removed proxy ID " + proxyId + " from Redis on shutdown.");
            }
        } catch (Exception e) {
            logger.warn("Failed to remove proxy ID from Redis on shutdown: " + e.getMessage());
        }

        if (connection != null) {
            connection.close();
        }
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        Component shutdownCompleteMessage = miniMessage.deserialize("<green>UnifiedProxy has shut down Redis connections.</green>");
        server.getConsoleCommandSource().sendMessage(shutdownCompleteMessage);
    }

    public static class RedisConfig {
        public String host = "localhost";
        public int port = 6379;
        public String password = "";
        public int database = 0;
    }

    public static class PluginConfig {
        public long heartbeatIntervalSeconds = 5;
    }
}