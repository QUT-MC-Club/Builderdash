package io.github.foundationgames.builderdash.config;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.foundationgames.builderdash.Builderdash;
import io.github.foundationgames.builderdash.game.CustomWordsPersistentState;
import net.minecraft.command.CommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public abstract class ConfigOption<T> {
    protected final Config owner;

    public final String key;
    protected T value;

    protected ConfigOption(String key, T initialValue, Config owner) {
        this.owner = owner;

        this.key = key;
        this.value = initialValue;
    }

    protected abstract void read(Properties properties);

    protected abstract void write(Properties properties);

    protected abstract ArgumentType<?> commandArgType();

    public final <S extends CommandSource> RequiredArgumentBuilder<S, ?> commandArg(String name) {
        return RequiredArgumentBuilder.argument(name, this.commandArgType());
    }

    public T get() {
        return this.value;
    }

    public void set(T value) {
        this.value = value;
    }

    public void setAndSave(T value) {
        this.set(value);
        try {
            this.owner.save();
        } catch (IOException e) {
            Builderdash.LOG.error("Error saving config '{}' while setting value '{}={}'",
                    this.owner.id, this.key, this.value, e);
        }
    }

    public String getString() {
        return get().toString();
    }

    public Text getCopyableValueText() {
        var value = getString();
        return Text.literal(value).styled(s ->
                s.withColor(0x8fabff).withClickEvent(new ClickEvent(net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, value))
        );
    }

    public abstract <S extends CommandSource> void setFromCommandAndSave(CommandContext<S> ctx, String argName);

    public static class BooleanOption extends ConfigOption<Boolean> {
        public BooleanOption(String key, Boolean initialValue, Config owner) {
            super(key, initialValue, owner);
        }

        @Override
        protected void read(Properties properties) {
            if (properties.containsKey(this.key)) {
                this.value = "true".equals(properties.getProperty(this.key));
            }
        }

        @Override
        protected void write(Properties properties) {
            properties.setProperty(this.key, this.value ? "true" : "false");
        }

        @Override
        public ArgumentType<Boolean> commandArgType() {
            return BoolArgumentType.bool();
        }

        @Override
        public <S extends CommandSource> void setFromCommandAndSave(CommandContext<S> ctx, String argName) {
            this.setAndSave(BoolArgumentType.getBool(ctx, argName));
        }
    }

    public static class IntOption extends ConfigOption<Integer> {
        private final int[] bounds;
        public IntOption(String key, Integer initialValue, int[] bounds, Config owner) {
            super(key, initialValue, owner);
            this.bounds = bounds;
        }

        @Override
        protected void read(Properties properties) {
            if (properties.containsKey(this.key)) {
                this.value = Integer.parseInt(properties.getProperty(this.key));
            }
        }

        @Override
        protected void write(Properties properties) {
            properties.setProperty(this.key, Integer.toString(this.value));
        }

        @Override
        public ArgumentType<Integer> commandArgType() {
            if (this.bounds.length == 1) {
                return IntegerArgumentType.integer(this.bounds[0]);
            }
            if (this.bounds.length == 2) {
                return IntegerArgumentType.integer(this.bounds[0], this.bounds[1]);
            }
            return IntegerArgumentType.integer();
        }

        @Override
        public <S extends CommandSource> void setFromCommandAndSave(CommandContext<S> ctx, String argName) {
            this.setAndSave(IntegerArgumentType.getInteger(ctx, argName));
        }
    }

    public static class StringListOption extends ConfigOption<ArrayList<String>> {
        public StringListOption(String key, Config owner) {
            super(key, new ArrayList<>(), owner);
        }

        private static ArrayList<String> parse(String string) {
            return new ArrayList<>(List.of(string.split(CustomWordsPersistentState.SPLIT_STRING_LIST)));
        }

        private static String stringify(ArrayList<String> list) {
            return list.stream().reduce((a, b) -> a + "," + b).orElse("");
        }

        @Override
        protected void read(Properties properties) {
            if (properties.containsKey(this.key)) {
                this.value = parse(properties.getProperty(this.key));
            }
        }

        @Override
        protected void write(Properties properties) {
            properties.setProperty(this.key, stringify(this.value));
        }

        @Override
        public String getString() {
            return stringify(this.value);
        }

        @Override
        public ArgumentType<String> commandArgType() {
            return StringArgumentType.greedyString();
        }

        @Override
        public <S extends CommandSource> void setFromCommandAndSave(CommandContext<S> ctx, String argName) {
            this.setAndSave(parse(StringArgumentType.getString(ctx, argName)));
        }
    }
}
