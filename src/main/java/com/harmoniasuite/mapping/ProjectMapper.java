package com.harmoniasuite.mapping;

import com.harmoniasuite.dto.FileStatsDto;
import com.harmoniasuite.dto.OverviewDto;
import com.harmoniasuite.dto.ProjectListDto;
import com.harmoniasuite.dto.SummaryDto;
import com.harmoniasuite.repository.ProjectRepository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProjectMapper {

    @Mapping(source = "row.id", target = "id")
    @Mapping(source = "row.name", target = "name")
    @Mapping(source = "row.updatedAt", target = "updatedAt")
    @Mapping(source = "summary.files", target = "files")
    @Mapping(source = "summary.entries", target = "entries")
    @Mapping(source = "summary.translated", target = "translated")
    @Mapping(target = "exists", constant = "true")
    @Mapping(target = "error", ignore = true)
    ProjectListDto toListDto(ProjectRepository.ProjectRow row, ProjectRepository.ProjectSummary summary);

    @Mapping(source = "summary.entries", target = "entries")
    @Mapping(source = "summary.files", target = "files")
    @Mapping(source = "summary.translated", target = "translated")
    @Mapping(source = "summary.byStatus", target = "byStatus")
    SummaryDto toSummaryDto(
            ProjectRepository.ProjectSummary summary, long untranslated, String outputDir);

    @Mapping(source = "row.id", target = "id")
    @Mapping(source = "row.name", target = "name")
    @Mapping(source = "row.sourceLocale", target = "sourceLocale")
    @Mapping(source = "row.targetLocale", target = "targetLocale")
    @Mapping(source = "row.createdAt", target = "createdAt")
    @Mapping(source = "row.updatedAt", target = "updatedAt")
    @Mapping(source = "inputRoot", target = "inputRoot")
    @Mapping(source = "outputDir", target = "outputDir")
    OverviewDto toOverviewDto(ProjectRepository.ProjectRow row, SummaryDto summary,
            String inputRoot, String outputDir);

    @Mapping(source = "row.id", target = "id")
    @Mapping(source = "row.name", target = "name")
    @Mapping(source = "row.updatedAt", target = "updatedAt")
    @Mapping(source = "error", target = "error")
    @Mapping(target = "files", constant = "0L")
    @Mapping(target = "entries", constant = "0L")
    @Mapping(target = "translated", constant = "0L")
    @Mapping(target = "exists", constant = "true")
    ProjectListDto toListDto(ProjectRepository.ProjectRow row, String error);

    @Mapping(source = "done", target = "translated")
    @Mapping(target = "untranslated", expression = "java(file.total() - file.done())")
    FileStatsDto toFileStatsDto(ProjectRepository.FileWithStats file);
}
