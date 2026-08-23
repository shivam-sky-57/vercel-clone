package com.vercel.apiserver.util;

import java.security.SecureRandom;

public class SlugGenerator {

    private static final String[] ADJECTIVES = {
            "fast", "cool", "swift", "bright", "silent", "brave", "calm", "bold",
            "crisp", "eager", "gentle", "happy", "jolly", "keen", "lively", "proud",
            "quick", "sharp", "smart", "vibrant", "wild", "zealous", "agile", "mystic"
    };

    private static final String[] NOUNS = {
            "falcon", "tiger", "river", "mountain", "ocean", "forest", "storm", "cloud",
            "galaxy", "nebula", "comet", "beacon", "shadow", "spark", "valley", "breeze",
            "aurora", "phoenix", "canyon", "harbor", "meadow", "zenith", "vortex", "oasis"
    };

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generateSlug() {
        String adj = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[RANDOM.nextInt(NOUNS.length)];
        int num = 100 + RANDOM.nextInt(900);
        return adj + "-" + noun + "-" + num;
    }
}
