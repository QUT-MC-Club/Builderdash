package io.github.foundationgames.builderdash.mixin;

import io.github.foundationgames.builderdash.Builderdash;
import io.github.foundationgames.builderdash.config.PlayerConfigInfo;
import io.github.foundationgames.builderdash.config.ServerConfigAccess;
import io.github.foundationgames.builderdash.config.ServerConfigInfo;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin implements ServerConfigAccess {
    @Unique private ServerConfigInfo serverConfig = null;
    @Unique private final Map<UUID, PlayerConfigInfo> playerConfigs = new HashMap<>();

    @Override
    public ServerConfigInfo getServerConfig() {
        var server = (MinecraftServer) (Object) this;

        if (serverConfig == null) {
            serverConfig = ServerConfigInfo.forServer(server);
            try {
                serverConfig.config.load();
            } catch (IOException e) {
                Builderdash.LOG.error(e);
            }
        }

        return serverConfig;
    }

    @Override
    public PlayerConfigInfo getPlayerConfig(UUID player) {
        var server = (MinecraftServer) (Object) this;

        return playerConfigs.computeIfAbsent(player, uuid -> {
            var playerConfig = PlayerConfigInfo.forServer(uuid, server);
            try {
                playerConfig.config.load();
            } catch (IOException e) {
                Builderdash.LOG.error(e);
            }

            return playerConfig;
        });
    }
}
