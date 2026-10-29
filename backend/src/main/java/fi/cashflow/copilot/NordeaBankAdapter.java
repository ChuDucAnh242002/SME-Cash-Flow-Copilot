package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bank-mode", havingValue = "nordea")
public class NordeaBankAdapter implements BankPort {
    private static final String ORIGIN = "https://api.nordeaopenbanking.com";
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;
    private final String origin;
    private final String clientId, accountId, secretFile, tokenFile, keyFile;
    private final LocalDate fromDate;

    @org.springframework.beans.factory.annotation.Autowired
    public NordeaBankAdapter(ObjectMapper mapper, Clock clock,
            @Value("${app.nordea.client-id}") String clientId,
            @Value("${app.nordea.account-id}") String accountId,
            @Value("${app.nordea.from-date}") LocalDate fromDate,
            @Value("${app.nordea.client-secret-file}") String secretFile,
            @Value("${app.nordea.access-token-file}") String tokenFile,
            @Value("${app.nordea.private-key-file}") String keyFile) {
        this(mapper, clock, clientId, accountId, fromDate, secretFile, tokenFile, keyFile,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), ORIGIN);
    }

    NordeaBankAdapter(ObjectMapper mapper, Clock clock, String clientId, String accountId, LocalDate fromDate,
                      String secretFile, String tokenFile, String keyFile, HttpClient http, String origin) {
        this.mapper = mapper; this.clock = clock; this.clientId = clientId; this.accountId = accountId;
        this.fromDate = fromDate; this.secretFile = secretFile; this.tokenFile = tokenFile; this.keyFile = keyFile;
        this.http = http; this.origin = origin;
    }

    @Override
    public BankSnapshot fetch() {
        if (clientId.isBlank() || !accountId.matches("FI[0-9]+-EUR")) {
            throw new BankAccessException("Configure NORDEA_CLIENT_ID and a Finnish EUR NORDEA_ACCOUNT_ID from the sandbox.");
        }
        LocalDate today = LocalDate.now(clock);
        if (fromDate.isAfter(today)) throw new BankAccessException("NORDEA_FROM_DATE must not be in the future.");
        String path = "/corporate/premium/v4/accounts/" + accountId;
        try {
            String secret = credential(secretFile), token = credential(tokenFile);
            PrivateKey key = keyFile.isBlank() ? null : readKey(keyFile);
            JsonNode details = response(get(path, secret, token, key));
            if (!required(details, "_id").equals(accountId) || !required(details, "currency").equals("EUR")
                    || !required(details, "country").equals("FI")) {
                throw new BankAccessException("Nordea returned a different account, country or currency. No data was imported.");
            }
            Account account = new Account(accountId, required(details, "account_name"), "EUR",
                    money(details, "booked_balance"), clock.instant());
            List<BankTransaction> transactions = new ArrayList<>();
            String continuation = "";
            Set<String> seen = new HashSet<>();
            for (int page = 0; page < 1000; page++) {
                String query = "?from_date=" + fromDate + "&to_date=" + today
                        + (continuation.isEmpty() ? "" : "&continuation_key=" + encode(continuation));
                JsonNode body = response(get(path + "/transactions" + query, secret, token, key));
                JsonNode rows = body.get("transactions");
                String next = body.path("continuation_key").asText("");
                if (rows == null || rows.isNull()) {
                    if (next.isEmpty()) throw new BankAccessException("Nordea response is missing its transactions array.");
                } else {
                    if (!rows.isArray()) throw new BankAccessException("Nordea transactions must be an array.");
                    for (JsonNode row : rows) {
                        if (!required(row, "currency").equals("EUR")) {
                            throw new BankAccessException("A transaction has an unsupported currency. No data was imported.");
                        }
                        LocalDate bookingDate = LocalDate.parse(required(row, "booking_date"));
                        if (bookingDate.isAfter(today)) {
                            throw new BankAccessException("Nordea returned a future-booked transaction. Manual review is required.");
                        }
                        transactions.add(new BankTransaction(accountId, required(row, "transaction_id"),
                                bookingDate, money(row, "amount"), row.path("reference").asText(""),
                                row.path("counterparty_name").asText("Not supplied by bank"), null));
                    }
                }
                if (next.isEmpty()) return new BankSnapshot(account, List.copyOf(transactions));
                if (!seen.add(next)) throw new BankAccessException("Nordea repeated a continuation key. No partial data was imported.");
                continuation = next;
            }
            throw new BankAccessException("Nordea pagination exceeded 1000 pages. Narrow NORDEA_FROM_DATE.");
        } catch (IOException exception) {
            throw new BankAccessException("Cannot read Nordea credential files or contact the sandbox. Check local files and connectivity.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BankAccessException("Nordea sync was interrupted. Retry manually.", exception);
        } catch (GeneralSecurityException | IllegalArgumentException | DateTimeException | ArithmeticException exception) {
            throw new BankAccessException("Invalid Nordea signing key or response format. Expected a PKCS#8 RSA key and Instant Reporting v4 data.", exception);
        }
    }

    private JsonNode get(String path, String secret, String token, PrivateKey key)
            throws GeneralSecurityException, IOException, InterruptedException {
        URI uri = URI.create(origin + path);
        String date = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .withZone(ZoneOffset.UTC).format(clock.instant());
        // Nordea's developer console uses this marker for sandbox credentials only.
        String signature = key == null ? "SKIP_SIGNATURE_VALIDATION_FOR_SANDBOX" : signature(uri, date, clientId, key);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-IBM-Client-Id", clientId).header("X-IBM-Client-Secret", secret)
                .header("X-Nordea-Originating-Host", uri.getHost())
                .header("X-Nordea-Originating-Date", date).header("Signature", signature).GET().build();
        HttpResponse<String> reply = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (reply.statusCode() != 200) {
            throw new BankAccessException("Nordea sandbox returned HTTP " + reply.statusCode()
                    + ". Check subscription, token expiry, account access and signing key. No partial data was imported.");
        }
        return mapper.readTree(reply.body());
    }

    static String signature(URI uri, String date, String clientId, PrivateKey key) throws GeneralSecurityException {
        String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String input = "(request-target): get " + target + "\nx-nordea-originating-host: " + uri.getHost()
                + "\nx-nordea-originating-date: " + date;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(input.getBytes(StandardCharsets.UTF_8));
        return "keyId=\"" + clientId + "\",algorithm=\"rsa-sha256\",headers=\"(request-target) "
                + "x-nordea-originating-host x-nordea-originating-date\",signature=\""
                + Base64.getEncoder().encodeToString(signer.sign()) + "\"";
    }

    static JsonNode response(JsonNode envelope) {
        if (envelope == null || !envelope.path("response").isObject()) {
            throw new BankAccessException("Nordea returned an unexpected response envelope. No data was imported.");
        }
        JsonNode code = envelope.path("group_header").path("http_code");
        if (code.isInt() && code.asInt() != 200) {
            throw new BankAccessException("Nordea reported an unsuccessful operation inside its response.");
        }
        return envelope.get("response");
    }

    static String required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isValueNode() || value.isNull() || value.asText().isBlank()) {
            throw new BankAccessException("Nordea response is missing required field: " + name);
        }
        return value.asText();
    }

    static BigDecimal money(JsonNode node, String name) {
        BigDecimal value = new BigDecimal(required(node, name)).setScale(2, RoundingMode.UNNECESSARY);
        if (value.precision() > 19) throw new BankAccessException("Nordea monetary value exceeds supported precision.");
        return value;
    }

    private static String credential(String file) throws IOException {
        String value = Files.readString(Path.of(file)).strip();
        if (value.isEmpty()) throw new BankAccessException("A required Nordea credential file is empty.");
        return value;
    }

    private static PrivateKey readKey(String file) throws IOException, GeneralSecurityException {
        String pem = credential(file);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
