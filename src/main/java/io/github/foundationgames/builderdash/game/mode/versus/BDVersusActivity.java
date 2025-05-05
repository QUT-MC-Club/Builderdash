package io.github.foundationgames.builderdash.game.mode.versus;

import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import io.github.foundationgames.builderdash.BDUtil;
import io.github.foundationgames.builderdash.game.BDGameActivity;
import io.github.foundationgames.builderdash.game.element.TickingAnimation;
import io.github.foundationgames.builderdash.game.element.display.GenericContent;
import io.github.foundationgames.builderdash.game.map.BuildZone;
import io.github.foundationgames.builderdash.game.map.BuilderdashMap;
import io.github.foundationgames.builderdash.game.map.PrivateBuildZoneManager;
import io.github.foundationgames.builderdash.game.mode.pictionary.WordQueue;
import io.github.foundationgames.builderdash.game.mode.versus.role.VersusBuilderRole;
import io.github.foundationgames.builderdash.game.mode.versus.role.VersusVoterRole;
import io.github.foundationgames.builderdash.game.player.BDPlayer;
import io.github.foundationgames.builderdash.game.player.PlayerRole;
import io.github.foundationgames.builderdash.game.sound.SFX;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.GameCloseReason;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.player.JoinOffer;
import xyz.nucleoid.plasmid.api.game.player.JoinOfferResult;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BDVersusActivity extends BDGameActivity<BDVersusConfig> {
    public static final String ROUND_NO = "label.builderdash.versus.round";
    public static final String PROMPT_NO = "label.builderdash.versus.prompt";
    public static final String BUILD_NO = "label.builderdash.versus.build_no";
    public static final String HOW_MANY_DONE = "label.builderdash.versus.how_many_done";
    public static final String RESULT_WON = "message.builderdash.versus.result_player_won";
    public static final String PLAYER_GAINED_POINTS = "message.builderdash.versus.player_gained_points";

    public static final Text FIRST_PROMPT = Text.translatable("label.builderdash.versus.first_prompt").formatted(Formatting.AQUA);
    public static final Text SECOND_PROMPT = Text.translatable("label.builderdash.versus.second_prompt").formatted(Formatting.AQUA);
    public static final Text PRE_VOTE = Text.translatable("label.builderdash.versus.pre_vote").formatted(Formatting.AQUA);
    public static final Text VOTING_PLAYERS = Text.translatable("label.builderdash.versus.voting").formatted(Formatting.AQUA);
    public static final Text POST_VOTE = Text.translatable("label.builderdash.versus.post_vote").formatted(Formatting.AQUA);
    public static final Text REVEALING = Text.translatable("label.builderdash.versus.revealing").formatted(Formatting.RED);
    public static final Text RESULTS = Text.translatable("label.builderdash.versus.results").formatted(Formatting.BLUE);
    public static final Text HOW_TO_VOTE = Text.translatable("label.builderdash.versus.how_to_vote").formatted(Formatting.GRAY);
    public static final Text GET_READY_VOTE = Text.translatable("message.builderdash.versus.pre_vote").formatted(Formatting.GOLD);
    public static final Text START_VOTING = Text.translatable("message.builderdash.versus.start_voting").formatted(Formatting.GREEN);
    public static final Text RESULTS_IN = Text.translatable("message.builderdash.versus.results_are_in").formatted(Formatting.LIGHT_PURPLE);
    public static final Text RESULT_TIED = Text.translatable("message.builderdash.versus.result_tied").formatted(Formatting.YELLOW);

    private Phase phase = Phase.PREGAME;

    private final List<PlayerRef> indexedPlayers; // Players that disconnect are not removed from this, unlike participants
    private final List<BuildPairWithPrompt[]> buildRounds = new ArrayList<>();

    private final Object2ObjectMap<PlayerRef, BDPlayer> disconnectedPlayers = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectMap<PlayerRef, BuildZone> currentlyAssignedZones = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectMap<PlayerRef, PlayerBuildInfo> playerScoredBuilds = new Object2ObjectOpenHashMap<>();

    private int currentRound = -1;
    private int currentSubRound = 0;
    private int currentVotePair = 0;
    private final int maxRounds;

    private final Set<PlayerRef> playersReadyToContinue = new HashSet<>();

    private final PrivateBuildZoneManager privateBuildZones;
    private BuildZone emptyBuildZone = null;

    private final Object2IntMap<PlayerRef> votes = new Object2IntOpenHashMap<>();

    protected BDVersusActivity(GameSpace space, GameActivity game, ServerWorld world, BuilderdashMap map, BDVersusConfig config) {
        super(space, game, world, map, config);

        this.privateBuildZones = new PrivateBuildZoneManager(world, map.privateZoneTemplate, map.buildZonesStart, this.participants.size());

        this.indexedPlayers = new ArrayList<>(this.participants.keySet());
        int playerCount = indexedPlayers.size();

        var words = WordQueue.ofShuffled(config.wordList());

        var initialShuffle = BDUtil.range(0, playerCount);
        Collections.shuffle(initialShuffle);
        var shiftEachSubRoundBy = new IntArrayList();
        int subRoundCount = config.doubleRounds() ? 8 : 4;
        for (int i = 0; i < subRoundCount; i++) {
            shiftEachSubRoundBy.add(initialShuffle.getInt(i % playerCount));
        }

        this.maxRounds = subRoundCount / 2;

        for (int r = 0; r < maxRounds; r++) {
            int sr = r * 2;
            var roundBuilds = new BuildPairWithPrompt[playerCount];

            var playersFirstSr = BDUtil.circshift(BDUtil.range(0, playerCount), shiftEachSubRoundBy.getInt(sr));
            var playersSecondSr = BDUtil.circshift(BDUtil.range(0, playerCount), shiftEachSubRoundBy.getInt(sr + 1));

            for (int i = 0; i < playerCount; i++) {
                var build = new BuildPairWithPrompt(words.pop()[0]);

                build.builders[0] = indexedPlayers.get(playersFirstSr.getInt(i));
                build.builders[1] = indexedPlayers.get(playersSecondSr.getInt(i));

                roundBuilds[i] = build;
            }

            this.buildRounds.add(roundBuilds);
        }
    }

    public static void open(GameSpace gameSpace, ServerWorld world, BuilderdashMap map, BDVersusConfig config) {
        gameSpace.setActivity(game -> new BDVersusActivity(gameSpace, game, world, map, config));
    }

    public void setBuilderFinishedStatus(PlayerRef player, boolean finished) {
        if (finished) {
            playersReadyToContinue.add(player);
        } else {
            playersReadyToContinue.remove(player);
        }

        this.updateScoreboard();
    }

    @Override
    protected void onOpen() {
    }

    @Override
    protected void tick() {
        super.tick();

        if (this.phase == Phase.BUILDING) {
            var builds = this.buildRounds.get(this.currentRound);
            for (BuildPairWithPrompt build : builds) {
                build.builders[this.currentSubRound].ifOnline(this.gameSpace, s ->
                        s.sendMessageToClient(Text.translatable(YOU_ARE_BUILDING, build.prompt).formatted(Formatting.AQUA), true));
            }
        }

        if (this.playersReadyToContinue.size() >= this.indexedPlayers.size()) {
            this.nextPhase();
        }
    }

    @Override
    protected void nextPhase() {
        switch (this.phase) {
            case PREGAME -> beginBuilding(0);
            case BUILDING -> endBuildSubRoundLogic();
            case PREPARE_VOTE -> startVoting();
            case VOTING -> revealVotedBuildInfo();
            case POST_VOTE -> endSingleVoteSessionLogic();
            case DISPLAY_WIN -> endGame();
        }
    }

    public void endBuildSubRoundLogic() {
        if (this.currentSubRound < 1) {
            beginBuilding(this.currentSubRound + 1);
        } else {
            this.currentVotePair = 0;
            this.prepareForVoting();
        }
    }

    public void beginBuilding(int subRound) {
        if (subRound == 0) {
            this.currentRound++;
        }
        this.currentSubRound = subRound;

        this.currentlyAssignedZones.clear();
        this.phase = Phase.BUILDING;
        this.timeToPhaseChange = this.config.buildTime() * SEC;
        this.totalTime = this.timeToPhaseChange;
        this.playersReadyToContinue.clear();

        this.setTimerBar(BossBar.Color.BLUE, BossBar.Style.NOTCHED_20);
        this.timesToAnnounce.add(TIME_ONE_MIN);
        this.timesToAnnounce.add(TIME_THIRTY_SEC);
        this.timesToAnnounce.add(TIME_TEN_SEC);
        this.timesToAnnounce.addAll(COUNTDOWN_FROM_FIVE);

        var roundBuilds = this.buildRounds.get(this.currentRound);
        for (BuildPairWithPrompt pair : roundBuilds) {
            pair.builds[subRound] = privateBuildZones.requestNewBuildZone();
            this.currentlyAssignedZones.put(pair.builders[subRound], pair.builds[subRound]);

            var player = this.getDataFor(pair.builders[subRound]);
            if (player != null) {
                player.updateRole(new VersusBuilderRole(this.world, player, pair.builds[subRound], this));
            }

            pair.builders[subRound].ifOnline(this.gameSpace, this::spawnParticipant);
        }

        var roundText = subRound == 0 ? Text.translatable(ROUND_NO, this.currentRound / 2 + 1, this.maxRounds / 2).formatted(Formatting.GOLD) : Text.empty();
        var promptText = Text.translatable(PROMPT_NO, subRound + 1, 2).formatted(Formatting.LIGHT_PURPLE);

        this.gameSpace.getPlayers().showTitle(
                roundText, promptText, 10, 5 * SEC, 10
        );

        this.animations.add(SFX.VERSUS_BUILD.play(this.world));
        this.updateScoreboard();
    }

    public void endSingleVoteSessionLogic() {
        this.currentVotePair++;

        if (this.currentVotePair >= this.indexedPlayers.size()) {
            this.currentRound++;

            if (this.currentRound >= this.maxRounds - 1) {
                displayWin();
                return;
            }

            this.beginBuilding(0);
        } else {
            this.prepareForVoting();
        }
    }

    public void prepareForVoting() {
        this.phase = Phase.PREPARE_VOTE;
        this.timeToPhaseChange = 99 * SEC;
        this.totalTime = this.timeToPhaseChange;
        this.respawn = this.gameMap.doubleZone;

        this.removeTimerInfo();

        this.currentlyAssignedZones.clear();
        this.playersReadyToContinue.clear();
        this.votes.clear();

        for (var bdPlayer : this.participants.values()) {
            bdPlayer.updateRole(new PlayerRole.Flying(this.world, bdPlayer));
            bdPlayer.player.ifOnline(this.gameSpace, this::spawnParticipant);
        }

        if (this.emptyBuildZone == null) {
            this.emptyBuildZone = this.privateBuildZones.requestNewBuildZone();
        }

        var builds = this.buildRounds.get(this.currentRound)[this.currentVotePair];
        var dests = new BlockPos[] {
                this.gameMap.doubleZone.buildSafeArea().min(),
                this.gameMap.doubleZone.buildSafeArea().max()
        };

        var anims = new ArrayDeque<TickingAnimation>();
        anims.addLast(TickingAnimation.wait(3 * SEC));
        for (int i = 0; i < Math.min(builds.builds.length, dests.length); i++) {
            var build = builds.builds[i];
            var dest = dests[i];

            this.emptyBuildZone.copyBuild(this.world, dest);

            var copy = new BuildZone.CopyAnimation(build, dest, false, 2);
            anims.addLast(copy);
            anims.addLast(SFX.VERSUS_VOTE_REVEAL_BUILD.play(this.world));
            anims.addLast(TickingAnimation.wait(2 * SEC));

            var disp = this.gameMap.doubleZone.displays()[i];
            disp.setContent(GenericContent.builder().addBottom(REVEALING, 1, 7).build());

            var currAtt = disp.getAttachment();
            if (currAtt != null) {
                currAtt.destroy();
            }

            ChunkAttachment.of(disp, this.world, disp.getPos());
        }
        anims.addLast(TickingAnimation.instant(w -> this.nextPhase()));
        this.animations.add(new TickingAnimation.Sequence(anims));

        this.gameSpace.getPlayers().showTitle(GET_READY_VOTE, Text.empty(), 5, 3 * SEC, 5);
        this.animations.add(SFX.VERSUS_VOTE.play(this.world, 8));
        this.updateScoreboard();
    }

    public void startVoting() {
        this.phase = Phase.VOTING;
        this.timeToPhaseChange = this.config.voteTime() * SEC;
        this.totalTime = this.timeToPhaseChange;

        this.setTimerBar(BossBar.Color.GREEN, BossBar.Style.NOTCHED_10);
        this.timesToAnnounce.addAll(COUNTDOWN_FROM_FIVE);

        for (var bdPlayer : this.participants.values()) {
            bdPlayer.updateRole(new VersusVoterRole(this.world, bdPlayer, this));
        }

        var builds = this.buildRounds.get(this.currentRound)[this.currentVotePair];
        var displays = this.gameMap.doubleZone.displays();
        var prompt = builds.prompt;
        var promptText = Text.translatable(QUOTE, prompt).formatted(Formatting.AQUA);

        for (int i = 0; i < displays.length; i++) {
            var disp = displays[i];
            float scale = (float) (((disp.sizeX - 0.75) * GenericContent.WIDTH_PER_BLOCK) / ((prompt.length() * 6.2) + 5));
            if (scale > 7) {
                scale = 7;
            }

            displays[i].setContent(GenericContent.builder()
                    .addTop(Text.translatable(BUILD_NO, i + 1).formatted(Formatting.GREEN), 1, 9)
                    .addBottom(HOW_TO_VOTE)
                    .addBottom(promptText, 1, scale)
                    .build());
        }

        this.gameSpace.getPlayers().showTitle(promptText, START_VOTING, 5, 5 * SEC, 5);
        this.animations.add(SFX.VERSUS_VOTE_START.play(this.world));
        this.updateScoreboard();
    }

    public void revealVotedBuildInfo() {
        this.phase = Phase.POST_VOTE;
        this.timeToPhaseChange = (8 + (indexedPlayers.size() / 4)) * SEC;
        this.totalTime = this.timeToPhaseChange;

        this.playersReadyToContinue.clear();

        for (var bdPlayer : this.participants.values()) {
            bdPlayer.updateRole(new PlayerRole.Flying(this.world, bdPlayer));
        }

        this.removeTimerInfo();

        var anims = new ArrayDeque<TickingAnimation>();
        anims.addLast(SFX.VERSUS_VOTE_BEGIN_TALLY.play(this.world));
        anims.addLast(TickingAnimation.wait(2 * SEC));

        var displays = this.gameMap.doubleZone.displays();
        var builds = this.buildRounds.get(this.currentRound)[this.currentVotePair];
        var prompt = builds.prompt;

        for (int i = 0; i < displays.length; i++) {
            var disp = displays[i];
            var builder = builds.builders[i];
            var player = builder.getEntity(this.gameSpace);
            Text builderName = Text.empty();

            if (player != null) {
                builderName = player.getStyledDisplayName().copy().formatted(Formatting.YELLOW);
            }

            float scale = (float) (((disp.sizeX - 0.75) * GenericContent.WIDTH_PER_BLOCK) / ((prompt.length() * 6.2) + 5));
            if (scale > 5.5f) {
                scale = 5.5f;
            }

            var content = GenericContent.builder()
                    .addTop(builderName, 1, 4.7f)
                    .addTop(Text.translatable(QUOTE, prompt).formatted(Formatting.AQUA), 1, scale)
                    .addBottom(RESULTS, 1, 6);

            disp.setContent(content.build());
            if (disp.getAttachment() == null) ChunkAttachment.of(disp, this.world, disp.getPos());

            final int index = i;
            float transpose = 0;
            for (var vote : this.votes.object2IntEntrySet()) {
                if (vote.getIntValue() == index) {
                    var voter = vote.getKey();
                    var voterPlayer = voter.getEntity(this.gameSpace);

                    if (voterPlayer != null) {
                        content.addPlayer(voterPlayer.getGameProfile());

                        final var c = content.build();
                        anims.addLast(TickingAnimation.instant(w -> displays[index].setContent(c)));
                        anims.addLast(SFX.VERSUS_VOTE_TALLY_EACH.play(this.world, transpose));
                        anims.addLast(TickingAnimation.wait(5));

                        transpose += 1;
                    }
                }
            }
        }

        anims.addLast(TickingAnimation.wait(30));
        anims.addLast(SFX.VERSUS_VOTE_RESULT.play(this.world));
        anims.addLast(TickingAnimation.instant(w -> tallyVotesAndUpdateScores()));
        this.animations.add(new TickingAnimation.Sequence(anims));

        this.gameSpace.getPlayers().showTitle(Text.empty(), RESULTS_IN, 5, 2 * SEC, 5);
        this.updateScoreboard();
    }

    public void tallyVotesAndUpdateScores() {
        var pair = this.buildRounds.get(this.currentRound)[this.currentVotePair];
        var totalVotes = new Int2IntOpenHashMap();
        totalVotes.put(0, 0);
        totalVotes.put(1, 0);

        for (var vote : this.votes.object2IntEntrySet()) {
            int idx = vote.getIntValue();
            int points = (int) (10 * (currentRound + 1) * config.pointRoundMul());
            var builder = pair.builders[idx];

            this.getDataFor(builder).score += points;
            totalVotes.put(idx, totalVotes.getOrDefault(idx, 0) + points);
        }

        for (var idxAndVotes : totalVotes.int2IntEntrySet()) {
            int idx = idxAndVotes.getIntKey();
            int points = idxAndVotes.getIntValue();
            var builder = pair.builders[idx];

            if (this.playerScoredBuilds.containsKey(builder)) {
                var scoredBuild = this.playerScoredBuilds.get(builder);
                if (points > scoredBuild.points() ||
                        (points == scoredBuild.points() && this.world.random.nextBoolean())) {
                    this.playerScoredBuilds.put(builder, new PlayerBuildInfo(pair.builds[idx], points));
                }
            } else {
                this.playerScoredBuilds.put(builder, new PlayerBuildInfo(pair.builds[idx], points));
            }

            builder.ifOnline(this.gameSpace, s ->
                    this.gameSpace.getPlayers().sendMessage(Text.translatable(PLAYER_GAINED_POINTS, s.getStyledDisplayName(), points)
                            .formatted(Formatting.GREEN, Formatting.ITALIC)));
        }

        int winnerIdx = totalVotes.int2IntEntrySet().stream().max(Comparator.comparingInt(Int2IntMap.Entry::getIntValue))
                .map(Int2IntMap.Entry::getIntKey).orElse(-1);
        int loserIdx = totalVotes.int2IntEntrySet().stream().min(Comparator.comparingInt(Int2IntMap.Entry::getIntValue))
                .map(Int2IntMap.Entry::getIntKey).orElse(-1);
        if (winnerIdx >= 0 && loserIdx >= 0) {
            if (totalVotes.get(winnerIdx) == totalVotes.get(loserIdx)) {
                this.gameSpace.getPlayers().showTitle(Text.empty(), RESULT_TIED, 5, 3 * SEC, 5);
            } else {
                pair.builders[winnerIdx].ifOnline(this.gameSpace, s ->
                        this.gameSpace.getPlayers().showTitle(Text.empty(),
                                Text.translatable(RESULT_WON, s.getStyledDisplayName()).formatted(Formatting.AQUA),
                                5, 3 * SEC, 5));
            }
        }

        this.updateScoreboard();
    }

    public void displayWin() {
        this.phase = Phase.DISPLAY_WIN;
        this.timeToPhaseChange = 10 * SEC;
        this.totalTime = this.timeToPhaseChange;

        var winner = this.participants.values().stream().max(Comparator.comparingInt(p -> p.score)).orElse(null);

        if (winner == null) {
            this.gameSpace.close(GameCloseReason.ERRORED);
            return;
        }

        var winnerZone = this.gameMap.singleZone;

        var winningBuild = this.playerScoredBuilds.get(winner.player);
        if (winningBuild != null) {
            winningBuild.build().copyBuild(this.world, winnerZone.buildSafeArea().min());
        }

        this.openWinArea(winner, winnerZone);
    }

    @Override
    protected JoinOfferResult onPlayerOffer(JoinOffer offer) {
        for (var profile : offer.players()) {
            var player = PlayerRef.of(profile);

            if (!this.indexedPlayers.contains(player)) {
                return super.onPlayerOffer(offer);
            }
        }

        return JoinOfferResult.ACCEPT;
    }

    @Override
    protected void addPlayer(ServerPlayerEntity player) {
        var ref = PlayerRef.of(player);
        var data = this.disconnectedPlayers.get(ref);

        if (data != null) {
            this.participants.put(ref, data);
            data.notifyReconnect();
            this.spawnParticipant(player);
            return;
        }

        super.addPlayer(player);
    }

    @Override
    protected void removePlayer(ServerPlayerEntity player) {
        var ref = PlayerRef.of(player);
        var data = this.participants.get(ref);
        disconnectedPlayers.put(ref, data);
        super.removePlayer(player);
    }

    public void updateScoreboard() {
        this.scoreboard.clearLines();

        this.scoreboard.addLines(Text.translatable(ROUND_NO, this.currentRound / 2 + 1, this.maxRounds / 2).formatted(Formatting.BLUE));
        this.scoreboard.addLines(Text.empty());

        switch (this.phase) {
            case PREPARE_VOTE -> this.scoreboard.addLines(PRE_VOTE);
            case VOTING -> this.scoreboard.addLines(VOTING_PLAYERS);
            case POST_VOTE -> this.scoreboard.addLines(POST_VOTE);
            case BUILDING -> this.scoreboard.addLines(this.currentSubRound == 0 ? FIRST_PROMPT : SECOND_PROMPT);
        }
        if (this.phase == Phase.VOTING || this.phase == Phase.BUILDING) {
            this.scoreboard.addLines(Text.translatable(HOW_MANY_DONE, this.playersReadyToContinue.size(), this.participants.size()).formatted(Formatting.AQUA));
        }
        this.scoreboard.addLines(Text.empty());

        if (this.phase == Phase.BUILDING) {
            this.scoreboard.addLines(TYPE_DONE_1, TYPE_DONE_2);
        }

        this.scoreboard.addLines(Text.empty());
        this.scoreboard.addLines(this.createScoresForScoreboard());
    }

    @Override
    protected BlockBounds getSpawnAreaFor(PlayerRef ref) {
        if (currentlyAssignedZones.containsKey(ref)) {
            return currentlyAssignedZones.get(ref).buildSafeArea();
        }

        return super.getSpawnAreaFor(ref);
    }

    private BDPlayer getDataFor(PlayerRef ref) {
        var data = this.participants.get(ref);
        if (data == null) {
            data = this.disconnectedPlayers.get(ref);
        }

        return data;
    }

    public int getVote(ServerPlayerEntity player) {
        var ref = PlayerRef.of(player);
        return !votes.containsKey(ref) ? -1 : votes.getInt(ref);
    }

    public void setVote(ServerPlayerEntity player, int buildIndex) {
        var ref = PlayerRef.of(player);
        votes.put(ref, buildIndex);
        playersReadyToContinue.add(ref);
        this.updateScoreboard();
    }

    @Override
    protected void onClose() {
        super.onClose();

        for (var player : this.disconnectedPlayers.values()) {
            player.end();
        }
    }

    public enum Phase {
        PREGAME,
        BUILDING,
        PREPARE_VOTE,
        VOTING,
        POST_VOTE,
        DISPLAY_WIN
    }

    public record PlayerBuildInfo(BuildZone build, int points) {}
}
