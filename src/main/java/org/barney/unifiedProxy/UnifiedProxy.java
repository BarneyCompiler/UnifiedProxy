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

// Import for miniMessage
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;


import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
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


@Plugin(id = "unifiedproxy", name = "UnifiedProxy", version = "1.0",
        description = "Syncs player counts across multiple Velocity proxies using Redis.",
        authors = {"BarneyTheGod"})
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
    private RedisPubSubAsyncCommands<String, String> pubSubCommands;

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
            pubSubCommands = pubSubConnection.async();

            Component successMessage = miniMessage.deserialize("<green>Successfully connected to Redis!</green>");
            server.getConsoleCommandSource().sendMessage(successMessage);

            pubSubConnection.addListener(new RedisPubSubListener<String, String>() {
                @Override
                public void message(String channel, String message) {
                    if (channel.equals(REDIS_CHANNEL)) {
                        String[] parts = message.split(":");
                        if (parts.length == 2) {
                            String sourceProxyId = parts[0];
                            int playerCount = Integer.parseInt(parts[1]);
                            playerCounts.put(sourceProxyId, playerCount);
                            logger.debug("Received player count from " + sourceProxyId + ": " + playerCount);
                        }
                    }
                }

                @Override public void message(String pattern, String channel, String message) {}
                @Override public void subscribed(String channel, long count) {}
                @Override public void psubscribed(String pattern, long count) {}
                @Override public void unsubscribed(String channel, long count) {}
                @Override public void punsubscribed(String pattern, long count) {}
            });

            pubSubCommands.subscribe(REDIS_CHANNEL).thenRun(() -> {
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
                redisConfig = new RedisConfig();
                pluginConfig = new PluginConfig();
                return;
            }
        } else {
            logger.debug("Data directory already exists: " + dataDirectory);
        }

        Path configFile = dataDirectory.resolve("config.yaml");
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(configFile)
                .build();

        try {
            logger.debug("Checking if config file exists: " + configFile);
            if (!Files.exists(configFile)) {
                logger.info("Config file does not exist. Attempting to copy default from resources.");
                try (InputStream defaultConfigStream = getClass().getClassLoader().getResourceAsStream("config.yaml")) {
                    if (defaultConfigStream != null) {
                        Files.copy(defaultConfigStream, configFile);
                        logger.info("Created default config.yaml at " + configFile);
                    } else {
                        logger.warn("Default config.yaml not found in plugin resources. Cannot create default config.");

                    }
                } catch (IOException e) {
                    logger.error("Failed to copy default config file: " + e.getMessage());
                }
            } else {
                logger.debug("Config file already exists: " + configFile);
            }

            org.spongepowered.configurate.CommentedConfigurationNode root = loader.load();
            redisConfig = root.node("redis").get(RedisConfig.class);
            pluginConfig = root.node("plugin").get(PluginConfig.class);

            if (redisConfig == null) {
                logger.warn("Redis configuration section not found in config.yaml. Using default values.");
                redisConfig = new RedisConfig();
            }
            if (pluginConfig == null) {
                logger.warn("Plugin configuration section not found in config.yaml. Using default values.");
                pluginConfig = new PluginConfig();
            }

            logger.info("Configuration loaded successfully from " + configFile);
        } catch (ConfigurateException e) {
            logger.error("Failed to load config.yaml: " + e.getMessage());
            redisConfig = new RedisConfig();
            pluginConfig = new PluginConfig();
        }
    }


    private void sendHeartbeat() {
        int localPlayerCount = server.getPlayerCount();
        String message = proxyId + ":" + localPlayerCount;

        try {
            pubSubCommands.publish(REDIS_CHANNEL, message)
                    .exceptionally(throwable -> {
                        logger.error("Failed to publish heartbeat to Redis: " + throwable.getMessage());
                        return null;
                    });
            logger.debug("Sent heartbeat: " + message);

            connection.sync().setex(REDIS_KEY_PREFIX + proxyId, pluginConfig.heartbeatIntervalSeconds * 2, String.valueOf(localPlayerCount));
        } catch (Exception e) {
            logger.error("Error sending heartbeat: " + e.getMessage());
        }
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        // this adds up all players from all proxies
        int totalPlayers = 0;
        for (int count : playerCounts.values()) {
            totalPlayers += count;
        }

        if (!playerCounts.containsKey(proxyId)) {
            totalPlayers += server.getPlayerCount();
        }


        // overrides player count
        ServerPing.Builder builder = event.getPing().asBuilder();
        builder.onlinePlayers(totalPlayers);
        // you might want to adjust the maximum players
        // builder.maximumPlayers(event.getPing().getMaximumPlayers()); # you can keep original max players or set it dynamically

        event.setPing(builder.build());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("UnifiedProxy is shutting down. Closing Redis connections...");
        if (connection != null) {
            connection.close();
        }
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        logger.info("UnifiedProxy has shut down Redis connections.");
    }

    // config
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