package io.github.foundationgames.builderdash;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.foundationgames.builderdash.config.PlayerConfigInfo;
import io.github.foundationgames.builderdash.config.ServerConfigInfo;
import io.github.foundationgames.builderdash.game.BDCustomWordsConfig;
import io.github.foundationgames.builderdash.game.CustomWordsPersistentState;
import io.github.foundationgames.builderdash.game.lobby.BDLobbyActivity;
import io.github.foundationgames.builderdash.game.mode.pictionary.BDPictionaryConfig;
import io.github.foundationgames.builderdash.game.mode.pictionary.PictionaryCommand;
import io.github.foundationgames.builderdash.game.mode.telephone.BDTelephoneConfig;
import io.github.foundationgames.builderdash.game.mode.telephone.TelephoneCommand;
import io.github.foundationgames.builderdash.game.mode.versus.BDVersusConfig;
import io.github.foundationgames.builderdash.game.mode.versus.VersusCommand;
import io.github.foundationgames.builderdash.tools.BDToolsItems;
import io.github.foundationgames.builderdash.tools.BDToolsState;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.nucleoid.plasmid.api.game.GameTexts;
import xyz.nucleoid.plasmid.api.game.GameType;
import xyz.nucleoid.plasmid.api.game.config.CustomValuesConfig;
import xyz.nucleoid.plasmid.api.game.config.GameConfig;
import xyz.nucleoid.plasmid.api.game.config.GameConfigs;
import xyz.nucleoid.plasmid.impl.game.manager.GameSpaceManagerImpl;

public class Builderdash implements ModInitializer {
    public static final String ID = "builderdash";
    public static final Logger LOG = LogManager.getLogger(ID);

    public static final GameType<BDPictionaryConfig> PICTIONARY = GameType.register(
            id(BDPictionaryConfig.PICTIONARY),
            BDPictionaryConfig.CODEC,
            BDLobbyActivity::open
    );

    public static final GameType<BDTelephoneConfig> TELEPHONE = GameType.register(
            id(BDTelephoneConfig.TELEPHONE),
            BDTelephoneConfig.CODEC,
            BDLobbyActivity::open
    );

    public static final GameType<BDVersusConfig> VERSUS = GameType.register(
            id(BDVersusConfig.VERSUS),
            BDVersusConfig.CODEC,
            BDLobbyActivity::open
    );

    @SuppressWarnings("unchecked")
    public static int openBuilderdashGame(ServerCommandSource cmd, Identifier gameConfigId) {
        var server = cmd.getServer();
        var key = RegistryKey.of(GameConfigs.REGISTRY_KEY, gameConfigId);
        var registry = server.getRegistryManager().getOrThrow(GameConfigs.REGISTRY_KEY);
        var configEntry = registry.getOptional(key).orElse(null);

        if (configEntry == null) {
            LOG.error("Builtin game config {} not registered!", gameConfigId);
            return 1;
        }

        var value = configEntry.value();
        if (value != null) {
            if (value.config() instanceof BDCustomWordsConfig<?> config) {
                value = new GameConfig<>((GameType<Object>) value.type(), null, null, null, null, CustomValuesConfig.empty(),
                        config.withCustomWords(CustomWordsPersistentState.get(server, CustomWordsPersistentState.getKeyForGame(config.getGameName()))));
            }

            // TODO: Handle errors?
            GameSpaceManagerImpl.get().open(RegistryEntry.of(value)).thenAccept(space ->
                    server.getPlayerManager().broadcast(GameTexts.Broadcast.gameOpened(cmd, space), false));
            return 0;
        }

        LOG.error("Could not find builtin game config {}!", gameConfigId);
        return 1;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var cmd = dispatcher.register(createCommand(CommandManager.literal("builderdash")));
            dispatcher.register(CommandManager.literal("bd").redirect(cmd));
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> BDToolsState.onServerStart());

        BDToolsItems.init();
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createCommand(LiteralArgumentBuilder<ServerCommandSource> command) {
        return command
                .then(PictionaryCommand.createCommand(CommandManager.literal("pictionary")))
                .then(TelephoneCommand.createCommand(CommandManager.literal("telephone")))
                .then(VersusCommand.createCommand(CommandManager.literal("versus")))
                .then(CommandManager.literal("toolbox").executes(ctx -> {
                    var player = ctx.getSource().getPlayer();
                    BDToolsState.get(player).openToolbox(player);
                    return 0;
                }))
                .then(CommandManager.literal("config")
                        .then(ServerConfigInfo.COMMAND.command(CommandManager.literal("server")
                                        .requires(src -> Permissions.check(src, BDUtil.PERM_GLOBAL_CONFIG, 4)),
                                ServerCommandSource::sendMessage))
                        .then(PlayerConfigInfo.COMMAND.command(CommandManager.literal("player"),
                                ServerCommandSource::sendMessage))
                );
    }

    public static Identifier id(String path) {
        return Identifier.of(ID, path);
    }
}
