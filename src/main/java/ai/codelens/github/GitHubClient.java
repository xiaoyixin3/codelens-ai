package ai.codelens.github;

import ai.codelens.config.RuntimeConfig;
import ai.codelens.contracts.Models;
import ai.codelens.workspace.RepositoryArchiveSource;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
@Profile({"worker", "publication-inspect"})
public class GitHubClient implements RepositoryArchiveSource {
    public static final String SUMMARY_MARKER = "<!-- codelens-ai:summary -->";
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:");
    private final String appId;
    private final PrivateKey privateKey;
    private final ObjectMapper json;
    private final HttpClient http;
    private final String baseUrl;
    private final Map<Long, CachedToken> tokens = new ConcurrentHashMap<>();
    private volatile CachedBot bot;

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubClient(RuntimeConfig config, ObjectMapper json) {
        this(config, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    GitHubClient(RuntimeConfig config, ObjectMapper json, HttpClient http) {
        if (config.githubAppId().isBlank() || config.githubPrivateKey().isBlank()) {
            throw new IllegalArgumentException("GITHUB_APP_ID and GITHUB_PRIVATE_KEY are required by the worker");
        }
        this.appId = config.githubAppId();
        this.privateKey = parsePrivateKey(config.githubPrivateKey());
        this.json = json;
        this.http = http;
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

    @Override
    public void download(long installationId, String owner, String repository, String commitSha,
                         Path destination, long maxBytes) {
        if (commitSha == null || !commitSha.matches("[0-9a-fA-F]{40}") || maxBytes <= 0) {
            throw new IllegalArgumentException("Archive download requires a full commit SHA and positive byte limit");
        }
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(temporary);
            URI current = URI.create(baseUrl + repoPath(owner, repository) + "/zipball/" + encode(commitSha));
            for (int redirect = 0; redirect <= 3; redirect++) {
                if (!allowedArchiveUri(current)) throw new IllegalStateException("GitHub archive redirect target is not allowed");
                HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + installationToken(installationId))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .GET().build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() >= 300 && response.statusCode() < 400) {
                        String location = response.headers().firstValue("location")
                                .orElseThrow(() -> new IllegalStateException("GitHub archive redirect omitted Location"));
                        current = current.resolve(location);
                        continue;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("GitHub archive download status " + response.statusCode());
                    }
                    long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
                    if (declared > maxBytes) throw new IllegalStateException("GitHub archive exceeds compressed size limit");
                    copyBounded(body, temporary, maxBytes);
                    moveAtomic(temporary, target);
                    return;
                }
            }
            throw new IllegalStateException("GitHub archive redirect limit exceeded");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub archive download interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("GitHub archive download failed", exception);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    static boolean allowedArchiveUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("api.github.com") || host.equals("codeload.github.com");
    }

    private static void copyBounded(InputStream input, Path destination, long maxBytes) throws IOException {
        long copied = 0;
        try (var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied += read;
                if (copied > maxBytes) throw new IllegalStateException("GitHub archive exceeds compressed size limit");
                output.write(buffer, 0, read);
            }
        }
    }

    private static void moveAtomic(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
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
        return currentRevision(installationId, owner, repo, number).headSha();
    }

    public Models.PullRequestRevision currentRevision(long installationId, String owner, String repo, int number) {
        JsonNode pull = request(installationId, "GET", repoPath(owner, repo) + "/pulls/" + number, null, JsonNode.class);
        return new Models.PullRequestRevision(pull.path("base").path("sha").asText(), pull.path("head").path("sha").asText());
    }

    public long startCheck(long installationId, String owner, String repo, String headSha, Long existing) {
        return startCheck(installationId, owner, repo, headSha, existing, null);
    }

    public long startCheck(long installationId, String owner, String repo, String headSha, Long existing, String externalId) {
        String root = repoPath(owner, repo);
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("status", "in_progress");
        body.put("started_at", Instant.now().toString());
        if (externalId != null) body.put("external_id", externalId);
        if (existing != null) {
            request(installationId, "PATCH", root + "/check-runs/" + existing, body, JsonNode.class);
            return existing;
        }
        body.put("name", "CodeLens AI Review");
        body.put("head_sha", headSha);
        JsonNode result = request(installationId, "POST", root + "/check-runs",
                body, JsonNode.class);
        return publicationId(result, "POST", root + "/check-runs");
    }

    public Optional<Long> findReviewCheck(Models.ReviewJob job, String externalId, Runnable beforeRead) {
        Long found = null;
        String root = repoPath(job.owner(), job.repo()) + "/commits/" + encode(job.headSha())
                + "/check-runs?filter=all&check_name=" + encode("CodeLens AI Review") + "&per_page=100&page=";
        for (int page = 1; page <= 20; page++) {
            beforeRead.run();
            JsonNode batch = request(job.installationId(), "GET", root + page, null, JsonNode.class).path("check_runs");
            if (!batch.isArray() || batch.size() > 100) throw checkConflict("Invalid check listing");
            for (JsonNode check : batch) {
                if (!externalId.equals(check.path("external_id").asText())) continue;
                // Same external ID on a foreign App is not evidence of our publication.
                if (!appId.equals(check.path("app").path("id").asText())) continue;
                validateCheck(check, job, externalId);
                long id = publicationId(check, "GET", root + page);
                if (found != null) throw checkConflict("Multiple matching Check Runs");
                found = id;
            }
            if (batch.size() < 100) return Optional.ofNullable(found);
        }
        throw checkConflict("Check listing pagination budget exhausted");
    }

    public JsonNode getReviewCheck(Models.ReviewJob job, long id, String externalId) {
        JsonNode check = request(job.installationId(), "GET", repoPath(job.owner(), job.repo()) + "/check-runs/" + id,
                null, JsonNode.class);
        validateCheck(check, job, externalId);
        if (check.path("id").asLong() != id) throw checkConflict("Check ID mismatch");
        return check;
    }

    private void validateCheck(JsonNode check, Models.ReviewJob job, String externalId) {
        if (!appId.equals(check.path("app").path("id").asText())
                || !job.headSha().equals(check.path("head_sha").asText())
                || !"CodeLens AI Review".equals(check.path("name").asText())
                || (externalId != null && !externalId.equals(check.path("external_id").asText()))) {
            throw checkConflict("Check ownership or revision mismatch");
        }
    }

    private static PublicationUncertainException checkConflict(String detail) {
        return new PublicationUncertainException("RECONCILE", "check-runs", new IllegalStateException(detail));
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

    public List<Models.Annotation> getReviewAnnotations(Models.ReviewJob job, long id, Runnable beforeRead) {
        beforeRead.run();
        JsonNode items = request(job.installationId(), "GET", repoPath(job.owner(), job.repo()) + "/check-runs/" + id
                + "/annotations?per_page=100&page=1", null, JsonNode.class);
        if (!items.isArray() || items.size() > 50) throw checkConflict("Unexpected annotation count");
        List<Models.Annotation> result = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.path("path").isTextual() || !item.path("start_line").isIntegralNumber()
                    || !item.path("end_line").isIntegralNumber() || !item.path("message").isTextual()
                    || item.hasNonNull("start_column") || item.hasNonNull("end_column")) throw checkConflict("Unsupported annotation shape");
            result.add(new Models.Annotation(item.path("path").asText(), item.path("start_line").asInt(),
                    item.path("end_line").asInt(), item.path("annotation_level").asText(), item.path("title").asText(""),
                    item.path("message").asText(), item.path("raw_details").asText("")));
        }
        return List.copyOf(result);
    }

    public long upsertSummaryComment(long installationId, String owner, String repo, int pullNumber, String body, Long existing) {
        Models.ReviewJob job = new Models.ReviewJob("legacy", installationId, owner, repo, pullNumber, "unknown", "unknown");
        Long target = existing == null ? findSummaryComment(job, null, () -> {}).orElse(null) : existing;
        return writeSummaryComment(job, SUMMARY_MARKER + "\n" + body, target, () -> {});
    }

    public Optional<Long> findSummaryComment(Models.ReviewJob job, String exactBody, Runnable beforeRead) {
        CachedBot author = botIdentity(job.installationId(), beforeRead);
        Long found = null;
        int ownedSummaries = 0;
        String root = repoPath(job.owner(), job.repo()) + "/issues/" + job.pullNumber() + "/comments?per_page=100&page=";
        for (int page = 1; page <= 20; page++) {
            beforeRead.run();
            JsonNode comments = request(job.installationId(), "GET", root + page, null, JsonNode.class);
            if (!comments.isArray() || comments.size() > 100) throw checkConflict("Invalid summary comment listing");
            for (JsonNode comment : comments) {
                if (!summaryMarker(comment) || !ownAuthor(comment, author)) continue;
                validateSummary(comment, job, author);
                if (++ownedSummaries > 1) throw checkConflict("Multiple owned summary comments on this PR");
                if (exactBody != null && !exactBody.equals(comment.path("body").asText())) continue;
                long id = publicationId(comment, "GET", root + page);
                if (found != null) throw checkConflict("Multiple matching summary comments");
                found = id;
            }
            if (comments.size() < 100) return Optional.ofNullable(found);
        }
        throw checkConflict("Summary comment pagination budget exhausted");
    }

    public JsonNode getSummaryComment(Models.ReviewJob job, long id, Runnable beforeRead) {
        CachedBot author = botIdentity(job.installationId(), beforeRead);
        beforeRead.run();
        JsonNode comment = request(job.installationId(), "GET", repoPath(job.owner(), job.repo()) + "/issues/comments/" + id,
                null, JsonNode.class);
        validateSummary(comment, job, author);
        if (publicationId(comment, "GET", "summary-comment") != id) throw checkConflict("Summary comment ID mismatch");
        return comment;
    }

    public long writeSummaryComment(Models.ReviewJob job, String fullBody, Long existing, Runnable beforeRead) {
        String root = repoPath(job.owner(), job.repo());
        if (existing != null) getSummaryComment(job, existing, beforeRead);
        beforeRead.run();
        String method = existing == null ? "POST" : "PATCH";
        String path = existing == null ? root + "/issues/" + job.pullNumber() + "/comments" : root + "/issues/comments/" + existing;
        JsonNode response = request(job.installationId(), method, path, Map.of("body", fullBody), JsonNode.class);
        long id = publicationId(response, method, path);
        if (existing != null && id != existing) throw checkConflict("Summary update returned another ID");
        return id;
    }

    private static boolean summaryMarker(JsonNode comment) {
        return comment.path("body").asText().startsWith(SUMMARY_MARKER + "\n");
    }

    private static boolean ownAuthor(JsonNode comment, CachedBot author) {
        JsonNode user = comment.path("user");
        return user.path("id").asLong(-1) == author.id() && "Bot".equals(user.path("type").asText())
                && author.login().equalsIgnoreCase(user.path("login").asText());
    }

    private void validateSummary(JsonNode comment, Models.ReviewJob job, CachedBot author) {
        String issue = baseUrl + repoPath(job.owner(), job.repo()) + "/issues/" + job.pullNumber();
        if (!summaryMarker(comment) || !ownAuthor(comment, author) || !issue.equalsIgnoreCase(comment.path("issue_url").asText())) {
            throw checkConflict("Summary author, marker or PR ownership mismatch");
        }
    }

    private CachedBot botIdentity(long installationId, Runnable beforeRead) {
        CachedBot cached = bot;
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached;
        try {
            beforeRead.run();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/app")).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + appJwt()).header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw checkConflict("Could not verify authenticated App");
            JsonNode app = json.readTree(response.body());
            String slug = app.path("slug").asText();
            if (!appId.equals(app.path("id").asText()) || !slug.matches("[A-Za-z0-9-]+")) throw checkConflict("Authenticated App identity mismatch");
            String login = slug + "[bot]";
            beforeRead.run();
            JsonNode user = request(installationId, "GET", "/users/" + encode(login), null, JsonNode.class);
            long id = publicationId(user, "GET", "app-bot");
            if (!login.equalsIgnoreCase(user.path("login").asText()) || !"Bot".equals(user.path("type").asText())) {
                throw checkConflict("App bot identity mismatch");
            }
            bot = new CachedBot(id, login, Instant.now().plusSeconds(600));
            return bot;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw checkConflict("App identity read interrupted");
        } catch (IOException exception) { throw checkConflict("App identity read failed"); }
        catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw checkConflict("Could not authenticate App identity"); }
    }

    private record CachedBot(long id, String login, Instant expiresAt) {}

    private static long publicationId(JsonNode response, String method, String path) {
        JsonNode id = response.path("id");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0) {
            throw new PublicationUncertainException(method, path, new IllegalStateException("Publication response omitted a valid remote ID"));
        }
        return id.asLong();
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
        boolean readOnly = method.equals("GET") || method.equals("HEAD");
        int attempts = readOnly ? 3 : 1;
        RuntimeException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            // Token acquisition cannot mutate a review; keep it outside the uncertain-send boundary.
            String token = installationToken(installationId);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(encoded));
            HttpResponse<byte[]> response;
            try {
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!readOnly) throw new PublicationUncertainException(method, path, exception);
                throw new IllegalStateException("GitHub request interrupted", exception);
            } catch (IOException | RuntimeException exception) {
                if (!readOnly) throw new PublicationUncertainException(method, path, exception);
                last = new IllegalStateException("GitHub read failed", exception);
                response = null;
            }
            if (response != null) {
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    try { return decoder.apply(response.body()); }
                    catch (RuntimeException exception) {
                        if (!readOnly) throw new PublicationUncertainException(method, path, exception);
                        throw exception;
                    }
                }
                // Do not log remote bodies: they may contain repository content or secrets.
                last = new IllegalStateException("GitHub " + method + " " + path + ": status " + status);
                if (!readOnly && status >= 500) throw new PublicationUncertainException(method, path, last);
                if (!readOnly || (status != 429 && status < 500)) throw last;
            }
            if (attempt + 1 == attempts) break;
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
