package io.github.naveen11.placemap.livehub.sse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Turns a client address into the key its connection limit is counted under: a salted SHA-256 hash, as for the
 * api's rate limits, so no address is ever stored. The salt is random per process.
 */
final class ClientIds {

    private final byte[] salt = new byte[32];

    ClientIds() {
        new SecureRandom().nextBytes(salt);
    }

    String of(String address) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return HexFormat.of().formatHex(digest.digest(address.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }
}
