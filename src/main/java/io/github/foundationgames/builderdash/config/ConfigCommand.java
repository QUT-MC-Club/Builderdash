package io.github.foundationgames.builderdash.config;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.BiConsumer;
import java.util.function.Function;

public record ConfigCommand<S extends CommandSource>(Function<CommandContext<S>, Config> configGetter, Config prototype) {
    public static final String VALUE_SET_KEY = "builderdash.config.set_value";
    public static final String VALUE_QUERY_KEY = "builderdash.config.query_value";

    public LiteralArgumentBuilder<S> command(LiteralArgumentBuilder<S> cmd, BiConsumer<S, Text> feedbackSender) {
        for (var key : prototype) {
            cmd.then(
                    LiteralArgumentBuilder.<S>literal(key.key)
                            .then(
                                    key.<S>commandArg("value").executes(context -> {
                                        var opt = configGetter().apply(context).getForPrototype(key);

                                        opt.setFromCommandAndSave(context, "value");
                                        feedbackSender.accept(context.getSource(),
                                                Text.translatable(VALUE_SET_KEY, opt.key, opt.getCopyableValueText()));
                                        return 0;
                                    })
                            ).executes(context -> {
                                var cfg = configGetter().apply(context);
                                var opt = cfg.getForPrototype(key);

                                var descKey = String.format("builderdash.config.%s.%s.desc", cfg.id, opt.key);
                                feedbackSender.accept(context.getSource(),
                                        Text.translatable(VALUE_QUERY_KEY, opt.key, opt.getCopyableValueText()));
                                feedbackSender.accept(context.getSource(),
                                        Text.translatable(descKey).formatted(Formatting.GRAY));
                                return 0;
                            })
            );
        }

        return cmd;
    }
}
