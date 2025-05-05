package io.github.foundationgames.builderdash.game.mode.pictionary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Locale;

public record WordQueue(WordList list, Deque<String[]> queue) {
    public static final String NON_ALPHANUM = "[^a-zA-Z0-9]";

    public static WordQueue ofShuffled(WordList list) {
        var words = new ArrayList<>(list.words());
        Collections.shuffle(words);

        return new WordQueue(list, new ArrayDeque<>(words));
    }

    public String[] pop() {
        if (queue().isEmpty()) {
            var words = new ArrayList<>(list.words());
            Collections.shuffle(words);

            queue().addAll(words);
        }

        return queue().removeFirst();
    }

    public static int levenshtein(String a, String b) {
        int[][] matrix = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) matrix[i][0] = i;
        for (int i = 0; i <= b.length(); i++) matrix[0][i] = i;

        for (int ai = 1; ai <= a.length(); ai++) {
            for (int bi = 1; bi <= b.length(); bi++) {
                int c = a.charAt(ai - 1) == b.charAt(bi - 1) ? 0 : 1;

                matrix[ai][bi] = Math.min(
                        matrix[ai - 1][bi] + 1,
                        Math.min(
                                matrix[ai][bi - 1] + 1,
                                matrix[ai - 1][bi - 1] + c
                        )
                );
            }
        }

        return matrix[a.length()][b.length()];
    }

    public static int compareToWord(String[] word, String guess) {
        var normGuess = guess.replaceAll(NON_ALPHANUM,"")
                .toLowerCase(Locale.ROOT);
        int min = Integer.MAX_VALUE;

        for (var alias : word) {
            var normAlias = alias.replaceAll(NON_ALPHANUM,"")
                    .toLowerCase(Locale.ROOT);
            int dist = levenshtein(normAlias, normGuess);
            if (dist < min) {
                min = dist;
            }
        }

        return min;
    }
}
