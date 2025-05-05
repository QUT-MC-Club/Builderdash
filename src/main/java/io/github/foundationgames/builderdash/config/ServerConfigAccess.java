package io.github.foundationgames.builderdash.config;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public interface ServerConfigAccess {
    ServerConfigInfo getServerConfig();

    PlayerConfigInfo getPlayerConfig(UUID player);

    static @NotNull ServerConfigAccess forServer(MinecraftServer server) {
        return (ServerConfigAccess) server;
    }
}
