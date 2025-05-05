package io.github.foundationgames.builderdash;

import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.block.NoteBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.map_templates.TemplateRegion;
import xyz.nucleoid.plasmid.api.game.GameOpenException;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public enum BDUtil {;
    public static final String[] HEAD_MONITOR_1 = {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmY5Njk0YTUyMjEyNzE5ZTExM2RjN2U2YWY2OThhOWZjM2FiNjNjNzQ5OTVmZmFkYzU3ZDM0NmZhY2U0ZTc1In19fQ==",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjFkODU5ZThiMTRmNjI2NDY4NTljZjM4MDRhNjRmMTA2MGQ2ODc5MzQxYjRjMzM4NWI0NmEwZWM0MGZhZjczYyJ9fX0="
    };
    public static final String[] HEAD_MONITOR_2 = {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDBlYmMzMzUyYzI1MmQyMzU3MTNkNWNiY2JjYTg3OTAyNTIyMWNhYWFlOWM0YWUwY2FiNzkyZDk3NGU2NSJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjNkOTNlOGI1ZmIwYjVkNTBhYmQ0ZWY4ODUzMmY0Njg3NGI5OTI0ZjY2OGRkYjAxMDkxNDY4ZTRlNjFiOWM4MyJ9fX0="
    };
    public static final String[] HEAD_READY = {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmZmZmE2MDJlMzY4MjE0ZGQ2MmNlY2Q2ODE1ZjE0OTI2ZWU1N2I5NDgxNDM0OTVlOTMxYTc3NjM2MzcyYmU1YSJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGM4MTNiZTEwOGIzMTE1N2ZiYjliNTBkODM2Y2FhMGQyNjEyZjZiY2ZlZjRhZTBjNmI3N2FlMzQ3OWUwMmEzZSJ9fX0="
    };

    public static final String PERM_GAME_OPEN = "open";
    public static final String PERM_GAME_EDIT = "edit";

    public static final String PERM_GLOBAL_TOOLBOX = Builderdash.ID + ".toolbox";
    public static final String PERM_GLOBAL_CONFIG = Builderdash.ID + ".config";

    public static TemplateRegion regionOrThrow(Identifier mapId, MapTemplate template, String marker) throws GameOpenException {
        var region = template.getMetadata().getFirstRegion(marker);
        if (region == null) {
            throw new GameOpenException(Text.literal(String.format("Map %s is missing region '%s'", mapId, marker)));
        }

        return region;
    }

    public static ProfileComponent skinProfile(String queryBase64) {
        var properties = new PropertyMap();
        properties.put("textures", new Property("textures", queryBase64));

        return new ProfileComponent(Optional.empty(), Optional.empty(), properties);
    }

    public static ItemStack customHead(String queryBase64) {
        var stack = Items.PLAYER_HEAD.getDefaultStack();
        stack.set(DataComponentTypes.PROFILE, skinProfile(queryBase64));

        return stack;
    }

    public static IntList range(int startInc, int endExc) {
        return new IntArrayList(IntStream.range(startInc, endExc).toArray());
    }

    // Mutates <ints>
    public static IntList circshift(IntList ints, int by) {
        int[] copy = new int[ints.size()];
        ints.toArray(copy);

        for (int i = 0; i < copy.length; i++) {
            ints.set(i, copy[(i + by) % copy.length]);
        }

        return ints;
    }

    public static Predicate<ServerCommandSource> permission(String game, String permission, int otherwise) {
        return src ->
                Permissions.check(src, Builderdash.ID + ".any." + permission, otherwise) &&
                Permissions.check(src, Builderdash.ID + "." + game + "." + permission, otherwise);
    }

    public static float fSharp(int note) {
        return 1.0f / NoteBlock.getNotePitch(note);
    }
}
