package com.shortel.url.util;

/**
 * Base62 encoder/decoder for Snowflake IDs → 7-character short codes.
 * Alphabet: 0-9 A-Z a-z (62 chars). 62^7 = 3.5 trillion unique codes.
 */
public final class Base62Encoder {

    private static final String ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int  BASE       = 62;
    private static final int  CODE_LEN   = 7;

    private Base62Encoder() {}

    public static String encode(long id) {
        if (id < 0) throw new IllegalArgumentException("ID must be non-negative");
        StringBuilder sb = new StringBuilder();
        long n = id;
        while (n > 0) {
            sb.insert(0, ALPHABET.charAt((int)(n % BASE)));
            n /= BASE;
        }
        // Left-pad with '0' to ensure exactly CODE_LEN characters
        while (sb.length() < CODE_LEN) sb.insert(0, '0');
        return sb.length() > CODE_LEN ? sb.substring(sb.length() - CODE_LEN) : sb.toString();
    }

    public static long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            int idx = ALPHABET.indexOf(c);
            if (idx == -1) throw new IllegalArgumentException("Invalid Base62 character: " + c);
            result = result * BASE + idx;
        }
        return result;
    }
}
