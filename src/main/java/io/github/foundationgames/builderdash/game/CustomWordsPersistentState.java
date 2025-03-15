package io.github.foundationgames.builderdash.game;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.foundationgames.builderdash.BDUtil;
import io.github.foundationgames.builderdash.Builderdash;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

public class CustomWordsPersistentState extends PersistentState {
    public static final String SPLIT_STRING_LIST = "[,\\n] ?+";

    public static final PersistentState.Type<CustomWordsPersistentState> TYPE = new PersistentState.Type<>(
            CustomWordsPersistentState::new,
            CustomWordsPersistentState::readNbt,
            null
    );

    public final List<String[]> customWords = new ArrayList<>();
    public boolean replaceDefault;

    public static CustomWordsPersistentState get(MinecraftServer server, String key) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, key);
    }

    public static String getKeyForGame(String game) {
        return String.format(Builderdash.ID + "_%s_custom_words", game);
    }

    public int setWords(String delimitedWordList) {
        this.replaceDefault = true;
        this.customWords.clear();

        return this.addWords(delimitedWordList);
    }

    public int addWords(String delimitedWordList) {
        var words = delimitedWordList.split(SPLIT_STRING_LIST);
        for (var word : words) {
            if (word.length() <= 1) {
                continue;
            }

            this.customWords.add(word.split("="));
        }

        this.markDirty();

        return words.length;
    }

    public void resetWords() {
        this.replaceDefault = false;
        this.customWords.clear();

        this.markDirty();
    }

    public void addDefaultWords() {
        this.replaceDefault = true;

        this.markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        nbt.putBoolean("replace_default", this.replaceDefault);

        var list = new NbtList();
        for (var word : this.customWords) {
            list.add(NbtString.of(String.join("=", word)));
        }
        nbt.put("custom_words", list);

        return nbt;
    }

    public static CustomWordsPersistentState readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        var state = new CustomWordsPersistentState();
        state.replaceDefault = nbt.getBoolean("replace_default");

        var list = nbt.getList("custom_words", NbtCompound.STRING_TYPE);
        for (var wordNbt : list) {
            if (wordNbt instanceof NbtString wordStr) {
                state.customWords.add(wordStr.asString().split("="));
            }
        }

        return state;
    }

    public static final String WORD_LIST_ADD = "command.builderdash.word_list_add";
    public static final String WORD_LIST_SET = "command.builderdash.word_list_set";
    public static final String WORD_LIST_GET_NO_DEFAULT = "command.builderdash.word_list_get_count_no_default";
    public static final String WORD_LIST_GET = "command.builderdash.word_list_get_count";
    public static final String WORD_LIST_RESET = "command.builderdash.word_list_reset";
    public static final String WORD_LIST_ADD_DEFAULT = "command.builderdash.word_list_add_default";

    public static LiteralArgumentBuilder<ServerCommandSource> createCommand(LiteralArgumentBuilder<ServerCommandSource> command, String game) {
        var gameName = Text.translatable("name." + Builderdash.ID + "." + game);
        var key = getKeyForGame(game);
        var perm = BDUtil.permission(game, BDUtil.PERM_GAME_EDIT, 2);

        return command
                .then(CommandManager.literal("setwords").requires(perm)
                        .then(CommandManager.argument("word_list", StringArgumentType.greedyString())
                                .executes(cmd -> {
                                    var wordList = cmd.getArgument("word_list", String.class);
                                    var customWords = CustomWordsPersistentState.get(cmd.getSource().getServer(), key);

                                    int ct = customWords.setWords(wordList);
                                    cmd.getSource().sendFeedback(() -> Text.translatable(WORD_LIST_SET, gameName, ct), true);
                                    return 0;
                                })
                        )
                )
                .then(CommandManager.literal("addwords").requires(perm)
                        .then(CommandManager.argument("word_list", StringArgumentType.greedyString())
                                .executes(cmd -> {
                                    var wordList = cmd.getArgument("word_list", String.class);
                                    var customWords = CustomWordsPersistentState.get(cmd.getSource().getServer(), key);

                                    int ct = customWords.addWords(wordList);
                                    cmd.getSource().sendFeedback(() -> Text.translatable(WORD_LIST_ADD, ct, gameName), true);
                                    return 0;
                                })
                        )
                )
                .then(CommandManager.literal("resetwords").requires(perm)
                        .executes(cmd -> {
                            var customWords = CustomWordsPersistentState.get(cmd.getSource().getServer(), key);

                            customWords.resetWords();
                            cmd.getSource().sendFeedback(() -> Text.translatable(WORD_LIST_RESET, gameName), true);
                            return 0;
                        })
                )
                .then(CommandManager.literal("getwordcount").requires(perm)
                        .executes(cmd -> {
                            var customWords = CustomWordsPersistentState.get(cmd.getSource().getServer(), key);
                            int ct = customWords.customWords.size();

                            cmd.getSource().sendFeedback(() ->
                                            Text.translatable(customWords.replaceDefault ? WORD_LIST_GET_NO_DEFAULT : WORD_LIST_GET, ct, gameName),
                                    true);
                            return 0;
                        })
                )
                .then(CommandManager.literal("withdefault").requires(perm)
                        .executes(cmd -> {
                            var customWords = CustomWordsPersistentState.get(cmd.getSource().getServer(), key);

                            customWords.addDefaultWords();
                            cmd.getSource().sendFeedback(() -> Text.translatable(WORD_LIST_ADD_DEFAULT, gameName), true);
                            return 0;
                        })
                );
    }
}
