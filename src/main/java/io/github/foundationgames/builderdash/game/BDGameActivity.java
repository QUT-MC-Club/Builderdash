package io.github.foundationgames.builderdash.game;

import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import io.github.foundationgames.builderdash.game.element.TickingAnimation;
import io.github.foundationgames.builderdash.game.element.display.GenericContent;
import io.github.foundationgames.builderdash.game.map.BuildZone;
import io.github.foundationgames.builderdash.game.map.BuilderdashMap;
import io.github.foundationgames.builderdash.game.player.BDPlayer;
import io.github.foundationgames.builderdash.game.player.PlayerRole;
import io.github.foundationgames.builderdash.game.sound.SFX;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.consume.TeleportRandomlyConsumeEffect;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.GameCloseReason;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.api.game.common.widget.BossBarWidget;
import xyz.nucleoid.plasmid.api.game.common.widget.SidebarWidget;
import xyz.nucleoid.plasmid.api.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.api.game.player.JoinOffer;
import xyz.nucleoid.plasmid.api.game.player.JoinOfferResult;
import xyz.nucleoid.plasmid.api.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.api.util.PlayerRef;
import xyz.nucleoid.stimuli.event.EventResult;
import xyz.nucleoid.stimuli.event.block.BlockBreakEvent;
import xyz.nucleoid.stimuli.event.block.BlockPlaceEvent;
import xyz.nucleoid.stimuli.event.block.BlockTrampleEvent;
import xyz.nucleoid.stimuli.event.block.BlockUseEvent;
import xyz.nucleoid.stimuli.event.block.FlowerPotModifyEvent;
import xyz.nucleoid.stimuli.event.block.FluidPlaceEvent;
import xyz.nucleoid.stimuli.event.entity.EntitySpawnEvent;
import xyz.nucleoid.stimuli.event.entity.EntityUseEvent;
import xyz.nucleoid.stimuli.event.item.ItemUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerAttackEntityEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;
import xyz.nucleoid.stimuli.event.player.ReplacePlayerChatEvent;
import xyz.nucleoid.stimuli.event.world.EndPortalOpenEvent;
import xyz.nucleoid.stimuli.event.world.ExplosionDetonatedEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BDGameActivity<C extends BDGameConfig> {
    public static final AnnouncedTime TIME_ONE_MIN = new AnnouncedTime(60, Formatting.GREEN, SFX.CLICK);
    public static final AnnouncedTime TIME_THIRTY_SEC = new AnnouncedTime(30, Formatting.YELLOW, SFX.CLICK);
    public static final AnnouncedTime TIME_TEN_SEC = new AnnouncedTime(30, Formatting.YELLOW, SFX.CLICK);
    public static final List<AnnouncedTime> COUNTDOWN_FROM_FIVE = List.of(
            new AnnouncedTime(5, Formatting.GOLD, SFX.NOTE_CLICK),
            new AnnouncedTime(4, Formatting.GOLD, SFX.NOTE_CLICK),
            new AnnouncedTime(3, Formatting.RED, SFX.HIGH_CLICK),
            new AnnouncedTime(2, Formatting.RED, SFX.HIGH_CLICK),
            new AnnouncedTime(1, Formatting.RED, SFX.HIGH_CLICK)
    );

    public static final String QUOTE = "text.builderdash.quote";
    public static final String TIME_REMAINING = "label.builderdash.time_remaining";
    public static final String YOU_ARE_BUILDING = "label.builderdash.you_are_building";

    public static final Text WON_THE_GAME = Text.translatable("title.builderdash.won_the_game").formatted(Formatting.GREEN);
    public static final Text TYPE_DONE_1 = Text.translatable("label.builderdash.type_done_1").formatted(Formatting.LIGHT_PURPLE);
    public static final Text TYPE_DONE_2 = Text.translatable("label.builderdash.type_done_2").formatted(Formatting.LIGHT_PURPLE);
    public static final Text BUILD_PROMPT = Text.translatable("title.builderdash.build_prompt").formatted(Formatting.GREEN);

    public static final int SEC = 20;

    public final C config;

    public final GameSpace gameSpace;
    public final BuilderdashMap gameMap;

    public final Object2ObjectMap<PlayerRef, BDPlayer> participants;
    public final BDPlayerLogic playerLogic;
    public final ServerWorld world;
    public final GlobalWidgets widgets;
    protected final SidebarWidget scoreboard;
    public final Set<TickingAnimation> animations = new HashSet<>();

    protected BuildZone respawn;
    protected int timeToPhaseChange = 1;
    protected int totalTime = 1;
    @Nullable
    protected BossBarWidget timerBar = null;
    protected final List<AnnouncedTime> timesToAnnounce = new ArrayList<>();

    private int lastCountdownTime = Integer.MAX_VALUE;

    protected BDGameActivity(GameSpace space, GameActivity game, ServerWorld world, BuilderdashMap map, C config) {
        Set<PlayerRef> participants = space.getPlayers().participants().stream()
                .map(PlayerRef::of)
                .collect(Collectors.toSet());
        GlobalWidgets widgets = GlobalWidgets.addTo(game);

        this.gameSpace = space;
        this.config = config;
        this.gameMap = map;
        this.playerLogic = new BDPlayerLogic(space, world, map);
        this.participants = new Object2ObjectOpenHashMap<>();
        this.world = world;
        this.widgets = widgets;

        this.respawn = map.singleZone;

        for (PlayerRef player : participants) {
            this.participants.put(player, new BDPlayer(world, player));
        }

        game.setRule(GameRuleType.CRAFTING, EventResult.DENY);
        game.setRule(GameRuleType.PORTALS, EventResult.DENY);
        game.setRule(GameRuleType.PVP, EventResult.DENY);
        game.setRule(GameRuleType.HUNGER, EventResult.DENY);
        game.setRule(GameRuleType.FALL_DAMAGE, EventResult.DENY);
        game.setRule(GameRuleType.BLOCK_DROPS, EventResult.DENY);
        game.setRule(GameRuleType.THROW_ITEMS, EventResult.DENY);
        game.setRule(GameRuleType.UNSTABLE_TNT, EventResult.DENY);
        game.setRule(GameRuleType.FIRE_TICK, EventResult.DENY);
        game.listen(ExplosionDetonatedEvent.EVENT, (explosion, blocksToDestroy) -> EventResult.DENY);
        game.listen(EndPortalOpenEvent.EVENT, (it, res) -> EventResult.DENY);

        game.listen(GameActivityEvents.ENABLE, this::onOpen);
        game.listen(GameActivityEvents.DISABLE, this::onClose);
        game.listen(GameActivityEvents.STATE_UPDATE, state -> state.canPlay(false));

        game.listen(GamePlayerEvents.OFFER, this::onPlayerOffer);
        game.listen(GamePlayerEvents.ACCEPT, joinAcceptor -> joinAcceptor.teleport(world, Vec3d.ZERO));
        game.listen(GamePlayerEvents.ADD, this::addPlayer);
        game.listen(GamePlayerEvents.REMOVE, this::removePlayer);

        game.listen(GameActivityEvents.TICK, this::tick);

        game.listen(PlayerDamageEvent.EVENT, this::onPlayerDamage);
        game.listen(PlayerDeathEvent.EVENT, this::onPlayerDeath);

        game.listen(BlockPlaceEvent.BEFORE, (player, world1, pos, state, context) ->
                this.canPlayerModify(player, context.getBlockPos()));
        game.listen(FluidPlaceEvent.EVENT, (world1, pos, player, hitResult) ->
                this.canPlayerModify(player, pos));
        game.listen(BlockBreakEvent.EVENT, (player, world1, pos) ->
                this.canPlayerModify(player, pos));
        game.listen(BlockUseEvent.EVENT, (player, hand, hitResult) -> {
            var r = this.canPlayerModify(player, hitResult.getBlockPos());
            if (r == EventResult.DENY) {
                var os = hitResult.getBlockPos().offset(hitResult.getSide());
                return this.canPlayerModify(player, os).asActionResult();
            }
            return r.asActionResult();
        });
        game.listen(EntityUseEvent.EVENT, (player, entity, hand, hitResult) ->
                this.canPlayerModify(player, entity.getBlockPos()));
        game.listen(FlowerPotModifyEvent.EVENT, (player, hand, hitResult) ->
                this.canPlayerModify(player, hitResult.getBlockPos()));
        game.listen(BlockTrampleEvent.EVENT, (entity, world1, pos, from, to) -> {
            if (entity instanceof ServerPlayerEntity player) {
                return this.canPlayerModify(player, pos);
            }
            return EventResult.PASS;
        });
        game.listen(PlayerAttackEntityEvent.EVENT, (player, hand, attacked, hitResult) ->
                this.canPlayerModify(player, attacked.getBlockPos()));
        game.listen(EntitySpawnEvent.EVENT, entity -> {
            if (entity instanceof MobEntity mob) {
                mob.setAiDisabled(true);
            }
            return EventResult.PASS;
        });
        game.listen(ItemUseEvent.EVENT, (player, hand) -> {
            var stack = player.getStackInHand(hand);
            if (stack.contains(DataComponentTypes.CONSUMABLE)) {
                var cons = stack.get(DataComponentTypes.CONSUMABLE);
                if (cons != null) for (var e : cons.onConsumeEffects()) if (e instanceof TeleportRandomlyConsumeEffect) {
                    return ActionResult.FAIL;
                }
            }

            if (stack.getItem() instanceof EnderPearlItem) {
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        });

        game.listen(ReplacePlayerChatEvent.EVENT, this::consumeChatMessage);

        var titleText = this.gameSpace.getMetadata().sourceConfig().value().type().name().copy();
        this.scoreboard = this.widgets.addSidebar(titleText.formatted(Formatting.YELLOW));
    }

    protected void onOpen() {
        for (var participant : this.gameSpace.getPlayers().participants()) {
            this.spawnParticipant(participant);
        }
        for (var spectator : this.gameSpace.getPlayers().spectators()) {
            this.spawnSpectator(spectator);
        }
    }

    protected void onClose() {
        for (var player : this.participants.values()) {
            player.end();
        }
    }

    protected JoinOfferResult onPlayerOffer(JoinOffer offer) {
        return offer.acceptSpectators();
    }

    protected void addPlayer(ServerPlayerEntity player) {
        if (!this.participants.containsKey(PlayerRef.of(player)) || this.gameSpace.getPlayers().spectators().contains(player)) {
            this.spawnSpectator(player);
        }
    }

    protected void removePlayer(ServerPlayerEntity player) {
        this.participants.remove(PlayerRef.of(player));
    }

    protected EventResult canPlayerModify(ServerPlayerEntity player, BlockPos pos) {
        var bdPlayer = participants.get(PlayerRef.of(player));

        if (bdPlayer != null && bdPlayer.currentRole != null && bdPlayer.currentRole.canModifyAt(pos)) {
            return EventResult.PASS;
        }

        return EventResult.DENY;
    }

    private boolean consumeChatMessage(ServerPlayerEntity player, SignedMessage signedMessage, MessageType.Parameters parameters) {
        var bdPlayer = participants.get(PlayerRef.of(player));

        if (bdPlayer != null && bdPlayer.currentRole != null) {
            return !bdPlayer.currentRole.handleChatMessage(signedMessage, parameters);
        }

        return false;
    }

    protected BlockBounds getSpawnAreaFor(PlayerRef ref) {
        return this.respawn.playerSafeArea();
    }

    protected EventResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        if (source.isIn(DamageTypeTags.IS_PLAYER_ATTACK) ||
                source.isOf(DamageTypes.OUT_OF_WORLD) ||
                source.isOf(DamageTypes.IN_WALL)) {
            this.spawnParticipant(player);
        }

        return EventResult.DENY;
    }

    protected EventResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        this.spawnParticipant(player);
        return EventResult.DENY;
    }

    protected void spawnParticipant(ServerPlayerEntity player) {
        var ref = PlayerRef.of(player);
        var spawn = this.getSpawnAreaFor(ref);
        this.playerLogic.resetPlayer(player, participants.get(ref));
        this.playerLogic.spawnPlayer(player, spawn, spawn.center());
    }

    protected void spawnSpectator(ServerPlayerEntity player) {
        this.playerLogic.resetPlayer(player, GameMode.SPECTATOR);
        this.playerLogic.spawnPlayer(player, this.respawn.playerSafeArea(), this.respawn.buildSafeArea().center());
    }

    protected static String formattedTime(int sec) {
        long minutes = sec / 60;
        long seconds = sec % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    protected void tick() {
        this.participants.values().forEach(BDPlayer::tick);

        if (this.timeToPhaseChange == 0) {
            nextPhase();
        } else {
            this.timeToPhaseChange--;
        }

        if (this.timeToPhaseChange % SEC == 0) {
            this.sec(this.timeToPhaseChange / SEC);
        }

        var remove = new HashSet<TickingAnimation>();
        for (var anim : this.animations) {
            if (!anim.tick(this.world)) {
                remove.add(anim);
            }
        }
        this.animations.removeAll(remove);
    }

    protected void sec(int timeToPhaseChangeSec) {
        if (this.timerBar != null) {
            this.timerBar.setTitle(this.createTimeText(timeToPhaseChangeSec));
            this.timerBar.setProgress((float) this.timeToPhaseChange / this.totalTime);
        }

        AnnouncedTime lowest = null;
        for (var time : this.timesToAnnounce) {
            int t = time.secRemaining();

            if (timeToPhaseChangeSec <= t && t < this.lastCountdownTime) {
                if (lowest == null || time.secRemaining() < lowest.secRemaining()) {
                    lowest = time;
                }
            }
        }

        if (lowest != null) {
            this.gameSpace.getPlayers().showTitle(Text.empty(),
                    Text.literal(formattedTime(timeToPhaseChangeSec)).formatted(lowest.color()),
                    0, 25, 2);

            this.animations.add(lowest.sound().play(this.world));
        }

        this.lastCountdownTime = timeToPhaseChangeSec;
    }

    protected void nextPhase() {}

    public void endGame() {
        this.gameSpace.close(GameCloseReason.FINISHED);
    }

    protected void removeTimerInfo() {
        if (this.timerBar != null) {
            this.widgets.removeWidget(this.timerBar);
        }

        this.timesToAnnounce.clear();
    }

    protected void setTimerBar(BossBar.Color color, BossBar.Style style) {
        if (this.timerBar != null) {
            this.widgets.removeWidget(this.timerBar);
        }
        this.timerBar = this.widgets.addBossBar(this.createTimeText(this.totalTime % SEC), color, style);
    }

    protected Text createTimeText(int timeToPhaseChangeSec) {
        return Text.translatable(TIME_REMAINING, formattedTime(timeToPhaseChangeSec));
    }

    protected Text[] createScoresForScoreboard() {
        var players = new ArrayList<>(this.participants.values());
        players.sort(Collections.reverseOrder(Comparator.comparingInt(p -> p.score)));
        return players.stream().map(p ->
                Text.literal(Integer.toString(p.score)).formatted(Formatting.AQUA)
                        .append(Text.literal(" - ").formatted(Formatting.GRAY))
                        .append(p.displayName())
        ).toArray(Text[]::new);
    }

    protected void openWinArea(BDPlayer winner, BuildZone winnerZone) {
        this.respawn = winnerZone;

        for (var player : this.participants.values()) {
            player.updateRole(new PlayerRole.Flying(this.world, player));
            player.player.ifOnline(this.gameSpace, this::spawnParticipant);
        }

        var winnerName = winner.displayName().copy().formatted(Formatting.AQUA, Formatting.BOLD);
        this.gameSpace.getPlayers().showTitle(
                winnerName, WON_THE_GAME, 10, 10 * SEC, 10
        );

        if (winnerZone.displays().length > 0) {
            var disp = winnerZone.displays()[0];

            if (disp.getAttachment() == null) ChunkAttachment.of(disp, this.world, disp.getPos());

            var content = GenericContent.builder()
                    .addTop(winnerName, 1, 8)
                    .addTop(WON_THE_GAME, 1, 7);
            disp.setContent(content.build());
        }

        this.animations.add(SFX.FANFARE.play(this.world));
    }

    public record AnnouncedTime(int secRemaining, Formatting color, SFX sound) {}
}
