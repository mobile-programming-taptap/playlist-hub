package com.mptaptap.core;

import java.util.ArrayList;
import java.util.List;

public class Track {
    public String id = "";
    public String title = "";
    public String artist = "";
    public String language = "";
    public List<String> tags = new ArrayList<>();
    public String youtubeVideoId = "";
    public String source = "";
    public String sourceUrl = "";
    public String license = "";
    public String licenseReference = "";
    public String tagsSource = "";
    public String checkedAt = "";
    public boolean aiUseAllowed;

    public boolean usable() {
        return id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") && !title.trim().isEmpty() && !artist.trim().isEmpty()
                && "MusicBrainz".equals(source) && "CC0-1.0".equals(license) && aiUseAllowed
                && sourceUrl.equals("https://musicbrainz.org/recording/" + id)
                && (youtubeVideoId.isEmpty() || youtubeVideoId.matches("[A-Za-z0-9_-]{11}"));
    }
}
