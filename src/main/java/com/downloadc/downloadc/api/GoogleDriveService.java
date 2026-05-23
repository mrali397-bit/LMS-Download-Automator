package com.downloadc.downloadc.api;

import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Handles Google Drive: OAuth URL generation, callback handling,
// uploading files per user using their own Google account
@Service
public class GoogleDriveService {

    private static final JsonFactory JSON_FACTORY   = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES        = Collections.singletonList(DriveScopes.DRIVE_FILE);
    private static final String APP_NAME            = "LMS Download Automator";
    private static final String ROOT_FOLDER_NAME    = "LMS Downloads";

    // ── Configurable via application.properties / environment variable ────────
    @Value("${app.redirect-uri:http://localhost:8080/api/drive/callback}")
    private String redirectUri;

    @Value("${google.drive.credentials.path:src/main/resources/credentials.json}")
    private String credentialsPath;

    // stores access tokens per session user (sessionId → credential)
    private final Map<String, GoogleCredential> userCredentials = new ConcurrentHashMap<>();

    // stores folder id caches per user
    private final Map<String, String>             rootFolderIds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> folderCaches = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    // true if credentials.json is present OR env variable is set
    public boolean isConfigured() {
        String credJson = System.getenv("GOOGLE_CREDENTIALS_JSON");
        if (credJson != null && !credJson.isEmpty()) return true;
        return new java.io.File(credentialsPath).exists();
    }

    // true if this session has a valid token
    public boolean isAuthorized(String sessionId) {
        return userCredentials.containsKey(sessionId);
    }

    // generates the Google OAuth URL the frontend should redirect the user to
    public String getAuthUrl(String sessionId) throws Exception {
        GoogleAuthorizationCodeFlow flow = buildFlow();
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(sessionId)          // we get sessionId back in callback
                .setAccessType("offline")
                .build();
    }

    // called by the callback endpoint after Google redirects back with a code
    public void handleCallback(String code, String sessionId) throws Exception {
        GoogleClientSecrets secrets   = loadSecrets();
        NetHttpTransport    transport = GoogleNetHttpTransport.newTrustedTransport();

        TokenResponse tokenResponse = new GoogleAuthorizationCodeTokenRequest(
                transport,
                JSON_FACTORY,
                secrets.getDetails().getClientId(),
                secrets.getDetails().getClientSecret(),
                code,
                redirectUri
        ).execute();

        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(transport)
                .setJsonFactory(JSON_FACTORY)
                .setClientSecrets(
                        secrets.getDetails().getClientId(),
                        secrets.getDetails().getClientSecret())
                .build()
                .setAccessToken(tokenResponse.getAccessToken())
                .setRefreshToken(tokenResponse.getRefreshToken());

        userCredentials.put(sessionId, credential);
        System.out.println("[GoogleDriveService] Token saved for session: " + sessionId);
    }

    // clears token for this session
    public void disconnect(String sessionId) {
        userCredentials.remove(sessionId);
        rootFolderIds.remove(sessionId);
        folderCaches.remove(sessionId);
        System.out.println("[GoogleDriveService] Disconnected session: " + sessionId);
    }

    // uploads all files from downloads/{courseName}/ to user's Drive
    public UploadResult uploadCourse(String sessionId, String courseName) throws Exception {

        Drive  drive          = buildDriveForSession(sessionId);
        String rootId         = getOrCreateRootFolder(drive, sessionId);
        String courseFolderId = getOrCreateCourseFolder(drive, sessionId, rootId, courseName);
        Set<String> existing  = listFileNamesInFolder(drive, courseFolderId);

        Path courseDir = Paths.get("downloads", sanitize(courseName));
        if (!Files.exists(courseDir))
            throw new IOException("No local downloads found for: " + courseName);

        int uploaded = 0, skipped = 0, failed = 0;

        try (var stream = Files.list(courseDir)) {
            for (Path filePath : stream.toList()) {
                if (!Files.isRegularFile(filePath)) continue;

                String fileName = filePath.getFileName().toString();

                if (existing.contains(fileName)) {
                    System.out.println("[Drive] Skipped (already in Drive): " + fileName);
                    skipped++;
                    continue;
                }

                try {
                    String mimeType = Files.probeContentType(filePath);
                    if (mimeType == null) mimeType = "application/octet-stream";

                    File fileMeta = new File();
                    fileMeta.setName(fileName);
                    fileMeta.setParents(Collections.singletonList(courseFolderId));

                    FileContent content = new FileContent(mimeType, filePath.toFile());
                    drive.files().create(fileMeta, content).setFields("id").execute();

                    System.out.println("[Drive] Uploaded: " + fileName);
                    uploaded++;

                } catch (Exception e) {
                    System.err.println("[Drive] Failed: " + fileName + " — " + e.getMessage());
                    failed++;
                }
            }
        }

        return new UploadResult(courseName, uploaded, skipped, failed);
    }

    // lists files in user's Drive course folder
    public List<DriveFileInfo> listCourseFiles(String sessionId, String courseName) throws Exception {
        Drive  drive          = buildDriveForSession(sessionId);
        String rootId         = getOrCreateRootFolder(drive, sessionId);
        String courseFolderId = getOrCreateCourseFolder(drive, sessionId, rootId, courseName);

        FileList result = drive.files().list()
                .setQ("'" + courseFolderId + "' in parents and trashed = false")
                .setFields("files(id, name, size, webViewLink, mimeType)")
                .execute();

        List<DriveFileInfo> files = new ArrayList<>();
        for (File f : result.getFiles()) {
            files.add(new DriveFileInfo(
                    f.getId(), f.getName(),
                    f.getSize() != null ? f.getSize() : 0L,
                    f.getWebViewLink(), f.getMimeType()
            ));
        }
        return files;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Drive buildDriveForSession(String sessionId) throws Exception {
        GoogleCredential credential = userCredentials.get(sessionId);
        if (credential == null)
            throw new IllegalStateException("Drive not connected. Please authorize first.");

        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        return new Drive.Builder(transport, JSON_FACTORY, credential)
                .setApplicationName(APP_NAME)
                .build();
    }

    private GoogleAuthorizationCodeFlow buildFlow() throws Exception {
        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        return new GoogleAuthorizationCodeFlow.Builder(
                transport, JSON_FACTORY, loadSecrets(), SCOPES)
                .setAccessType("offline")
                .build();
    }

    // Reads credentials from env variable (Koyeb) or file (local)
    private GoogleClientSecrets loadSecrets() throws Exception {
        String credJson = System.getenv("GOOGLE_CREDENTIALS_JSON");
        if (credJson != null && !credJson.isEmpty()) {
            return GoogleClientSecrets.load(JSON_FACTORY, new StringReader(credJson));
        }
        java.io.File credFile = new java.io.File(credentialsPath);
        if (!credFile.exists())
            throw new FileNotFoundException(
                    "credentials.json not found at: " + credentialsPath);
        return GoogleClientSecrets.load(JSON_FACTORY, new FileReader(credFile));
    }

    private String getOrCreateRootFolder(Drive drive, String sessionId) throws Exception {
        if (rootFolderIds.containsKey(sessionId))
            return rootFolderIds.get(sessionId);

        FileList existing = drive.files().list()
                .setQ("name = '" + ROOT_FOLDER_NAME + "' "
                        + "and mimeType = 'application/vnd.google-apps.folder' "
                        + "and trashed = false")
                .setFields("files(id)").execute();

        String id;
        if (!existing.getFiles().isEmpty()) {
            id = existing.getFiles().get(0).getId();
        } else {
            File meta = new File();
            meta.setName(ROOT_FOLDER_NAME);
            meta.setMimeType("application/vnd.google-apps.folder");
            id = drive.files().create(meta).setFields("id").execute().getId();
            System.out.println("[Drive] Created root folder: " + ROOT_FOLDER_NAME);
        }

        rootFolderIds.put(sessionId, id);
        return id;
    }

    private String getOrCreateCourseFolder(Drive drive, String sessionId,
                                           String rootId, String courseName) throws Exception {
        String safe  = sanitize(courseName);
        Map<String, String> cache = folderCaches.computeIfAbsent(sessionId, k -> new HashMap<>());
        if (cache.containsKey(safe)) return cache.get(safe);

        FileList existing = drive.files().list()
                .setQ("name = '" + safe + "' "
                        + "and '" + rootId + "' in parents "
                        + "and mimeType = 'application/vnd.google-apps.folder' "
                        + "and trashed = false")
                .setFields("files(id)").execute();

        String id;
        if (!existing.getFiles().isEmpty()) {
            id = existing.getFiles().get(0).getId();
        } else {
            File meta = new File();
            meta.setName(safe);
            meta.setMimeType("application/vnd.google-apps.folder");
            meta.setParents(Collections.singletonList(rootId));
            id = drive.files().create(meta).setFields("id").execute().getId();
            System.out.println("[Drive] Created course folder: " + safe);
        }

        cache.put(safe, id);
        return id;
    }

    private Set<String> listFileNamesInFolder(Drive drive, String folderId) throws Exception {
        FileList result = drive.files().list()
                .setQ("'" + folderId + "' in parents and trashed = false")
                .setFields("files(name)").execute();
        Set<String> names = new HashSet<>();
        for (File f : result.getFiles()) names.add(f.getName());
        return names;
    }

    private String sanitize(String name) {
        return name.replaceAll("[\\/:*?\"<>|\\\\]", "_").trim();
    }

    // Records
    public record UploadResult(String courseName, int uploaded, int skipped, int failed) {}
    public record DriveFileInfo(String id, String name, long size, String webViewLink, String mimeType) {}
}