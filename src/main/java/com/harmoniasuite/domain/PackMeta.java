package com.harmoniasuite.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PackMeta {
    private String packId;
    private String translationVersion;
    private String gameVersion;
    private List<String> compatibleGameVersions = new ArrayList<>();
    private String vendorId;
    private String vendorName;
    private String vendorUrl;
    private String vendorContact;
    private List<PackAuthor> authors = new ArrayList<>();
    private List<String> languages = new ArrayList<>();
    private String title;
    private String description;
    private String changelog;
    private String homepage;
    private String license;
    private String minPluginVersion;

    public String getPackId() {
        return packId;
    }

    public void setPackId(String packId) {
        this.packId = packId;
    }

    public String getTranslationVersion() {
        return translationVersion;
    }

    public void setTranslationVersion(String translationVersion) {
        this.translationVersion = translationVersion;
    }

    public String getGameVersion() {
        return gameVersion;
    }

    public void setGameVersion(String gameVersion) {
        this.gameVersion = gameVersion;
    }

    public List<String> getCompatibleGameVersions() {
        return compatibleGameVersions;
    }

    public void setCompatibleGameVersions(List<String> compatibleGameVersions) {
        this.compatibleGameVersions = compatibleGameVersions;
    }

    public String getVendorId() {
        return vendorId;
    }

    public void setVendorId(String vendorId) {
        this.vendorId = vendorId;
    }

    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public String getVendorUrl() {
        return vendorUrl;
    }

    public void setVendorUrl(String vendorUrl) {
        this.vendorUrl = vendorUrl;
    }

    public String getVendorContact() {
        return vendorContact;
    }

    public void setVendorContact(String vendorContact) {
        this.vendorContact = vendorContact;
    }

    public List<PackAuthor> getAuthors() {
        return authors;
    }

    public void setAuthors(List<PackAuthor> authors) {
        this.authors = authors;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public void setLanguages(List<String> languages) {
        this.languages = languages;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public String getHomepage() {
        return homepage;
    }

    public void setHomepage(String homepage) {
        this.homepage = homepage;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getMinPluginVersion() {
        return minPluginVersion;
    }

    public void setMinPluginVersion(String minPluginVersion) {
        this.minPluginVersion = minPluginVersion;
    }
}
