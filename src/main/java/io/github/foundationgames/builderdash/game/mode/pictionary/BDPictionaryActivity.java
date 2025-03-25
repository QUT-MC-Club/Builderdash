package io.github.foundationgames.builderdash.game.mode.pictionary;

import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import io.github.foundationgames.builderdash.game.BDGameActivity;
import io.github.foundationgames.builderdash.game.element.display.GenericContent;
import io.github.foundationgames.builderdash.game.element.display.InWorldDisplay;
import io.github.foundationgames.builderdash.game.map.BuildZone;
import io.github.foundationgames.builderdash.game.map.BuilderdashMap;
import io.github.foundationgames.builderdash.game.map.PrivateBuildZoneManager;
import io.github.foundationgames.builderdash.game.mode.pictionary.role.PictionaryBuilderRole;
import io.github.foundationgames.builderdash.game.mode.pictionary.role.PictionaryGuesserRole;
import io.github.foundationgames.builderdash.game.mode.pictionary.ui.ChooseWordGui;
import io.github.foundationgames.builderdash.game.player.BDPlayer;
import io.github.foundationgames.builderdash.game.player.PlayerRole;
import io.github.foundationgames.builderdash.game.sound.SFX;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.GameCloseReason;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class BDPictionaryActivity extends BDGameActivity<BDPictionaryConfig> {
    public static final Text IS_CHOOSING_WORD = Text.translatable("title.builderdash.pictionary.is_choosing_word").formatted(Formatting.GOLD);
    public static final Text IS_BUILDING = Text.translatable("label.builderdash.pictionary.is_building").formatted(Formatting.GOLD);
    public static final Text WORD_CHOSEN = Text.translatable("title.builderdash.pictionary.word_chosen").formatted(Formatting.AQUA);
    public static final Text WAS_THE_WORD = Text.translatable("title.builderdash.pictionary.was_the_word").formatted(Formatting.YELLOW);
    public static final Text GUESS_IN_CHAT = Text.translatable("message.builderdash.pictionary.guess_in_chat").formatted(Formatting.GOLD);

    public static final String GUESSED_WORD = "message.builderdash.pictionary.player_guessed_word";
    public static final String HINT = "label.builderdash.pictionary.hint";

    public final WordQueue words;
    public final Deque<PlayerRef> upcomingBuilders;

    public final Object2ObjectMap<PlayerRef, BuildZone> associatedBuildZones = new Object2ObjectOpenHashMap<>();

    public @Nullable String[] currentWord;
    private PlayerRef currentBuilder;
    private ChooseWordGui chooseWordGui = null;
    private InWorldDisplay currentDisplay = null;

    public final IntList hiddenChars = new IntArrayList();
    public int revealedChars = -1;
    private @Nullable Text hintText = null;
    private Text promptText = Text.empty();

    private final Set<PlayerRef> notYetGuessed = new HashSet<>();
    private int bonusAwardPoints = 10;

    private Phase phase = Phase.WORD_REVEAL;

    private int timeOfFirstGuess = -1;

    private final PrivateBuildZoneManager privateBuildZones;

    protected BDPictionaryActivity(GameSpace space, GameActivity game, ServerWorld world, BuilderdashMap map, BDPictionaryConfig config) {
        super(space, game, world, map, config);

        this.words = WordQueue.ofShuffled(config.wordList());

        var shuffledPlayers = new ArrayList<>(this.participants.keySet());
        Collections.shuffle(shuffledPlayers);

        if (config.doubleRounds()) {
            shuffledPlayers.addAll(shuffledPlayers);
        }

        this.upcomingBuilders = new ArrayDeque<>(shuffledPlayers);
        this.privateBuildZones = new PrivateBuildZoneManager(world, map.singleZone, map.buildZonesStart, 1 + (int)Math.sqrt(this.participants.size()));
    }

    public static void open(GameSpace gameSpace, ServerWorld world, BuilderdashMap map, BDPictionaryConfig config) {
        gameSpace.setActivity(game -> new BDPictionaryActivity(gameSpace, game, world, map, config));
    }

    public boolean allowGuessing() {
        return phase == Phase.BUILDING;
    }

    public void onPlayerCorrectGuess(BDPlayer player) {
        player.player.ifOnline(this.gameSpace, s -> this.gameSpace.getPlayers().sendMessage(
                Text.translatable(GUESSED_WORD, s.getDisplayName()).formatted(Formatting.GREEN)));

        int award = 20;

        if (this.timeOfFirstGuess < 0) {
            this.timeOfFirstGuess = this.timeToPhaseChange;

            float normalizedTime = 1f - ((float)this.timeToPhaseChange / this.totalTime);
            float builderScoreWeight = 1f - (normalizedTime * normalizedTime);

            int builderAward = 10 + (int) (10 * builderScoreWeight);
            if (this.participants.containsKey(this.currentBuilder)) {
                this.participants.get(this.currentBuilder).score += builderAward;
            }
        } else {
            int maxPoints = 5 + bonusAwardPoints;
            award = 5 + (int) (maxPoints * ((float) timeToPhaseChange / timeOfFirstGuess));
        }

        if (this.bonusAwardPoints > 0) {
            this.bonusAwardPoints--;
        }

        notYetGuessed.remove(player.player);
        player.score += award;

        this.animations.add(SFX.PICTIONARY_CORRECT.play(this.world, player.player));
        this.animations.add(SFX.PICTIONARY_OTHER_PLAYER_GUESSED.play(this.world,
                this.participants.values().stream().filter(p -> p != player).map(p -> p.player).collect(Collectors.toList())));

        if (player.currentRole instanceof PictionaryGuesserRole role) {
            role.alreadyGuessed = true;
        }

        this.updateScoreboard();
    }

    @Override
    protected void onOpen() {
    }

    @Override
    protected void tick() {
        super.tick();

        switch (this.phase) {
            case CHOOSE_WORD -> checkBuilderOnline();
            case BUILDING -> {
                checkBuilderOnline();

                this.notYetGuessed.removeIf(r -> !r.isOnline(this.world));
                if (this.notYetGuessed.isEmpty()) {
                    currPlayerRevealWord();
                }

                int totalGuessers = this.participants.size() - 1;
                int guessed = totalGuessers - this.notYetGuessed.size();
                float guessedProportion = (float) guessed / totalGuessers;

                int minTime = this.config.minBuildTime() * SEC;
                int maxTime = this.config.maxBuildTime() * SEC;

                float threshold = this.config.guesserThreshold();
                if (guessedProportion >= threshold * threshold) {
                    this.scaleTimeTo((maxTime + minTime) / 2);
                } else if (guessedProportion >= threshold) {
                    this.scaleTimeTo(minTime);
                }

                float revealProportion = 1.0f - (float)this.timeToPhaseChange / this.totalTime;
                revealProportion *= this.config.revealPercent();

                int charsToReveal = Math.round(revealProportion * (this.hiddenChars.size() + 1)) - 1;

                if (charsToReveal != this.revealedChars) {
                    this.revealedChars = charsToReveal;
                    this.hintText = this.generateHintText();

                    this.updateDisplay();
                }

                if (this.hintText != null) for (var player : this.participants.values()) {
                    if (player.currentRole instanceof PictionaryGuesserRole role && !role.alreadyGuessed) {
                        player.player.ifOnline(this.gameSpace, s ->
                                s.sendMessageToClient(this.hintText, true));
                    }
                }

                this.currentBuilder.ifOnline(this.gameSpace, s ->
                        s.sendMessageToClient(this.promptText, true));
            }
        }
    }

    private void scaleTimeTo(int newTime) {
        if (newTime >= this.timeToPhaseChange) {
            return;
        }

        float scaleFactor = (float) newTime / this.timeToPhaseChange;
        this.timeToPhaseChange = newTime;

        if (this.timeOfFirstGuess >= 0) {
            this.timeOfFirstGuess = (int) (this.timeOfFirstGuess * scaleFactor);
        }
    }

    private Text generateHintText() {
        if (this.revealedChars < 0) {
            return null;
        }

        var builder = new StringBuilder();

        if (this.currentWord != null && this.currentWord.length > 0 && this.currentWord[0] != null) {
            var wordLabel = this.currentWord[0];
            for (int i = 0; i < wordLabel.length(); i++) {
                char c = wordLabel.charAt(i);

                int hideIndex = this.hiddenChars.indexOf(i);

                if (!(Character.isAlphabetic(c) || Character.isDigit(c)) || (hideIndex >= 0 && hideIndex < this.revealedChars)) {
                    builder.append(c);
                } else {
                    builder.append('_');
                }
            }
        }

        return Text.translatable(HINT, Text.literal(builder.toString())).formatted(Formatting.YELLOW);
    }

    private void updateDisplay() {
        if (this.currentDisplay == null) {
            return;
        }

        switch (this.phase) {
            case BUILDING, CHOOSE_WORD -> {
                Text builderName = Text.empty();
                var builder = this.currentBuilder.getEntity(this.gameSpace);
                if (builder != null) {
                    builderName = Text.literal(builder.getNameForScoreboard()).formatted(Formatting.YELLOW);
                }

                var content = GenericContent.builder()
                        .addTop(builderName, 1, 8)
                        .addTop(this.phase == Phase.BUILDING ? IS_BUILDING : IS_CHOOSING_WORD, 1, 5);

                if (this.hintText != null && this.currentWord.length > 0 && this.currentWord[0] != null) {
                    float scale = (float) (((this.currentDisplay.sizeX - 0.75) * GenericContent.WIDTH_PER_BLOCK) / ((this.currentWord[0].length() * 6.2) + 30));
                    if (scale > 7) {
                        scale = 7;
                    }

                    content.addBottom(this.hintText, 1, scale);
                }

                this.currentDisplay.setContent(content.build());
            }
        }
    }

    private void changeCurrentDisplay(InWorldDisplay display) {
        if (this.currentDisplay != null) {
            this.currentDisplay.clear();
            if (this.currentDisplay.getAttachment() != null) {
                this.currentDisplay.getAttachment().destroy();
            }
        }

        this.currentDisplay = display;
        ChunkAttachment.of(this.currentDisplay, this.world, display.getPos());
    }

    private void checkBuilderOnline() {
        if (this.currentBuilder != null && !this.currentBuilder.isOnline(this.gameSpace)) {
            currPlayerRevealWord();
        }
    }

    @Override
    protected void nextPhase() {
        switch (this.phase) {
            case CHOOSE_WORD -> currPlayerBeginBuilding();
            case BUILDING -> currPlayerRevealWord();
            case WORD_REVEAL -> nextPlayerChooseWordOrEndGame();
            case DISPLAY_WIN -> endGame();
        }
    }

    public void nextPlayerChooseWordOrEndGame() {
        this.updateScoreboard();

        if (this.upcomingBuilders.isEmpty()) {
            displayWin();
            return;
        }

        this.phase = Phase.CHOOSE_WORD;
        this.currentBuilder = upcomingBuilders.removeLast();

        this.currentWord = null;
        this.revealedChars = -1;
        this.hintText = null;

        while (this.currentBuilder == null || !this.currentBuilder.isOnline(this.world)) {
            if (this.upcomingBuilders.isEmpty()) {
                displayWin();
                return;
            }

            this.currentBuilder = upcomingBuilders.removeLast();
        }

        this.timeToPhaseChange = this.config.wordChooseTime() * SEC;
        this.totalTime = this.timeToPhaseChange;

        this.setTimerBar(BossBar.Color.YELLOW, BossBar.Style.NOTCHED_6);

        var buildZone = this.privateBuildZones.requestNewBuildZone();
        this.respawn = buildZone;

        this.changeCurrentDisplay(buildZone.displays()[0]);
        this.updateDisplay();

        this.notYetGuessed.clear();
        for (var bdPlayer : this.participants.values()) {
            if (bdPlayer.player.equals(this.currentBuilder)) {
                bdPlayer.updateRole(new PictionaryBuilderRole(this.world, bdPlayer, buildZone, this));
            } else {
                bdPlayer.updateRole(new PictionaryGuesserRole(this.world, bdPlayer, this));
                this.notYetGuessed.add(bdPlayer.player);
            }

            bdPlayer.player.ifOnline(this.gameSpace, this::spawnParticipant);
        }

        this.currentBuilder.ifOnline(this.gameSpace, s -> this.gameSpace.getPlayers().showTitle(
                Text.literal(s.getNameForScoreboard()).formatted(Formatting.YELLOW),
                IS_CHOOSING_WORD,
                10, 5 * SEC, 10
        ));

        var words = new String[][]{this.words.pop(), this.words.pop(), this.words.pop()};
        setCurrentWord(words[this.world.random.nextInt(3)]);

        this.chooseWordGui = new ChooseWordGui(this.currentBuilder.getEntity(this.world), words[0], words[1], words[2]) {
            @Override
            public void wordChosen(String[] word) {
                BDPictionaryActivity.this.setCurrentWord(word);
                BDPictionaryActivity.this.chooseWordGui = null;
                BDPictionaryActivity.this.currPlayerBeginBuilding();

                this.stayOpen = false;
                this.close();
            }
        };
        this.chooseWordGui.open();

        this.animations.add(SFX.PICTIONARY_NEW_ROUND.play(this.world));
    }

    public void setCurrentWord(String[] word) {
        this.currentWord = word;

        this.hiddenChars.clear();
        var wordLabel = word[0];
        for (int i = 0; i < wordLabel.length(); i++) {
            char c = wordLabel.charAt(i);
            if (Character.isAlphabetic(c)) {
                this.hiddenChars.add(i);
            }
        }

        this.promptText = Text.translatable(YOU_ARE_BUILDING, wordLabel).formatted(Formatting.GREEN);

        Collections.shuffle(this.hiddenChars);
    }

    public void currPlayerBeginBuilding() {
        this.phase = Phase.BUILDING;
        this.timeToPhaseChange = this.config.maxBuildTime() * SEC;
        this.totalTime = this.timeToPhaseChange;

        this.bonusAwardPoints = 10;
        this.timeOfFirstGuess = -1;

        if (this.chooseWordGui != null) {
            this.chooseWordGui.close();
            this.chooseWordGui = null;
        }

        this.gameSpace.getPlayers().showTitle(Text.empty(), WORD_CHOSEN, 10, 3 * SEC, 10);
        for (var player : this.participants.values()) {
            if (player.currentRole instanceof PictionaryGuesserRole) {
                player.player.ifOnline(this.gameSpace, s ->
                        s.sendMessageToClient(GUESS_IN_CHAT, false));
            }
        }

        this.setTimerBar(BossBar.Color.BLUE, BossBar.Style.NOTCHED_20);
        this.timesToAnnounce.add(TIME_ONE_MIN);
        this.timesToAnnounce.add(TIME_THIRTY_SEC);
        this.timesToAnnounce.add(TIME_TEN_SEC);
        this.timesToAnnounce.addAll(COUNTDOWN_FROM_FIVE);

        updateDisplay();

        this.animations.add(SFX.PICTIONARY_START_GUESSING.play(this.world));
    }

    public void currPlayerRevealWord() {
        this.updateScoreboard();

        this.phase = Phase.WORD_REVEAL;
        this.timeToPhaseChange = 5 * SEC;
        this.totalTime = this.timeToPhaseChange;

        if (this.currentWord != null && this.currentWord.length > 0 && this.currentWord[0] != null) {
            var wordText = Text.literal(this.currentWord[0]).formatted(Formatting.GREEN);

            this.gameSpace.getPlayers().showTitle(
                    wordText,
                    WAS_THE_WORD,
                    5, 6 * SEC, 0
            );

            float scale = (float) (((this.currentDisplay.sizeX - 0.75) * GenericContent.WIDTH_PER_BLOCK) / (this.currentWord[0].length() * 6.2));
            if (scale > 9) {
                scale = 9;
            }
            this.currentDisplay.setContent(GenericContent.builder()
                    .addTop(wordText, 1, scale)
                    .addTop(WAS_THE_WORD, 1, 5)
                    .build()
            );
        }

        this.currentWord = null;
        for (var player : this.participants.values()) {
            player.updateRole(new PlayerRole.Flying(this.world, player));
        }

        this.removeTimerInfo();
        this.promptText = Text.empty();

        this.animations.add(SFX.PICTIONARY_WORD_REVEAL.play(this.world));
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

        var winnerZone = this.associatedBuildZones.get(winner.player);

        if (winnerZone == null) {
            this.gameSpace.close(GameCloseReason.ERRORED);
            return;
        }

        this.openWinArea(winner, winnerZone);
    }

    public void updateScoreboard() {
        this.scoreboard.clearLines();
        this.scoreboard.addLines(this.createScoresForScoreboard());
    }

    public enum Phase {
        CHOOSE_WORD,
        BUILDING,
        WORD_REVEAL,
        DISPLAY_WIN
    }
}
