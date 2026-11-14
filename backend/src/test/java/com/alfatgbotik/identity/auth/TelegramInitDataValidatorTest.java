package com.alfatgbotik.identity.auth;

import com.alfatgbotik.identity.service.TelegramAuthUnavailableException;
import com.alfatgbotik.identity.service.TelegramInitDataValidator;
import com.alfatgbotik.telegram.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramInitDataValidatorTest {

    static final String TEST_TOKEN = "stage4-test-bot-token";
    static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final TelegramInitDataValidator validator = new TelegramInitDataValidator(
            new TelegramProperties(false, TEST_TOKEN), JSON, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsFixedHmacVectorCalculatedIndependently() {
        String initData = "auth_date=1794657600&query_id=test-query&user="
                + "%7B%22id%22%3A42%2C%22first_name%22%3A%22Test%22%7D"
                + "&hash=2a91fd033379b0cad0998a5b9bc966b3a0c00e42efbef02aeae92802d59b8f4f";

        assertThat(validator.validate(initData).telegramUserId()).isEqualTo(42);
    }

    @Test
    void acceptsSignedUnicodeUserAndAllSignedFieldsIncludingSignature() throws Exception {
        Map<String, String> fields = fields("{\"id\":42,\"first_name\":\"Иван + &\",\"last_name\":\"Петров\"}", NOW);
        fields.put("signature", "test-ed25519-signature");
        fields.put("start_param", "campaign=one&two");

        var identity = validator.validate(sign(fields));

        assertThat(identity.telegramUserId()).isEqualTo(42);
        assertThat(identity.displayName()).isEqualTo("Иван + & Петров");
    }

    @ParameterizedTest
    @ValueSource(longs = {-600, 30})
    void acceptsTimeBoundaries(long offset) throws Exception {
        assertThat(validator.validate(signedUser(42, NOW.plusSeconds(offset))).telegramUserId()).isEqualTo(42);
    }

    @ParameterizedTest
    @ValueSource(longs = {-601, 31})
    void rejectsExpiredAndFutureData(long offset) {
        assertThatThrownBy(() -> validator.validate(signedUser(42, NOW.plusSeconds(offset))))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsTamperingAndDuplicateDecodedKeys() throws Exception {
        String signed = signedUser(42, NOW);

        assertInvalid(signed.replace("Test", "Other"));
        assertInvalid(signed + "&%61uth_date=" + NOW.getEpochSecond());
        assertInvalid(signed + "&hash=" + "0".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "user=%XX", "hash=bad", "field", "=value", "&"})
    void rejectsMalformedQueryString(String value) {
        assertInvalid(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{\"id\":\"42\",\"first_name\":\"Test\"}",
            "{\"id\":42.0,\"first_name\":\"Test\"}", "{\"id\":0,\"first_name\":\"Test\"}",
            "{\"id\":4503599627370496,\"first_name\":\"Test\"}",
            "{\"id\":42,\"first_name\":true}", "{\"id\":42,\"first_name\":\"Test\",\"is_bot\":true}",
            "{\"id\":42,\"first_name\":\"Test\",\"is_bot\":\"false\"}",
            "{\"id\":42,\"id\":43,\"first_name\":\"Test\"}",
            "{\"id\":42,\"first_name\":\"Test\"} {}"
    })
    void rejectsInvalidSignedUser(String user) throws Exception {
        assertInvalid(sign(fields(user, NOW)));
    }

    @Test
    void rejectsMissingUserAndInvalidDate() throws Exception {
        Map<String, String> fields = fields("{}", NOW);
        fields.remove("user");
        assertInvalid(sign(fields));
        fields.put("user", "{\"id\":42,\"first_name\":\"Test\"}");
        fields.put("auth_date", "not-a-date");
        assertInvalid(sign(fields));
    }

    @Test
    void missingBotTokenDoesNotEnableAnAlternativeLogin() {
        TelegramInitDataValidator unconfigured = new TelegramInitDataValidator(
                new TelegramProperties(false, ""), JSON, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> unconfigured.validate("anything"))
                .isInstanceOf(TelegramAuthUnavailableException.class);
    }

    private void assertInvalid(String initData) {
        assertThatThrownBy(() -> validator.validate(initData)).isInstanceOf(BadCredentialsException.class)
                .hasMessage("Telegram authentication data is invalid").hasNoCause();
    }

    static String signedUser(long userId, Instant authDate) throws Exception {
        return sign(fields("{\"id\":" + userId + ",\"first_name\":\"Test\"}", authDate));
    }

    private static Map<String, String> fields(String user, Instant authDate) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("user", user);
        fields.put("auth_date", Long.toString(authDate.getEpochSecond()));
        fields.put("query_id", "test-query");
        return fields;
    }

    private static String sign(Map<String, String> fields) throws Exception {
        String canonical = new TreeMap<>(fields).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("\n"));
        Mac secret = Mac.getInstance("HmacSHA256");
        secret.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        Mac signature = Mac.getInstance("HmacSHA256");
        signature.init(new SecretKeySpec(secret.doFinal(TEST_TOKEN.getBytes(StandardCharsets.UTF_8)), "HmacSHA256"));
        String encoded = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return encoded + "&hash=" + HexFormat.of().formatHex(signature.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
