package com.harmoniasuite.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TranslationDocument {

    private int schemaVersion = 4;
    private ProjectMeta project = new ProjectMeta();
    private String sourceLocale = "en";
    private String targetLocale = "ru";
    private List<String> files = new ArrayList<>();
    private List<String> selectedTablesTranslate = new ArrayList<>();
    private List<String> selectedTablesExport = new ArrayList<>();
    private List<TranslationEntry> entries = new ArrayList<>();
    private PackMeta pack;
    private Map<String, Object> merge;

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public ProjectMeta getProject() {
        return project;
    }

    public void setProject(ProjectMeta project) {
        this.project = project;
    }

    public String getSourceLocale() {
        return sourceLocale;
    }

    public void setSourceLocale(String sourceLocale) {
        this.sourceLocale = sourceLocale;
    }

    public String getTargetLocale() {
        return targetLocale;
    }

    public void setTargetLocale(String targetLocale) {
        this.targetLocale = targetLocale;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }

    public List<String> getSelectedTablesTranslate() {
        return selectedTablesTranslate;
    }

    public void setSelectedTablesTranslate(List<String> selectedTablesTranslate) {
        this.selectedTablesTranslate = selectedTablesTranslate;
    }

    public List<String> getSelectedTablesExport() {
        return selectedTablesExport;
    }

    public void setSelectedTablesExport(List<String> selectedTablesExport) {
        this.selectedTablesExport = selectedTablesExport;
    }

    public List<TranslationEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<TranslationEntry> entries) {
        this.entries = entries;
    }

    public Map<String, Object> getMerge() {
        return merge;
    }

    public void setMerge(Map<String, Object> merge) {
        this.merge = merge;
    }

    public PackMeta getPack() {
        return pack;
    }

    public void setPack(PackMeta pack) {
        this.pack = pack;
    }

    public static TranslationDocument empty() {
        TranslationDocument document = new TranslationDocument();
        document.setProject(new ProjectMeta());
        return document;
    }
}
