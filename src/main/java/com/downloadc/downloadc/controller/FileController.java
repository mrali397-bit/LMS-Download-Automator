package com.downloadc.downloadc.controller;
 
import com.downloadc.downloadc.api.DownloadHistoryService;
import com.downloadc.downloadc.api.FileService;
import com.downloadc.downloadc.api.GoogleDriveService;
import com.downloadc.downloadc.api.MoodleApiClient;
import com.downloadc.downloadc.config.SessionManager;
import com.downloadc.downloadc.downloader.FileDownloader;
import com.downloadc.downloadc.model.Course;
import com.downloadc.downloadc.model.CourseFile;
import com.downloadc.downloadc.model.DownloadStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
 
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

// Controller for course files and downloading
@RestController
@RequestMapping("/api")
public class FileController {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private DownloadHistoryService historyService;

        @Autowired
    private GoogleDriveService driveService;
    
    // Get all files for a course
    @GetMapping("/courses/{courseId}/files")
    public ResponseEntity<?> getFilesForCourse(@PathVariable int courseId) {

        // check login
        if (!sessionManager.isLoggedIn()) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Not logged in."));
        }

        try {
            MoodleApiClient apiClient = new MoodleApiClient(sessionManager.getActiveConfig());
            FileService fileService = new FileService(apiClient);

            // create course object
            Course course = new Course(courseId, "", String.valueOf(courseId), "");

            // return files
            return ResponseEntity.ok(fileService.getFilesForCourse(course));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // download all files of a course
    @PostMapping("/download/{courseId}")
    public ResponseEntity<?> downloadCourse(
            @PathVariable int courseId,
            @RequestBody Map<String, String> body,
            HttpSession session) {

        // check login
        if (!sessionManager.isLoggedIn())
            return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));

        String saveOption = body.getOrDefault("saveOption", "device").toLowerCase();
        boolean uploadToDrive = saveOption.equals("drive") || saveOption.equals("both");

        // Drive validation
        String sessionId = session.getId();
        if (uploadToDrive && !driveService.isAuthorized(sessionId)) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "Google Drive not connected. Click 'Connect Drive' in the Drive banner first."));
        }

        try {
            // setup api and downloader
            
            MoodleApiClient apiClient = new MoodleApiClient(sessionManager.getActiveConfig());
            FileService fileService = new FileService(apiClient);
            FileDownloader fileDownloader = new FileDownloader(sessionManager.getActiveConfig(), historyService);

            // get Course name
            String shortName = body.getOrDefault("shortName", String.valueOf(courseId));
            Course course = new Course(courseId, "", shortName, "");

            // Get all files
            List<CourseFile> files = fileService.getFilesForCourse(course);

            // Counters
            int downloaded = 0, updated = 0, resumed = 0, skipped = 0, failed = 0;
            int driveUploaded = 0, driveFailed = 0;

            // loop through files
            for (CourseFile file : files) {
                DownloadStatus result;

                try {
                    // call downloader and count result
                    result = fileDownloader.download(file);
                } catch (Exception e) {
                    failed++;
                    System.out.println("Failed : "+ file.getFileName() + " — " + e.getMessage());
                    continue;
                }

                // added: proper switch using result
                switch (result) {
                    case DOWNLOADED -> downloaded++;
                    case UPDATED -> updated++;
                    case RESUMED-> resumed++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                }

                if (uploadToDrive) {
                    try {
                        GoogleDriveService.UploadResult uploadResult = driveService.uploadCourse(sessionId, shortName);
                        driveUploaded = uploadResult.uploaded();
                        driveFailed   = uploadResult.failed();
                    } catch (Exception e) {
                        System.out.println("Drive upload failed: " + e.getMessage());
                        driveFailed = files.size();
                    }
                }
            }

            // added: extended response
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("courseId", courseId);
            response.put("totalFiles", files.size());
            response.put("downloaded", downloaded);
            response.put("updated", updated);
            response.put("resumed", resumed);
            response.put("skipped", skipped);
            response.put("failed", failed);
            response.put("saveOption", saveOption);

            if (uploadToDrive) {
                response.put("driveUploaded", driveUploaded);
                response.put("driveFailed", driveFailed);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("Error", e.getMessage()));
        }
    }

    // added: sanitize method
    private String sanitize(String name) {
        return name.replaceAll("[\\/:*?\"<>|\\\\]", "_").trim();
    }
}
