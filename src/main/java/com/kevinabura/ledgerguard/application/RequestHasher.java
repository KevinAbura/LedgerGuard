package com.kevinabura.ledgerguard.application;

import com.kevinabura.ledgerguard.error.ValidationException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class RequestHasher {
    private final ObjectMapper objectMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(Object request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(request)));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new ValidationException("Unable to create a deterministic request hash");
        }
    }
}
