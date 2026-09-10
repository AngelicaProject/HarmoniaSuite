package com.harmoniasuite.service.export;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.dto.ExportFileDto;
import com.harmoniasuite.dto.ExportListDto;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.PackRepository;
import com.harmoniasuite.repository.ProjectRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class ExportService {

    private final WorkspacePaths workspace;
    private final ProjectRepository projectRepository;
    private final PackRepository packStore;
    private final PackManifestService packs;

    public ExportService(WorkspacePaths workspace, ProjectRepository projectRepository,
            PackRepository packStore, PackManifestService packs) {
        this.workspace = workspace;
        this.projectRepository = projectRepository;
        this.packStore = packStore;
        this.packs = packs;
    }

    public record CsvDownload(Resource resource, String filename, long length) {
    }

    public record ZipDownload(byte[] bytes, String filename) {
    }

    public record ManifestDownload(byte[] bytes) {
    }

    public ExportListDto listing(UUID projectId) throws IOException {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        Path outRoot = workspace.resolve(row.outputDir());
        List<ExportFileDto> files = new ArrayList<>();
        if (Files.isDirectory(outRoot)) {
            try (var walk = Files.walk(outRoot)) {
                List<Path> csvs = walk.filter(p -> Files.isRegularFile(p)
                        && p.toString().toLowerCase().endsWith(".csv")).toList();
                for (Path source : csvs) {
                    String rel = outRoot.relativize(source).toString().replace('\\', '/');
                    files.add(new ExportFileDto(rel, Files.size(source)));
                }
            }
            files.sort(Comparator.comparing(ExportFileDto::name));
        }
        boolean manifest = Files.isRegularFile(outRoot.resolve(PackManifestService.MANIFEST_FILE_NAME));
        return new ExportListDto(files, manifest);
    }

    public CsvDownload csvFile(UUID projectId, String file) throws IOException {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        String normalized = file.replace('\\', '/').trim();
        if (normalized.contains("..") || normalized.contains("/") || !normalized.endsWith(".csv")) {
            throw new HarmoniaSuiteBadRequestException("invalid file");
        }
        Path outRoot = workspace.resolve(row.outputDir());
        Path target = outRoot.resolve(normalized).normalize();
        if (!target.startsWith(outRoot) || !Files.isRegularFile(target)) {
            throw new NoSuchFileException(target.toString());
        }
        return new CsvDownload(new FileSystemResource(target), normalized, Files.size(target));
    }

    public ZipDownload zip(UUID projectId) throws IOException {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        Map<String, Object> manifest = validatedManifest(projectId, row.name());
        byte[] manifestJson = packs.toJsonBytes(manifest);
        Path outRoot = workspace.resolve(row.outputDir());
        if (!Files.isDirectory(outRoot)) {
            throw new NoSuchFileException(outRoot.toString());
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(PackManifestService.MANIFEST_FILE_NAME));
            zip.write(manifestJson);
            zip.closeEntry();
            List<Path> files;
            try (var walk = Files.walk(outRoot)) {
                files = walk.filter(p -> Files.isRegularFile(p)
                        && p.toString().toLowerCase().endsWith(".csv")).toList();
            }
            for (Path source : files) {
                String rel = outRoot.relativize(source).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(rel));
                Files.copy(source, zip);
                zip.closeEntry();
            }
        }
        String zipName = outRoot.getFileName() + "-" + row.name() + ".zip";
        return new ZipDownload(buffer.toByteArray(), zipName);
    }

    public ManifestDownload manifest(UUID projectId) throws IOException {
        ProjectRepository.ProjectRow row = projectRepository.findById(projectId);
        return new ManifestDownload(packs.toJsonBytes(validatedManifest(projectId, row.name())));
    }

    private Map<String, Object> validatedManifest(UUID projectId, String projectName) {
        PackMeta pack = packs.effectivePack(packStore.load(projectId), projectName);
        List<String> errors = packs.validate(pack);
        if (!errors.isEmpty()) {
            throw new HarmoniaSuiteBadRequestException(
                    "Пак не настроен (вкладка «Пак»): " + String.join("; ", errors));
        }
        return packs.build(pack);
    }
}
