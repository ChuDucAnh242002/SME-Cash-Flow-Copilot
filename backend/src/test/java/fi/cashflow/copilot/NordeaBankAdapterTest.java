package fi.cashflow.copilot;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NordeaBankAdapterTest {
    @TempDir Path directory;
    HttpServer server;
    KeyPair keys;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Europe/Helsinki"));
    final List<String> queries = new ArrayList<>();
    boolean signaturesValid;

    @BeforeEach
    void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        Files.writeString(directory.resolve("secret"), "test-secret");
        Files.writeString(directory.resolve("token"), "test-token");
        Files.writeString(directory.resolve("key"), "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        signaturesValid = true;
    }

    @AfterEach
    void stop() { server.stop(0); }

    NordeaBankAdapter adapter() {
        return new NordeaBankAdapter(new ObjectMapper(), clock, "test-client", "FI1234-EUR",
                LocalDate.of(2026, 1, 1), directory.resolve("secret").toString(),
                directory.resolve("token").toString(), directory.resolve("key").toString(),
                HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        queries.add(exchange.getRequestURI().toString());
        try {
            String header = exchange.getRequestHeaders().getFirst("Signature");
            String encoded = header.substring(header.indexOf("signature=\"") + 11, header.length() - 1);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keys.getPublic());
            String input = "(request-target): get " + exchange.getRequestURI()
                    + "\nx-nordea-originating-host: 127.0.0.1\nx-nordea-originating-date: "
                    + exchange.getRequestHeaders().getFirst("X-Nordea-Originating-Date");
            verifier.update(input.getBytes(StandardCharsets.UTF_8));
            signaturesValid &= verifier.verify(Base64.getDecoder().decode(encoded))
                    && "Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && "test-secret".equals(exchange.getRequestHeaders().getFirst("X-IBM-Client-Secret"));
        } catch (GeneralSecurityException | RuntimeException exception) {
            signaturesValid = false;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    String account() {
        return """
                {"group_header":{"http_code":200},"response":{"_id":"FI1234-EUR","country":"FI",
                "currency":"EUR","account_name":"Sandbox Company","booked_balance":"1000.25"}}
                """;
    }

    @Test
    void signsRequestsAndFollowsEmptyContinuationPages() {
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (!exchange.getRequestURI().getPath().endsWith("transactions")) respond(exchange, 200, account());
            else if (!query.contains("continuation_key")) respond(exchange, 200,
                    "{\"response\":{\"transactions\":[],\"continuation_key\":\"next +/\"}}");
            else respond(exchange, 200, """
                    {"response":{"transactions":[{"transaction_id":"tx1","currency":"EUR","booking_date":"2026-10-20",
                    "amount":"300.25","reference":"10001","counterparty_name":"Customer"}]}}
                    """);
        });
        server.start();
        var snapshot = adapter().fetch();
        assertThat(snapshot.account().bookedBalance()).isEqualByComparingTo("1000.25");
        assertThat(snapshot.transactions()).hasSize(1);
        assertThat(queries).hasSize(3);
        assertThat(queries.getLast()).contains("continuation_key=next%20%2B%2F");
        assertThat(signaturesValid).isTrue();
    }

    @Test
    void rejectsExpiredTokensWithoutExposingResponseSecrets() {
        server.createContext("/", exchange -> respond(exchange, 401, "{\"secret\":\"do-not-expose\"}"));
        server.start();
        assertThatThrownBy(() -> adapter().fetch()).isInstanceOf(BankAccessException.class)
                .hasMessageContaining("HTTP 401").hasMessageNotContaining("do-not-expose");
    }

    @Test
    void sandboxUsesPortalSignatureMarkerWithoutAPrivateKeyFile() throws Exception {
        Files.delete(directory.resolve("key"));
        List<String> signatures = new ArrayList<>();
        server.createContext("/", exchange -> {
            signatures.add(exchange.getRequestHeaders().getFirst("Signature"));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token");
            respond(exchange, 200, exchange.getRequestURI().getPath().endsWith("transactions")
                    ? "{\"response\":{\"transactions\":[]}}" : account());
        });
        server.start();
        var sandbox = new NordeaBankAdapter(new ObjectMapper(), clock, "test-client", "FI1234-EUR",
                LocalDate.of(2026, 1, 1), directory.resolve("secret").toString(),
                directory.resolve("token").toString(), "", HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort());
        assertThat(sandbox.fetch().account().bookedBalance()).isEqualByComparingTo("1000.25");
        assertThat(signatures).containsExactly("SKIP_SIGNATURE_VALIDATION_FOR_SANDBOX",
                "SKIP_SIGNATURE_VALIDATION_FOR_SANDBOX");
    }

    @Test
    void configuredSigningKeyFailureDoesNotFallBackToSandboxMarker() throws Exception {
        Files.delete(directory.resolve("key"));
        assertThatThrownBy(() -> adapter().fetch()).isInstanceOf(BankAccessException.class)
                .hasMessageContaining("Cannot read Nordea credential files");
        assertThat(queries).isEmpty();
    }

    @Test
    void rejectsRepeatedContinuationKeys() {
        server.createContext("/", exchange -> respond(exchange, 200,
                exchange.getRequestURI().getPath().endsWith("transactions")
                        ? "{\"response\":{\"transactions\":[],\"continuation_key\":\"same\"}}" : account()));
        server.start();
        assertThatThrownBy(() -> adapter().fetch()).hasMessageContaining("repeated a continuation key");
    }

    @Test
    void rejectsMalformedAndWrongCurrencyResponsesInsteadOfEmptySuccess() {
        server.createContext("/", exchange -> respond(exchange, 200, account().replace("\"EUR\"", "\"SEK\"")));
        server.start();
        assertThatThrownBy(() -> adapter().fetch()).hasMessageContaining("different account, country or currency");
        assertThatThrownBy(() -> NordeaBankAdapter.response(new ObjectMapper().readTree("{}")))
                .hasMessageContaining("unexpected response envelope");
        assertThatThrownBy(() -> NordeaBankAdapter.money(new ObjectMapper().readTree("{\"amount\":\"0.001\"}"), "amount"))
                .isInstanceOf(ArithmeticException.class);
    }
}
