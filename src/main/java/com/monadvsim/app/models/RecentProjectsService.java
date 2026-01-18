package com.monadvsim.app.models;

import java.util.prefs.Preferences;
import java.util.*;
import java.io.File;

public class RecentProjectsService {
    private static final int MAX_RECENT = 5;
    private final Preferences prefs = Preferences.userNodeForPackage(RecentProjectsService.class);

    public void addProject(File file) {
        if (file == null) return;
        
        List<String> recent = getRecentProjects();
        String path = file.getAbsolutePath();
        
        recent.remove(path); 
        recent.add(0, path); 
        
        if (recent.size() > MAX_RECENT) {
            recent = recent.subList(0, MAX_RECENT);
        }
        
        prefs.put("recent_list", String.join("|", recent));
    }

    public List<String> getRecentProjects() {
        String list = prefs.get("recent_list", "");
        if (list.isEmpty()) return new ArrayList<>();
        
        // Convert to a mutable list and filter out non-existent files
        List<String> paths = new ArrayList<>(Arrays.asList(list.split("\\|")));
        paths.removeIf(p -> !new File(p).exists());
        
        return paths;
    }
    
    public void clearHistory() {
        prefs.remove("recent_list");
    }
}
