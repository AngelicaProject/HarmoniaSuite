package com.harmoniasuite.source.artifact;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/** Resolves server-generated staging and artifact paths without accepting client path fragments. */
public final class SourceArtifactPath {

    private static final Pattern SNAPSHOT_ID = Pattern.compile("sha256:[0-9a-f]{64}");

    private final Path stagingRoot;
    private final Path artifactRoot;

    public SourceArtifactPath(WorkspacePaths workspace, HarmoniaProperties properties) {
        this.stagingRoot = requireRoot(workspace.resolve(properties.getSourceIngestion().getStagingPath()));
        this.artifactRoot = requireRoot(workspace.resolve(properties.getSourceIngestion().getArtifactPath()));
    }

    public Path stagingRoot() {
        return stagingRoot;
    }

    public Path artifactRoot() {
        return artifactRoot;
    }

    public Path uploadDirectory(String uploadId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uploadId);
        } catch (Exception exception) {
            throw new IllegalArgumentException("upload session ID is invalid", exception);
        }
        Path directory = stagingRoot.resolve(uuid.toString()).normalize();
        requireChild(stagingRoot, directory);
        rejectSymlink(directory, "upload staging directory");
        return directory;
    }

    public Path payloadPath(String uploadId) {
        return child(uploadDirectory(uploadId), "payload.part");
    }

    public Path snapshotPath(String uploadId) {
        return child(uploadDirectory(uploadId), "snapshot.hxs");
    }

    public Path snapshotTempPath(String uploadId) {
        return child(uploadDirectory(uploadId), "snapshot.hxs.tmp");
    }

    public Path chunkTempPath(String uploadId) {
        return child(uploadDirectory(uploadId), "chunk.part");
    }

    public Path artifactPath(String trustedSnapshotId) {
        String hex = snapshotHex(trustedSnapshotId);
        Path path = artifactRoot.resolve("sha256").resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4)).resolve(hex + ".hxs.zst").normalize();
        requireChild(artifactRoot, path);
        return path;
    }

    public String storageKey(String trustedSnapshotId) {
        String hex = snapshotHex(trustedSnapshotId);
        return "sha256/" + hex.substring(0, 2) + "/" + hex.substring(2, 4)
                + "/" + hex + ".hxs.zst";
    }

    public void createStagingDirectory(String uploadId) throws IOException {
        Files.createDirectories(stagingRoot);
        rejectSymlinkComponents(stagingRoot, stagingRoot);
        Path directory = uploadDirectory(uploadId);
        Files.createDirectory(directory);
        rejectSymlink(directory, "upload staging directory");
    }

    public void createArtifactParent(Path artifactPath) throws IOException {
        rejectSymlinkComponents(artifactRoot, artifactPath.getParent());
        Files.createDirectories(artifactPath.getParent());
        rejectSymlinkComponents(artifactRoot, artifactPath.getParent());
    }

    public static void rejectSymlink(Path path, String description) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException(description + " must not be a symbolic link");
        }
    }

    public static void requireRegularFile(Path path, String description) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IllegalStateException(description + " is not a managed regular file");
        }
    }

    public static void rejectSymlinkComponents(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        requireChild(normalizedRoot, normalizedPath);
        Path current = normalizedRoot;
        rejectSymlink(current, "managed path component");
        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(part);
            rejectSymlink(current, "managed path component");
        }
    }

    private static Path requireRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getNameCount() == 0) {
            throw new IllegalArgumentException("managed storage root is too broad");
        }
        rejectSymlink(normalized, "managed storage root");
        return normalized;
    }

    private static Path child(Path parent, String name) {
        Path child = parent.resolve(name).normalize();
        requireChild(parent, child);
        return child;
    }

    private static void requireChild(Path parent, Path child) {
        if (!child.startsWith(parent)) {
            throw new IllegalArgumentException("managed path escapes configured root");
        }
    }

    private static String snapshotHex(String snapshotId) {
        if (snapshotId == null || !SNAPSHOT_ID.matcher(snapshotId).matches()) {
            throw new IllegalArgumentException("trusted snapshot ID is invalid");
        }
        return snapshotId.substring("sha256:".length());
    }
}
