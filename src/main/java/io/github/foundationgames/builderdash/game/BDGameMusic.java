package io.github.foundationgames.builderdash.game;

import com.google.common.collect.ImmutableList;
import io.github.foundationgames.builderdash.Builderdash;
import io.github.foundationgames.builderdash.config.ServerConfigAccess;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.InvalidIdentifierException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public record BDGameMusic(List<Entry> musicEntries) {
    public static BDGameMusic ofStrings(List<String> strings) {
        var entries = ImmutableList.<Entry>builder();

        for (var str : strings) {
            var durAndId = str.split("\\$");

            try {
                int duration = 120;
                Identifier id = null;

                if (durAndId.length >= 2) {
                    duration = Integer.parseInt(durAndId[0]);
                    id = Identifier.tryParse(durAndId[1]);
                } else if (durAndId.length == 1) {
                    id = Identifier.tryParse(durAndId[0]);
                }

                if (id != null) {
                    entries.add(new Entry(id, duration));
                }
            } catch (InvalidIdentifierException | NumberFormatException ex) {
                Builderdash.LOG.error("Error parsing game music", ex);
            }
        }

        return new BDGameMusic(entries.build());
    }

    public record Entry(Identifier soundId, int durationSec) {
        public void play(ServerPlayerEntity player) {
            var server = player.getServer();
            var playerConfig = ServerConfigAccess.forServer(server).getPlayerConfig(player.getUuid());

            float volume = (float) playerConfig.musicVolume.get() / 100;

            if (volume > 0.005) {
                player.playSoundToPlayer(SoundEvent.of(soundId()), SoundCategory.MASTER, volume, 1);
            }
        }
    }

    public record Playlist(BDGameMusic music, Deque<Entry> queue) {
        public static Playlist ofShuffled(BDGameMusic music) {
            var entries = new ArrayList<>(music.musicEntries());
            Collections.shuffle(entries);

            return new Playlist(music, new ArrayDeque<>(entries));
        }

        public Entry pop() {
            if (queue().isEmpty()) {
                var words = new ArrayList<>(music().musicEntries());
                Collections.shuffle(words);

                queue().addAll(words);
            }

            if (queue().isEmpty()) {
                return null;
            }

            return queue().removeFirst();
        }
    }
}
