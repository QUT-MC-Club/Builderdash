package io.github.foundationgames.builderdash.game.lobby;

import io.github.foundationgames.builderdash.game.BDGameConfig;
import io.github.foundationgames.builderdash.game.BDPlayerLogic;
import io.github.foundationgames.builderdash.game.map.BuilderdashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.Blocks;
import net.minecraft.block.NoteBlock;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.joml.Quaternionf;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.GameOpenContext;
import xyz.nucleoid.plasmid.api.game.GameOpenProcedure;
import xyz.nucleoid.plasmid.api.game.GameResult;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.api.game.common.widget.BossBarWidget;
import xyz.nucleoid.plasmid.api.game.common.widget.SidebarWidget;
import xyz.nucleoid.plasmid.api.game.config.GameConfig;
import xyz.nucleoid.plasmid.api.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.api.game.player.JoinOffer;
import xyz.nucleoid.plasmid.api.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.api.util.PlayerRef;
import xyz.nucleoid.stimuli.event.EventResult;
import xyz.nucleoid.stimuli.event.block.BlockUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerC2SPacketEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;
import xyz.nucleoid.stimuli.event.world.ExplosionDetonatedEvent;

public class BDLobbyActivity<C extends BDGameConfig> {
    public static final Text WAITING = Text.translatable("text.plasmid.game.waiting_lobby.bar.waiting");
    public static final Text NOT_ENOUGH_PLAYERS = Text.translatable("label.builderdash.lobby.not_enough_players").formatted(Formatting.YELLOW);
    public static final Text[] NOT_ENOUGH_READY = {
            Text.translatable("label.builderdash.lobby.not_enough_ready.1").formatted(Formatting.YELLOW),
            Text.translatable("label.builderdash.lobby.not_enough_ready.2").formatted(Formatting.YELLOW)
    };
    public static final Text[] HALF_READY = {
            Text.translatable("label.builderdash.lobby.half_ready.1").formatted(Formatting.GREEN),
            Text.translatable("label.builderdash.lobby.half_ready.2").formatted(Formatting.GREEN)
    };
    public static final Text[] MAJORITY_READY = {
            Text.translatable("label.builderdash.lobby.majority_ready.1").formatted(Formatting.LIGHT_PURPLE),
            Text.translatable("label.builderdash.lobby.majority_ready.2").formatted(Formatting.LIGHT_PURPLE)
    };

    public static final String PLAYERS_READY = "label.builderdash.lobby.players_ready";
    public static final String STARTING_IN = "text.plasmid.game.waiting_lobby.bar.countdown";

    public static final int SEC = 20;

    public final GameSpace gameSpace;
    private final BuilderdashMap map;
    private final C config;
    private final BDPlayerLogic playerLogic;
    private final ServerWorld world;
    private final SidebarWidget scoreboard;
    private final BossBarWidget bossBar;

    private final Object2ObjectMap<PlayerRef, LobbyPlayer> players = new Object2ObjectOpenHashMap<>();
    private int timeUntilStart = Integer.MAX_VALUE;
    private float proportionReady = 0f;

    private boolean titleSpawned = false;

    private BDLobbyActivity(GameSpace gameSpace, GameActivity game, ServerWorld world, BuilderdashMap map, C config) {
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;
        this.world = world;
        this.playerLogic = new BDPlayerLogic(gameSpace, world, map);

        GlobalWidgets widgets = GlobalWidgets.addTo(game);

        var cfg = gameSpace.getMetadata().sourceConfig();
        this.scoreboard = widgets.addSidebar(GameConfig.shortName(cfg).copy().formatted(Formatting.GOLD));
        this.bossBar = widgets.addBossBar(WAITING, BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
    }

    public static <C extends BDGameConfig> GameOpenProcedure open(GameOpenContext<C> context) {
        BDGameConfig config = context.config();
        BuilderdashMap map = config.getMapConfig().buildMap(context.server());

        RuntimeWorldConfig worldConfig = new RuntimeWorldConfig()
                .setGenerator(map.asGenerator(context.server()));

        return context.openWithWorld(worldConfig, (game, world) -> {
            var lobby = new BDLobbyActivity<>(game.getGameSpace(), game, world, map, context.config());
            lobby.timeUntilStart = config.getLobbyConfig().countdown().fullSeconds() * SEC;

            game.deny(GameRuleType.PVP).deny(GameRuleType.FALL_DAMAGE).deny(GameRuleType.HUNGER)
                    .deny(GameRuleType.CRAFTING).deny(GameRuleType.PORTALS).deny(GameRuleType.THROW_ITEMS)
                    .deny(GameRuleType.PLACE_BLOCKS).deny(GameRuleType.BREAK_BLOCKS).deny(GameRuleType.UNSTABLE_TNT);

            game.allow(GameRuleType.INTERACTION);
            game.deny(GameRuleType.USE_ITEMS).deny(GameRuleType.USE_ENTITIES);
            game.listen(ExplosionDetonatedEvent.EVENT, (explosion, blocksToDestroy) -> EventResult.DENY);
            game.listen(BlockUseEvent.EVENT, (player, hand, hitResult) -> {
                var state = player.getWorld().getBlockState(hitResult.getBlockPos());
                if (state.isIn(BlockTags.BUTTONS) || state.isOf(Blocks.CHEST) || state.isOf(Blocks.BARREL)) {
                    return ActionResult.PASS;
                }

                return ActionResult.FAIL;
            });

            game.listen(PlayerC2SPacketEvent.EVENT, (player, packet) -> {
                if (packet instanceof CloseHandledScreenC2SPacket) {
                    var ref = PlayerRef.of(player);

                    var lp = lobby.players.get(ref);
                    if (lp != null) {
                        lp.gui.open();
                    }
                }

                return EventResult.PASS;
            });

            game.listen(GameActivityEvents.TICK, lobby::tick);
            game.listen(GameActivityEvents.REQUEST_START, lobby::requestStart);
            game.listen(GamePlayerEvents.ADD, lobby::addPlayer);
            game.listen(GamePlayerEvents.REMOVE, lobby::removePlayer);
            game.listen(GamePlayerEvents.OFFER, JoinOffer::pass);
            game.listen(GamePlayerEvents.ACCEPT, joinAcceptor -> joinAcceptor.teleport(world, Vec3d.ZERO));
            game.listen(PlayerDeathEvent.EVENT, lobby::onPlayerDeath);
        });
    }

    private GameResult requestStart() {
        this.players.values().forEach(LobbyPlayer::destroy);

        this.config.openActivity(this.gameSpace, this.world, this.map);
        return GameResult.ok();
    }

    private void tick() {
        var countdown = this.config.getLobbyConfig().countdown();

        if (!titleSpawned) {
            config.makeTitle(this.map.titlePos, 12, new Quaternionf().rotateLocalX(0.5f)).spawn(world);
            titleSpawned = true;
        }

        if (this.proportionReady < 0.5) {
            boolean update = this.timeUntilStart < countdown.fullSeconds() * SEC;
            this.timeUntilStart = countdown.fullSeconds() * SEC;

            if (update) {
                this.updateInfo();
            }
        } else {
            if (this.proportionReady >= 1) {
                this.requestStart();
            } else if (this.proportionReady >= 0.75) {
                this.timeUntilStart = Math.min(this.timeUntilStart, countdown.readySeconds() * SEC);
            } else {
                this.timeUntilStart = Math.min(this.timeUntilStart, countdown.fullSeconds() * SEC);
            }

            if (this.timeUntilStart % SEC == 0) {
                if (this.timeUntilStart <= 5 * SEC) {
                    this.gameSpace.getPlayers().playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                            SoundCategory.MASTER, 0.7f, NoteBlock.getNotePitch(9));
                }

                this.updateInfo();
            }

            this.timeUntilStart--;
            if (this.timeUntilStart <= 0) {
                this.requestStart();
            }
        }

        for (var player : this.players.values()) {
            player.tick();
        }
    }

    private void updateInfo() {
        this.scoreboard.clearLines();

        this.scoreboard.addLines(Text.empty());

        if (this.players.size() < this.config.getLobbyConfig().minPlayers()) {
            this.scoreboard.addLines(NOT_ENOUGH_PLAYERS);

            this.bossBar.setProgress(1);
            this.bossBar.setTitle(WAITING);
            this.bossBar.setStyle(BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
        } else {
            int ready = 0;
            for (var p : this.players.values()) {
                if (p.ready) ready++;
            }

            this.scoreboard.addLines(Text.translatable(PLAYERS_READY, ready, this.players.size()).formatted(Formatting.AQUA));
            this.scoreboard.addLines(Text.empty());

            boolean timerBar = true;

            if (proportionReady < 0.5) {
                this.scoreboard.addLines(NOT_ENOUGH_READY);
                timerBar = false;
            } else if (proportionReady < 0.75) {
                this.scoreboard.addLines(HALF_READY);
            } else {
                this.scoreboard.addLines(MAJORITY_READY);
            }

            this.bossBar.setProgress((float) this.timeUntilStart / (this.config.getLobbyConfig().countdown().fullSeconds() * SEC));
            this.bossBar.setTitle(timerBar ? Text.translatable(STARTING_IN, this.timeUntilStart / SEC)
                    : WAITING);
            this.bossBar.setStyle(BossBar.Color.BLUE, timerBar ? BossBar.Style.NOTCHED_20 : BossBar.Style.PROGRESS);
        }

        this.scoreboard.addLines(Text.empty());
    }

    public void checkCanStart() {
        if (this.players.size() < this.config.getLobbyConfig().minPlayers()) {
            this.proportionReady = 0;
        } else {
            int ready = 0;
            for (var p : this.players.values()) {
                if (p.ready) ready++;
            }

            this.proportionReady = (float) ready / this.players.size();
        }
        updateInfo();
    }

    private void addPlayer(ServerPlayerEntity player) {
        this.spawnPlayer(player);

        var ref = PlayerRef.of(player);
        this.players.put(ref, new LobbyPlayer(this.gameSpace, this, ref));
        this.checkCanStart();
    }

    private void removePlayer(ServerPlayerEntity player) {
        var ref = PlayerRef.of(player);

        var lp = this.players.remove(ref);
        if (lp != null) {
            lp.destroy();
        }
    }

    private EventResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        player.setHealth(20.0f);
        this.spawnPlayer(player);
        return EventResult.DENY;
    }

    private void spawnPlayer(ServerPlayerEntity player) {
        this.playerLogic.resetPlayer(player, GameMode.ADVENTURE);
        this.playerLogic.spawnPlayer(player, this.map.spawn, this.map.spawn.center());
    }
}
