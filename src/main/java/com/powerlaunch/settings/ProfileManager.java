package com.powerlaunch.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ProfileManager {
    private static ProfileManager instance;
    private static final String INDEX_FILE = "profiles_index.json";

    private final Path profilesDir;
    private final Gson gson;
    private final List<String> profileList;
    private String currentProfile;

    private ProfileManager() {
        gson = new GsonBuilder().setPrettyPrinting().create();
        profilesDir = com.powerlaunch.launcher.LauncherHomeProvider.getProfilesDir();
        profileList = new ArrayList<>();
        currentProfile = "Default";
        loadIndex();
        ensureDefaultProfile();
    }

    public static synchronized ProfileManager getInstance() {
        if (instance == null) {
            instance = new ProfileManager();
        }
        return instance;
    }

    private void loadIndex() {
        try {
            Files.createDirectories(profilesDir);
            Path indexPath = profilesDir.resolve(INDEX_FILE);
            if (Files.exists(indexPath)) {
                String content = Files.readString(indexPath);
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> index = gson.fromJson(content, type);
                if (index != null) {
                    if (index.containsKey("current")) {
                        currentProfile = (String) index.get("current");
                    }
                    if (index.containsKey("profiles") && index.get("profiles") instanceof List) {
                        profileList.clear();
                        profileList.addAll((List<String>) index.get("profiles"));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load profiles index: " + e.getMessage());
        }
    }

    private void saveIndex() {
        try {
            Files.createDirectories(profilesDir);
            Map<String, Object> index = new HashMap<>();
            index.put("current", currentProfile);
            index.put("profiles", new ArrayList<>(profileList));
            Files.writeString(profilesDir.resolve(INDEX_FILE), gson.toJson(index));
        } catch (IOException e) {
            System.err.println("Failed to save profiles index: " + e.getMessage());
        }
    }

    private void ensureDefaultProfile() {
        if (profileList.isEmpty()) {
            profileList.add("Default");
            // Create default profile file with current settings
            Map<String, Object> defaultSettings = SettingsManager.getInstance().getAll();
            defaultSettings.put("profileName", "Default");
            saveProfileSettings("Default", defaultSettings);
            saveIndex();
        }
        // Make sure current profile exists in list
        if (!profileList.contains(currentProfile)) {
            currentProfile = profileList.get(0);
            saveIndex();
        }
    }

    public List<String> getProfileNames() {
        return new ArrayList<>(profileList);
    }

    public String getCurrentProfile() {
        return currentProfile;
    }

    public Path getProfilePath(String name) {
        String fileName = sanitizeFileName(name) + ".json";
        return profilesDir.resolve(fileName);
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public Map<String, Object> loadProfileSettings(String name) {
        Path path = getProfilePath(name);
        if (Files.exists(path)) {
            try {
                String content = Files.readString(path);
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> settings = gson.fromJson(content, type);
                if (settings != null) return settings;
            } catch (IOException e) {
                System.err.println("Failed to load profile '" + name + "': " + e.getMessage());
            }
        }
        return new HashMap<>();
    }

    public void saveProfileSettings(String name, Map<String, Object> settings) {
        try {
            Files.createDirectories(profilesDir);
            settings.put("profileName", name);
            Files.writeString(getProfilePath(name), gson.toJson(settings));
        } catch (IOException e) {
            System.err.println("Failed to save profile '" + name + "': " + e.getMessage());
        }
    }

    public boolean switchToProfile(String name) {
        if (!profileList.contains(name)) return false;

        // Save current profile settings first
        saveProfileSettings(currentProfile, SettingsManager.getInstance().getAll());

        // Load new profile settings into SettingsManager
        currentProfile = name;
        Map<String, Object> profileSettings = loadProfileSettings(name);
        if (!profileSettings.isEmpty()) {
            SettingsManager.getInstance().loadFromMap(profileSettings);
        } else {
            SettingsManager.getInstance().loadDefaults();
        }
        SettingsManager.getInstance().save();

        saveIndex();
        return true;
    }

    public String createProfile(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String cleanName = name.trim();

        // If name already exists, append number
        String finalName = cleanName;
        int counter = 1;
        while (profileList.contains(finalName)) {
            finalName = cleanName + " (" + counter + ")";
            counter++;
        }

        // Save current profile first
        saveProfileSettings(currentProfile, SettingsManager.getInstance().getAll());

        // Create new profile with default settings
        Map<String, Object> defaultSettings = new HashMap<>();
        defaultSettings.put("profileName", finalName);
        defaultSettings.put("gameDirectory", "");
        defaultSettings.put("javaArgs", "");
        defaultSettings.put("selectedVersion", "");
        defaultSettings.put("javaPath", "");
        defaultSettings.put("javaChoice", "auto");
        defaultSettings.put("gpuChoice", "auto");
        defaultSettings.put("autoConnect", false);
        defaultSettings.put("connectServerIp", "");
        defaultSettings.put("useCustomResolution", false);
        defaultSettings.put("gameWidth", 854);
        defaultSettings.put("gameHeight", 480);

        profileList.add(finalName);
        saveProfileSettings(finalName, defaultSettings);

        // Switch to new profile
        currentProfile = finalName;
        SettingsManager.getInstance().loadFromMap(defaultSettings);
        SettingsManager.getInstance().save();
        saveIndex();

        return finalName;
    }

    public boolean deleteProfile(String name) {
        if (profileList.size() <= 1) return false; // Can't delete the last profile
        if (!profileList.contains(name)) return false;

        // Delete the file
        try {
            Files.deleteIfExists(getProfilePath(name));
        } catch (IOException e) {
            System.err.println("Failed to delete profile file: " + e.getMessage());
        }

        profileList.remove(name);

        // If deleting current, switch to first available
        if (currentProfile.equals(name)) {
            String nextProfile = profileList.get(0);
            switchToProfile(nextProfile);
        }

        saveIndex();
        return true;
    }

    public boolean renameProfile(String oldName, String newName) {
        if (oldName == null || newName == null || newName.trim().isEmpty()) return false;
        if (!profileList.contains(oldName)) return false;
        String cleanName = newName.trim();
        if (profileList.contains(cleanName)) return false;

        // Rename file
        Path oldPath = getProfilePath(oldName);
        Path newPath = getProfilePath(cleanName);
        try {
            Files.createDirectories(profilesDir);
            Files.move(oldPath, newPath);
        } catch (IOException e) {
            System.err.println("Failed to rename profile: " + e.getMessage());
            return false;
        }

        int idx = profileList.indexOf(oldName);
        profileList.set(idx, cleanName);

        if (currentProfile.equals(oldName)) {
            currentProfile = cleanName;
        }

        saveIndex();
        return true;
    }

    public String importProfile(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) return null;
        try {
            String content = Files.readString(filePath);
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> settings = gson.fromJson(content, type);
            if (settings == null) return null;

            // Get name from file or settings
            String name = (String) settings.getOrDefault("profileName", "");
            if (name.isEmpty()) {
                name = filePath.getFileName().toString().replace(".json", "");
            }

            // Make unique name if needed
            String finalName = name;
            int counter = 1;
            while (profileList.contains(finalName)) {
                finalName = name + " (" + counter + ")";
                counter++;
            }

            settings.put("profileName", finalName);
            profileList.add(finalName);
            saveProfileSettings(finalName, settings);
            saveIndex();

            return finalName;
        } catch (IOException e) {
            System.err.println("Failed to import profile: " + e.getMessage());
            return null;
        }
    }

    public Path getProfilesDirectory() {
        return profilesDir;
    }
}
