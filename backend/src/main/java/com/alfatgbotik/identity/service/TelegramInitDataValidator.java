package com.alfatgbotik.identity.service;

import com.alfatgbotik.identity.model.TelegramIdentity;
import com.alfatgbotik.telegram.TelegramProperties;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class TelegramInitDataValidator {

    private final byte[] secretKey;
    private final JsonMapper json;
    private final Clock clock;

    public TelegramInitDataValidator(TelegramProperties properties, JsonMapper json, Clock clock) {
        this.secretKey = properties.getToken() == null || properties.getToken().isBlank() ? null
                : hmac("WebAppData".getBytes(StandardCharsets.UTF_8), properties.getToken());
        this.json = json;
        this.clock = clock;
    }

    public TelegramIdentity validate(String initData) {
        if (secretKey == null) {
            throw new TelegramAuthUnavailableException();
        }
        if (initData == null || initData.isBlank() || initData.length() > 16384) {
            throw invalid();
        }
        Map<String, String> fields = parse(initData);
        String hash = fields.remove("hash");
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
            throw invalid();
        }
        String dataCheckString = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("\n"));
        if (!MessageDigest.isEqual(hmac(secretKey, dataCheckString), HexFormat.of().parseHex(hash))) {
            throw invalid();
        }
        validateDate(fields.get("auth_date"));
        return readUser(fields.get("user"));
    }

    private Map<String, String> parse(String initData) {
        Map<String, String> fields = new TreeMap<>();
        try {
            for (String pair : initData.split("&", -1)) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    throw invalid();
                }
                String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                if (!key.matches("[A-Za-z0-9_]+") || fields.putIfAbsent(key, value) != null) {
                    throw invalid();
                }
            }
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        return fields;
    }

    private void validateDate(String value) {
        if (value == null || !value.matches("[0-9]{1,12}")) {
            throw invalid();
        }
        Instant authDate = Instant.ofEpochSecond(Long.parseLong(value));
        Instant now = clock.instant();
        if (authDate.isBefore(now.minusSeconds(600)) || authDate.isAfter(now.plusSeconds(30))) {
            throw invalid();
        }
    }

    private TelegramIdentity readUser(String value) {
        if (value == null) {
            throw invalid();
        }
        JsonNode user;
        try {
            user = json.readTree(value);
        } catch (JacksonException exception) {
            throw invalid();
        }
        if (user == null || !user.isObject()) {
            throw invalid();
        }
        JsonNode id = user.get("id");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()
                || id.longValue() <= 0 || id.longValue() >= (1L << 52)) {
            throw invalid();
        }
        JsonNode isBot = user.get("is_bot");
        if (isBot != null && (!isBot.isBoolean() || isBot.booleanValue())) {
            throw invalid();
        }
        String firstName = name(user.get("first_name"), true);
        String lastName = name(user.get("last_name"), false);
        String displayName = Stream.of(firstName, lastName).filter(name -> name != null && !name.isBlank())
                .map(String::strip).collect(Collectors.joining(" "));
        if (displayName.codePointCount(0, displayName.length()) > 160) {
            throw invalid();
        }
        return new TelegramIdentity(id.longValue(), displayName.isEmpty() ? null : displayName);
    }

    private String name(JsonNode value, boolean required) {
        if (!required && (value == null || value.isNull())) {
            return null;
        }
        if (value == null || !value.isString()) {
            throw invalid();
        }
        return value.stringValue();
    }

    private byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable");
        }
    }

    private BadCredentialsException invalid() {
        return new BadCredentialsException("Telegram authentication data is invalid");
    }
}
