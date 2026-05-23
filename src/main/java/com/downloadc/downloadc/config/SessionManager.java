package com.downloadc.downloadc.config;

import com.downloadc.downloadc.model.MoodleConfig;
import org.springframework.stereotype.Component;


//Holds the active MoodleConfig after a successful login.

// @Component registers this as a Spring Bean
@Component
public class SessionManager {

    //Starts as null because no user is logged in when the app starts
    //Set by AuthController after successful login

    private MoodleConfig activeConfig = null;


    public void setActiveConfig(MoodleConfig config) {
        this.activeConfig = config;
    }

    //Returns the active config or null if no user has login

    public MoodleConfig getActiveConfig() {
        return activeConfig;
    }

    //Returns true if a user has logged in and their token is available
    public boolean isLoggedIn() {
        return activeConfig != null && activeConfig.getToken() != null;
    }
    // Discards the token and userId

    public void clearSession() {
        this.activeConfig = null;
    }
}