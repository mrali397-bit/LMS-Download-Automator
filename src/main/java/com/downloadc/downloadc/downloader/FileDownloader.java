package com.downloadc.downloadc.downloader;

import com.downloadc.downloadc.api.DownloadHistoryService;
import com.downloadc.downloadc.model.CourseFile;
import com.downloadc.downloadc.model.DownloadRecord;
import com.downloadc.downloadc.model.DownloadStatus;
import com.downloadc.downloadc.model.MoodleConfig;
import com.downloadc.downloadc.model.Downloadable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

// class to download files and save them locally
public class FileDownloader {

    private final HttpClient httpClient;
    private final MoodleConfig config;
    private final DownloadHistoryService historyService;

    private static final String DOWNLOAD_ROOT = "downloads";

    // constructor
    public FileDownloader(MoodleConfig config, DownloadHistoryService historyService) {
        this.config = config;
        this.historyService = historyService;

        try {
            // trust all SSL
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    }
            };

            SSLContext ssl = SSLContext.getInstance("SSL");
            ssl.init(null, trustAll, new SecureRandom());

            this.httpClient = HttpClient.newBuilder().sslContext(ssl).build();

        } catch (Exception e) {
            throw new RuntimeException("SSL setup failed : " + e.getMessage(), e);
        }
    }

    // download a file, returns DownloadStatus enum
    public DownloadStatus download(Downloadable courseFile) throws Exception {
        String safeCourse = sanitize(courseFile.getCourseName());
        String safeFile   = sanitize(courseFile.getFileName());

        Path courseFolder = Paths.get(DOWNLOAD_ROOT, safeCourse);
        Path destination  = courseFolder.resolve(safeFile);
        Path partFile     = courseFolder.resolve(safeFile + ".part");

        // check history
        Optional<DownloadRecord> prior = historyService.findRecord(
                safeFile, courseFile.getCourseName()
        );

        // skip if already exists
        if (Files.exists(destination)) {

            if (prior.isPresent()) {
                DownloadRecord rec = prior.get();

                long lmsTs   = courseFile.getMoodleTimestamp();
                long localTs = rec.getMoodleTimestamp();

                // if same then skip
                if (lmsTs > 0 && localTs > 0 && lmsTs <= localTs) {
                    System.out.println("SKIP: " + safeFile);
                    return DownloadStatus.SKIPPED;
                }

                // if size matches skip
                long localSize = Files.size(destination);
                if (lmsTs == 0 && courseFile.getFileSize() > 0
                        && localSize == courseFile.getFileSize()) {
                    System.out.println("SKIP size: " + safeFile);
                    return DownloadStatus.SKIPPED;
                }

                // update, delete old file and re-download
                System.out.println("UPDATE: " + safeFile);
                Files.delete(destination);

            } else {
                // no history
                if (courseFile.getFileSize() > 0
                        && Files.size(destination) == courseFile.getFileSize()) {
                    System.out.println("SKIP no history: " + safeFile);
                    return DownloadStatus.SKIPPED;
                }

                System.out.println("Re-download: " + safeFile);
                Files.delete(destination);
            }
        }

        // create folder
        Files.createDirectories(courseFolder);

        // build URL
        String url     = courseFile.getFileUrl();
        String authUrl = courseFile.getAuthenticatedUrl(config.getToken());

        // Resume check
        long resumeFrom = 0;
        if (Files.exists(partFile)) {
            resumeFrom = Files.size(partFile);
            System.out.println("Resuming " + safeFile);
        }

        // Build request
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(authUrl))
                .GET();

        if (resumeFrom > 0) {
            reqBuilder.header("Range", "bytes=" + resumeFrom + "-");
        }

        HttpResponse<InputStream> response = httpClient.send(
                reqBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        int status = response.statusCode();

        // Check status
        if (status != 200 && status != 206) {
            if (resumeFrom > 0 && status == 416) {
                Files.deleteIfExists(partFile);
                System.out.println("Restarting: " + safeFile);
                return download(courseFile);
            }
            throw new Exception("HTTP " + status);
        }

        long          bytesWritten   = resumeFrom;
        MessageDigest md5            = MessageDigest.getInstance("MD5");
        boolean       freshDownload  = (resumeFrom == 0);

        // Write file
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(
                     partFile,
                     resumeFrom > 0 ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {

            byte[] buffer = new byte[65536];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (freshDownload) md5.update(buffer, 0, read);
                bytesWritten += read;
            }
        }

        // Rename .part into final file
        Files.move(partFile, destination);

        String hashHex = freshDownload
                ? HexFormat.of().formatHex(md5.digest())
                : null;

        System.out.println("Saved: " + destination);

        // update history
        prior.ifPresent(r -> historyService.removeRecord(safeFile, courseFile.getCourseName()));

        DownloadRecord record = new DownloadRecord(
                safeFile,
                courseFile.getCourseName(),
                safeCourse,
                bytesWritten,
                destination.toString(),
                hashHex,
                courseFile.getMoodleTimestamp()
        );

        historyService.addRecord(record);

        // return result
        if (resumeFrom > 0)   return DownloadStatus.RESUMED;
        if (prior.isPresent()) return DownloadStatus.UPDATED;
        return DownloadStatus.DOWNLOADED;
    }

    // clean file name
    private String sanitize(String name) {
        return name.replaceAll("[\\/:*?\"<>|\\\\]", "_").trim();
    }
}