package io.github.foundationgames.builderdash.config;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
import java.util.function.Supplier;

public class Config extends ArrayList<ConfigOption<?>> {
    public final String id;
    public final @Nullable Supplier<Path> path;

    public Config(String id, @Nullable Supplier<Path> path) {
        this.id = id;
        this.path = path;
    }

    public <V, O extends ConfigOption<V>> O opt(O opt) {
        this.add(opt);
        return opt;
    }

    public ConfigOption<?> getForPrototype(ConfigOption<?> key) {
        for (var opt : this) {
            if (opt.key.equals(key.key)) {
                return opt;
            }
        }

        return key;
    }

    public ConfigOption.BooleanOption optBool(String key, boolean value) {
        return this.opt(new ConfigOption.BooleanOption(key, value, this));
    }

    public ConfigOption.IntOption optInt(String key, int value) {
        return this.opt(new ConfigOption.IntOption(key, value, new int[0], this));
    }

    public ConfigOption.IntOption optInt(String key, int value, int min) {
        return this.opt(new ConfigOption.IntOption(key, value, new int[] {min}, this));
    }

    public ConfigOption.IntOption optInt(String key, int value, int min, int max) {
        return this.opt(new ConfigOption.IntOption(key, value, new int[] {min, max}, this));
    }

    public ConfigOption.StringListOption optStrings(String key) {
        return this.opt(new ConfigOption.StringListOption(key, this));
    }

    public void load() throws IOException {
        if (this.path == null) {
            return;
        }

        var path = this.path.get();

        if (!Files.exists(path)) {
            return;
        }

        try (var in = Files.newInputStream(path)) {
            var properties = new Properties();
            properties.load(in);

            for (var opt : this) {
                opt.read(properties);
            }
        }
    }

    public void save() throws IOException {
        if (this.path == null) {
            return;
        }

        var path = this.path.get();

        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
        }

        try (var out = Files.newOutputStream(path)) {
            var properties = new Properties();

            for (var opt : this) {
                opt.write(properties);
            }

            properties.store(out, this.id);
        }
    }
}
