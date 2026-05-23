package com.downloadc.downloadc.api;

import com.downloadc.downloadc.model.Course;
import com.downloadc.downloadc.model.MoodleConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// CourseService fetches the user's enrolled courses from Moodle.
// Returns a list
public class CourseService {

    private final MoodleApiClient apiClient;
    private final MoodleConfig config;
    private final FavoritesService favoritesService; // nullable

    // Constructor without favorites
    public CourseService(MoodleApiClient apiClient, MoodleConfig config) {
        this.apiClient        = apiClient;
        this.config           = config;
        this.favoritesService = null; // ✅ explicitly set to null
    }

    // Constructor with favorites
    public CourseService(MoodleApiClient apiClient, MoodleConfig config,
                         FavoritesService favoritesService) {
        this.apiClient        = apiClient;
        this.config           = config;
        this.favoritesService = favoritesService;
    }

    // Methods
    public List<Course> getEnrolledCourses() {
        List<Course> courses = new ArrayList<>();
        System.out.println("Getting enrolled courses...");

        try {
            // Parameters for api
            String params = "userid=" + config.getUserId() + "&returnusercount=0";

            // API call
            JsonNode response = apiClient.callFunction("core_enrol_get_users_courses", params);

            // check response
            if (response == null || !response.isArray()) {
                System.err.println("bad api response");
                return courses;
            }

            // get favourite ids
            Set<Integer> favoriteIds = (favoritesService != null)
                    ? favoritesService.getFavoriteIds()
                    : Set.of();

            for (JsonNode node : response) {

                int    id        = node.path("id").asInt();
                String fullName  = node.path("fullname").asText("Unknown Course");
                String shortName = node.path("shortname").asText("");
                String summary   = node.path("summary").asText("");

                // get teacher name
                String instructor = "";
                JsonNode contacts = node.path("contacts");
                if (contacts.isArray() && contacts.size() > 0) {
                    instructor = contacts.get(0).path("fullname").asText("");
                }

                // last access time
                long lastAccess = node.path("lastaccess").asLong(0L);

                // count new files
                int newFiles = countNewFiles(node, shortName);

                // check fav
                boolean isFav = favoriteIds.contains(id);

                // add course
                courses.add(new Course(id, fullName, shortName, summary,
                        instructor, lastAccess, newFiles, isFav));
            }

            System.out.println("Loaded " + courses.size() + " courses");

        } catch (Exception e) {
            System.err.println("error " + e.getMessage());
        }

        return courses;
    }

    // Count files not yet downloaded
    private int countNewFiles(JsonNode courseNode, String shortName) {
        try {
            JsonNode overviewFiles = courseNode.path("overviewfiles");
            if (!overviewFiles.isArray() || overviewFiles.size() == 0) return 0;

            // clean name
            String safeShort = shortName.replaceAll("[\\/:*?\"<>|\\\\]", "_").trim();

            int newCount = 0;

            for (JsonNode f : overviewFiles) {
                String fileName = f.path("filename").asText("");
                if (fileName.isBlank()) continue;

                // clean file name
                String safeFile = fileName.replaceAll("[\\/:*?\"<>|\\\\]", "_").trim();

                // check if exists
                if (!Files.exists(Paths.get("downloads", safeShort, safeFile))) {
                    newCount++;
                }
            }

            return newCount;

        } catch (Exception e) {
            return 0; // ignore errors
        }
    }
}