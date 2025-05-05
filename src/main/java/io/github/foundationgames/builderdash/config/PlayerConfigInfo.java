package io.github.foundationgames.builderdash.config;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

public class PlayerConfigInfo {
    public static final Config PROTOTYPE = new PlayerConfigInfo(null).config;
    public static final ConfigCommand<ServerCommandSource> COMMAND =
            new ConfigCommand<>(PlayerConfigInfo::getConfigForCommand, PROTOTYPE);

    public final Config config;

    public final ConfigOption.IntOption musicVolume;

    public PlayerConfigInfo(Supplier<Path> file) {
        this.config = new Config("builderdash_player", file);

        this.musicVolume = this.config.optInt("music_volume", 100, 0, 200);
    }

    public static PlayerConfigInfo forServer(UUID uuid, MinecraftServer server) {
        return new PlayerConfigInfo(() -> server.getSavePath(WorldSavePath.ROOT)
                .resolve("builderdash/player_" + uuid.toString().toLowerCase(Locale.ROOT) + ".properties"));
    }

    public static Config getConfigForCommand(CommandContext<ServerCommandSource> context) {
        var player = context.getSource().getPlayer();
        if (player == null) {
            return PROTOTYPE;
        }

        return ServerConfigAccess.forServer(context.getSource().getServer()).getPlayerConfig(player.getUuid()).config;
    }
}
