package mchorse.bbs_together.network;

import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.utils.AssetPaths;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

/**
 * Optional read-only CDN transport for asset downloads.
 *
 * <p>The server manifest stays authoritative: a file is fetched from the configured CDN
 * base URL first, verified against the manifest SHA-1/size, and only then skipped from the
 * server request. Any failure (404, timeout, short read, hash mismatch) falls back to the
 * normal server transfer. Uploads never go to the CDN.</p>
 */
public final class CdnDownloader {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static volatile long disabledUntil = 0L;

    private CdnDownloader() {}

    public static boolean isConfigured() {
        return !baseUrl().isEmpty() && System.currentTimeMillis() >= disabledUntil;
    }

    public static boolean tryDownload(String relativePath, String sha1, long size, long mtime, Path assetsRoot, long maxBytes) {
        if (!isConfigured() || relativePath == null || sha1 == null || sha1.isEmpty() || size <= 0L
            || size > maxBytes || size > 512L * 1024L * 1024L) {
            return false;
        }

        Path target = AssetPaths.resolveSafe(assetsRoot.toFile(), relativePath);

        if (target == null || target.getParent() == null) {
            return false;
        }

        Path temp = target.resolveSibling(target.getFileName() + "." + sha1 + ".cdntmp");

        try {
            Files.createDirectories(target.getParent());

            HttpRequest.Builder builder = HttpRequest.newBuilder(buildUri(relativePath))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/octet-stream")
                .GET();
            String token = token();

            if (!token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<InputStream> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                response.body().close();
                return false;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            long total = 0L;

            try (InputStream in = response.body(); java.io.OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;

                while ((read = in.read(buffer)) >= 0) {
                    total += read;

                    if (total > size) {
                        throw new java.io.IOException("CDN object is larger than the manifest size");
                    }

                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }

            if (total != size) {
                return false;
            }

            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }

            if (!hex.toString().equalsIgnoreCase(sha1)) {
                return false;
            }

            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            if (mtime > 0L) {
                Files.setLastModifiedTime(target, FileTime.fromMillis(mtime));
            }

            return true;
        }
        catch (Exception e) {
            /* A dead CDN must not add 30 seconds per file to every join. Back off for a
             * minute after a transport-level failure; 404s stay per-file fallbacks. */
            if (!(e instanceof java.io.FileNotFoundException)) {
                disabledUntil = System.currentTimeMillis() + 60_000L;
            }

            return false;
        }
        finally {
            try {
                Files.deleteIfExists(temp);
            }
            catch (Exception ignored) {}
        }
    }

    private static URI buildUri(String relativePath) {
        StringBuilder url = new StringBuilder(baseUrl());

        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }

            if (url.charAt(url.length() - 1) != '/') {
                url.append('/');
            }

            url.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }

        return URI.create(url.toString());
    }

    private static String baseUrl() {
        try {
            String url = TogetherSettings.cdnUrl == null ? "" : (String) TogetherSettings.cdnUrl.get();
            return url == null ? "" : url.trim();
        }
        catch (Exception e) {
            return "";
        }
    }

    private static String token() {
        try {
            String token = TogetherSettings.cdnToken == null ? "" : (String) TogetherSettings.cdnToken.get();
            return token == null ? "" : token.trim();
        }
        catch (Exception e) {
            return "";
        }
    }
}
