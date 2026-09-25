package ai.codelens.github;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
@Profile("worker")
public class GitHubClient {
    public static final String SUMMARY_MARKER = "<!-- codelens-ai:summary -->";
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:");
    private final String appId;
    private final PrivateKey privateKey;
    private final ObjectMapper json;
    private final HttpClient http;
    private final String baseUrl;
    private final Map<Long, CachedToken> tokens = new ConcurrentHashMap<>();

    public GitHubClient(RuntimeConfig config, ObjectMapper json) {
        if (config.githubAppId().isBlank() || config.githubPrivateKey().isBlank()) {
            throw new IllegalArgumentException("GITHUB_APP_ID and GITHUB_PRIVATE_KEY are required by the worker");
        }
        this.appId = config.githubAppId();
        this.privateKey = parsePrivateKey(config.githubPrivateKey());
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.baseUrl = "https://api.github.com";
    }

    public String getFileContent(long installationId, String owner, String repo, String path, String ref) {
        String safe = repositoryFilePath(path);
        JsonNode payload = request(installationId, "GET", repoPath(owner, repo) + "/contents/" + safe + "?ref=" + encode(ref), null, JsonNode.class);
        if (!"file".equals(payload.path("type").asText()) || !"base64".equals(payload.path("encoding").asText())) {
            throw new IllegalStateException("GitHub contents response for " + path + " is not a base64 file");
        }
        return new String(Base64.getDecoder().decode(payload.path("content").asText().replace("\n", "")), StandardCharsets.UTF_8);
    }

    public Models.PullRequest getPullRequest(long installationId, String owner, String repo, int number) {
        String root = repoPath(owner, repo);
        JsonNode pull = request(installationId, "GET", root + "/pulls/" + number, null, JsonNode.class);
        List<Models.ChangedFile> files = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<Models.ChangedFile> batch = request(installationId, "GET",
                    root + "/pulls/" + number + "/files?per_page=100&page=" + page, null,
                    new TypeReference<List<Models.ChangedFile>>() {});
            files.addAll(batch);
            if (batch.size() < 100) break;
        }
        return new Models.PullRequest(pull.path("number").asInt(), pull.path("title").asText(""),
                pull.path("body").asText(""), pull.path("base").path("sha").asText(),
                pull.path("head").path("sha").asText(), files);
    }

    public String currentHead(long installationId, String owner, String repo, int number) {
        JsonNode pull = request(installationId, "GET", repoPath(owner, repo) + "/pulls/" + number, null, JsonNode.class);
        return pull.path("head").path("sha").asText();
    }

    public long startCheck(long installationId, String owner, String repo, String headSha, Long existing) {
        String root = repoPath(owner, repo);
        if (existing != null) {
            request(installationId, "PATCH", root + "/check-runs/" + existing,
                    Map.of("status", "in_progress", "started_at", Instant.now().toString()), JsonNode.class);
            return existing;
        }
        JsonNode result = request(installationId, "POST", root + "/check-runs",
                Map.of("name", "CodeLens AI Review", "head_sha", headSha, "status", "in_progress", "started_at", Instant.now().toString()), JsonNode.class);
        return result.path("id").asLong();
    }

    public void completeCheck(long installationId, String owner, String repo, long checkId,
                              String conclusion, String title, String summary, List<Models.Annotation> annotations) {
        List<Models.Annotation> bounded = annotations.size() > 50 ? annotations.subList(0, 50) : annotations;
        var output = new java.util.LinkedHashMap<String, Object>();
        output.put("title", title);
        output.put("summary", summary);
        if (!bounded.isEmpty()) output.put("annotations", bounded);
        request(installationId, "PATCH", repoPath(owner, repo) + "/check-runs/" + checkId,
                Map.of("status", "completed", "conclusion", conclusion, "completed_at", Instant.now().toString(), "output", output), JsonNode.class);
    }

    public long upsertSummaryComment(long installationId, String owner, String repo, int pullNumber, String body, Long existing) {
        String root = repoPath(owner, repo);
        String fullBody = SUMMARY_MARKER + "\n" + body;
        Long commentId = existing;
        if (commentId == null) {
            List<JsonNode> comments = request(installationId, "GET", root + "/issues/" + pullNumber + "/comments?per_page=100", null,
                    new TypeReference<List<JsonNode>>() {});
            commentId = comments.stream().filter(item -> item.path("body").asText().contains(SUMMARY_MARKER))
                    .map(item -> item.path("id").asLong()).findFirst().orElse(null);
        }
        if (commentId != null) {
            request(installationId, "PATCH", root + "/issues/comments/" + commentId, Map.of("body", fullBody), JsonNode.class);
            return commentId;
        }
        JsonNode created = request(installationId, "POST", root + "/issues/" + pullNumber + "/comments", Map.of("body", fullBody), JsonNode.class);
        return created.path("id").asLong();
    }

    public static String repositoryFilePath(String value) {
        if (value == null || value.isBlank() || value.contains("\\") || value.indexOf('\0') >= 0 || WINDOWS_DRIVE.matcher(value).find()) {
            throw new IllegalArgumentException("unsafe repository path");
        }
        String clean = value.startsWith("./") ? value.substring(2) : value;
        if (clean.isBlank() || clean.startsWith("/") || clean.equals("..") || clean.contains("../")) {
            throw new IllegalArgumentException("unsafe repository path");
        }
        return String.join("/", java.util.Arrays.stream(clean.split("/")).map(GitHubClient::encode).toList());
    }

    private <T> T request(long installationId, String method, String path, Object body, Class<T> type) {
        return requestBytes(installationId, method, path, body, bytes -> {
            if (bytes.length == 0 && type == JsonNode.class) return type.cast(json.createObjectNode());
            try { return json.readValue(bytes, type); }
            catch (Exception exception) { throw new IllegalStateException("Invalid GitHub response", exception); }
        });
    }

    private <T> T request(long installationId, String method, String path, Object body, TypeReference<T> type) {
        return requestBytes(installationId, method, path, body, bytes -> {
            try { return json.readValue(bytes, type); }
            catch (Exception exception) { throw new IllegalStateException("Invalid GitHub response", exception); }
        });
    }

    private <T> T requestBytes(long installationId, String method, String path, Object body, java.util.function.Function<byte[], T> decoder) {
        byte[] encoded;
        try { encoded = body == null ? new byte[0] : json.writeValueAsBytes(body); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + installationToken(installationId))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28");
                if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
                else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(encoded));
                HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return decoder.apply(response.body());
                last = new IllegalStateException("GitHub " + method + " " + path + ": status " + response.statusCode() + ": "
                        + new String(response.body(), StandardCharsets.UTF_8));
                if (response.statusCode() != 429 && response.statusCode() < 500) throw last;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GitHub request interrupted", exception);
            } catch (Exception exception) {
                last = exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
            }
            try { Thread.sleep((1L << attempt) * 500); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        }
        throw Optional.ofNullable(last).orElseGet(() -> new IllegalStateException("GitHub request retry exhausted"));
    }

    private String installationToken(long installationId) {
        CachedToken cached = tokens.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(120))) return cached.value();
        try {
            String jwt = appJwt();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/app/installations/" + installationId + "/access_tokens"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + jwt)
                    .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("GitHub installation token status " + response.statusCode());
            JsonNode payload = json.readTree(response.body());
            CachedToken token = new CachedToken(payload.path("token").asText(), Instant.parse(payload.path("expires_at").asText()));
            tokens.put(installationId, token);
            return token.value();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (Exception exception) { throw new IllegalStateException("Could not obtain GitHub installation token", exception); }
    }

    private String appJwt() throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64Url(("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));
        String content = header + "." + claims;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(content.getBytes(StandardCharsets.US_ASCII));
        return content + "." + base64Url(signature.sign());
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String normalized = pem.replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
                    .replaceAll("-----END (RSA )?PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(normalized);
            try { return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded)); }
            catch (Exception pkcs8Failure) {
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1(decoded)));
            }
        } catch (Exception exception) { throw new IllegalArgumentException("GITHUB_PRIVATE_KEY is not valid RSA PEM", exception); }
    }

    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = new byte[]{0x30,0x0d,0x06,0x09,0x2a,(byte)0x86,0x48,(byte)0x86,(byte)0xf7,0x0d,0x01,0x01,0x01,0x05,0x00};
        byte[] privateKey = der((byte)0x04, pkcs1);
        return der((byte)0x30, concat(new byte[]{0x02,0x01,0x00}, algorithm, privateKey));
    }
    private static byte[] der(byte tag, byte[] value) { return concat(new byte[]{tag}, derLength(value.length), value); }
    private static byte[] derLength(int length) {
        if (length < 128) return new byte[]{(byte) length};
        if (length < 256) return new byte[]{(byte)0x81,(byte)length};
        return new byte[]{(byte)0x82,(byte)(length >> 8),(byte)length};
    }
    private static byte[] concat(byte[]... arrays) {
        int length = java.util.Arrays.stream(arrays).mapToInt(a -> a.length).sum();
        byte[] result = new byte[length]; int offset = 0;
        for (byte[] array : arrays) { System.arraycopy(array, 0, result, offset, array.length); offset += array.length; }
        return result;
    }
    private static String base64Url(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String repoPath(String owner, String repo) { return "/repos/" + encode(owner) + "/" + encode(repo); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private record CachedToken(String value, Instant expiresAt) {}
}
