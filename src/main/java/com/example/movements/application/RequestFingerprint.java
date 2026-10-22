package com.example.movements.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String computeHash(MovementCommand command) {
        String canonical = String.format("%s:%s:%s:%s:%s",
                command.type().name(),
                command.sourceAccountId(),
                command.destinationAccountId() != null ? command.destinationAccountId().toString() : "",
                command.amount().toPlainString(),
                command.currency()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
