package com.harmoniasuite.service.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.config.AppVersion;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.InstallLayout;
import com.harmoniasuite.dto.UpdateState;
import com.harmoniasuite.dto.UpdateStatusDto;
import com.harmoniasuite.exception.UpdateErrorCode;
import com.harmoniasuite.exception.UpdateException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UpdateService {

    private static final String GIT_REMOTE = "origin";
    private static final String GIT_BRANCH = "main";
    private static final int COMMIT_SUBJECT_LIMIT = 10;
    private static final Logger logger = LoggerFactory.getLogger(UpdateService.class);

    private enum UpdatePhase {
        PULL("получение исходников", UpdateErrorCode.PULL_FAILED),
        BUILD("сборка", UpdateErrorCode.BUILD_FAILED);

        private final String description;
        private final UpdateErrorCode errorCode;

        UpdatePhase(String description, UpdateErrorCode errorCode) {
            this.description = description;
            this.errorCode = errorCode;
        }
    }

    private record UpdatePlan(Path root, String gitBinary, String javaHome,
            String currentSha, String latestSha, int behindBy, int aheadBy) {
    }

    private final UpdateCoordinator coordinator = new UpdateCoordinator();
    private final HarmoniaProperties properties;
    private final ObjectMapper objectMapper;

    public UpdateService(HarmoniaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public UpdateStatusDto status() {
        return coordinator.read(this::statusLocked);
    }

    private UpdateStatusDto statusLocked() {
        String version = AppVersion.resolve(properties.getApp().getVersion(), "dev");
        String gitBinary = ToolchainProvider.gitBinary();
        Path root = resolveRepositoryRoot(gitBinary);
        if (root == null) {
            return failureStatus(version, null, UpdateState.UNAVAILABLE,
                    new UpdateException(UpdateErrorCode.SOURCES_NOT_FOUND));
        }
        if (gitBinary == null || ToolchainProvider.javaHome() == null) {
            String launchMode = UpdateRelaunch.detectLaunchMode(System.getProperty("java.class.path", ""));
            return new UpdateStatusDto(version, true, launchMode, true,
                    null, null, null, null, false, UpdateState.TOOLCHAIN_REQUIRED,
                    UpdateState.TOOLCHAIN_REQUIRED.message());
        }
        return checkForUpdates(version, root, gitBinary);
    }

    public void runUpdate(Consumer<String> log) {
        coordinator.run(() -> runUpdateLocked(log));
    }

    private void runUpdateLocked(Consumer<String> log) {
        // Without sources there is nothing to update, so no Git download either.
        if (ToolchainProvider.gitBinary() == null && findRepositoryRootInFileSystem() == null) {
            throw new UpdateException(UpdateErrorCode.SOURCES_NOT_FOUND);
        }
        String gitBinary = ToolchainProvider.requireGit(log, objectMapper);
        Path root = resolveRepositoryRoot(gitBinary);
        if (root == null) {
            throw new UpdateException(UpdateErrorCode.SOURCES_NOT_FOUND);
        }
        applyUpdate(root, log, gitBinary, ToolchainProvider.requireJavaHome(log));
    }

    static UpdateState classifyHistory(int behindBy, int aheadBy) {
        if (behindBy > 0 && aheadBy == 0) {
            return UpdateState.UPDATE_AVAILABLE;
        }
        if (behindBy == 0 && aheadBy > 0) {
            return UpdateState.LOCAL_AHEAD;
        }
        if (behindBy > 0) {
            return UpdateState.DIVERGED;
        }
        return UpdateState.UP_TO_DATE;
    }

    static String shortenSha(String sha) {
        if (sha == null) {
            return "";
        }
        return sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }

    public static String resolveCommitSha(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("@")) {
            return "";
        }
        return raw.strip();
    }

    static String scrub(String value) {
        return UpdateProcess.scrub(value);
    }

    @SafeVarargs
    static <T> T firstNonNull(Supplier<T>... candidates) {
        for (Supplier<T> candidate : candidates) {
            T value = candidate.get();
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String formatFailure(List<String> command, int code, List<String> lines) {
        return UpdateProcess.formatFailure(command, code, lines);
    }

    static String formatFailure(String command, int code, List<String> lines) {
        return UpdateProcess.formatFailure(command, code, lines);
    }

    private UpdateStatusDto checkForUpdates(String version, Path root, String gitBinary) {
        String launchMode = UpdateRelaunch.detectLaunchMode(System.getProperty("java.class.path", ""));
        if (gitBinary == null) {
            return new UpdateStatusDto(version, true, launchMode, true,
                    null, null, null, null, false, UpdateState.TOOLCHAIN_REQUIRED,
                    UpdateState.TOOLCHAIN_REQUIRED.message());
        }
        try {
            String currentSha = captureOutput(root, gitBinary, "rev-parse", "HEAD");
            runCommand(root, List.of(gitBinary, "fetch", "--quiet", GIT_REMOTE, GIT_BRANCH));
            String latestSha = captureOutput(root, gitBinary, "rev-parse", "FETCH_HEAD");
            if (latestSha.isBlank()) {
                throw new UpdateException(UpdateErrorCode.TARGET_NOT_FOUND);
            }
            int behindBy = latestSha.equals(currentSha)
                    ? 0 : countCommits(root, gitBinary, "HEAD..FETCH_HEAD");
            int aheadBy = latestSha.equals(currentSha)
                    ? 0 : countCommits(root, gitBinary, "FETCH_HEAD..HEAD");
            UpdateState state = classifyHistory(behindBy, aheadBy);
            List<String> commitSubjects = behindBy > 0 ? commitSubjects(root, gitBinary) : List.of();
            return new UpdateStatusDto(version, true, launchMode, null,
                    currentSha, latestSha, behindBy, commitSubjects,
                    state == UpdateState.UPDATE_AVAILABLE, state, state.message());
        } catch (UpdateException | IOException | NumberFormatException e) {
            UpdateException failure = e instanceof UpdateException updateException
                    ? updateException
                    : new UpdateException(UpdateErrorCode.STATUS_CHECK_FAILED, UpdateProcess.diagnosticDetail(e), e);
            return failureStatus(version, launchMode, UpdateState.CHECK_FAILED, failure);
        }
    }

    private static int countCommits(Path root, String gitBinary, String revisionRange) throws IOException {
        return Integer.parseInt(captureOutput(root, gitBinary, "rev-list", "--count", revisionRange));
    }

    private static UpdateStatusDto failureStatus(String version, String launchMode,
            UpdateState state, UpdateException failure) {
        if (state == UpdateState.CHECK_FAILED) {
            logger.warn("Обновления недоступны [{}]: {}", failure.code(),
                    scrub(failure.diagnosticMessage()));
        } else {
            logger.debug("Обновления недоступны [{}]: {}", failure.code(),
                    scrub(failure.diagnosticMessage()));
        }
        return new UpdateStatusDto(version, false, launchMode, null,
                null, null, null, null, null, state, failure.getMessage());
    }

    static List<String> mavenBuildCommand(Path root) {
        Path mvn = root.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        return List.of(mvn.toString(), "-B", "-DskipTests", "clean", "package");
    }

    private void applyUpdate(Path root, Consumer<String> log, String gitBinary, String javaHome) {
        try {
            if (!captureOutput(root, log, gitBinary, "status", "--porcelain").isBlank()) {
                throw new UpdateException(UpdateErrorCode.WORKTREE_DIRTY);
            }
            UpdatePlan plan = resolveUpdatePlan(root, log, gitBinary, javaHome);
            if (plan.currentSha().isBlank()) {
                throw new UpdateException(UpdateErrorCode.GIT_COMMAND_FAILED);
            }
            if (plan.latestSha().isBlank()) {
                throw new UpdateException(UpdateErrorCode.TARGET_NOT_FOUND);
            }
            if (plan.aheadBy() > 0) {
                throw new UpdateException(plan.behindBy() > 0
                        ? UpdateErrorCode.HISTORY_DIVERGED : UpdateErrorCode.LOCAL_VERSION_AHEAD);
            }
            if (plan.latestSha().equals(plan.currentSha())) {
                log.accept("Уже актуально");
                return;
            }
            log.accept("Получение версии " + shortenSha(plan.latestSha()));
            pullAndBuild(plan, log);
            relaunch(plan, log);
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.GIT_COMMAND_FAILED, UpdateProcess.diagnosticDetail(e), e);
        }
    }

    private UpdatePlan resolveUpdatePlan(Path root, Consumer<String> log, String gitBinary, String javaHome) {
        try {
            String currentSha = captureOutput(root, log, gitBinary, "rev-parse", "HEAD");
            runCommand(root, List.of(gitBinary, "fetch", "--quiet", GIT_REMOTE, GIT_BRANCH), log);
            String latestSha = captureOutput(root, log, gitBinary, "rev-parse", "FETCH_HEAD");
            if (latestSha.isBlank()) {
                return new UpdatePlan(root, gitBinary, javaHome, currentSha, latestSha, 0, 0);
            }
            int behindBy = latestSha.equals(currentSha) ? 0 : countCommits(root, gitBinary, "HEAD..FETCH_HEAD");
            int aheadBy = latestSha.equals(currentSha) ? 0 : countCommits(root, gitBinary, "FETCH_HEAD..HEAD");
            return new UpdatePlan(root, gitBinary, javaHome, currentSha, latestSha, behindBy, aheadBy);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.GIT_COMMAND_FAILED, UpdateProcess.diagnosticDetail(e), e);
        }
    }

    private void pullAndBuild(UpdatePlan plan, Consumer<String> log) {
        UpdatePhase phase = UpdatePhase.PULL;
        List<String> phaseOutput = new ArrayList<>();
        try {
            List<String> pullCommand = List.of(plan.gitBinary(), "pull", "--ff-only",
                    GIT_REMOTE, GIT_BRANCH);
            int code = runProcess(plan.root(), pullCommand, line -> {
                phaseOutput.add(line);
                log.accept(line);
            });
            if (code != 0) {
                throw new UpdateException(phase.errorCode, formatFailure(pullCommand, code, phaseOutput));
            }
            log.accept("Сборка");
            phase = UpdatePhase.BUILD;
            phaseOutput.clear();
            List<String> mavenCommand = mavenBuildCommand(plan.root());
            String nodeHome = ToolchainProvider.requireNodeHome(plan.root(), log);
            code = runProcess(plan.root(), mavenCommand, line -> {
                phaseOutput.add(line);
                log.accept(line);
            }, Map.of(
                    "JAVA_HOME", plan.javaHome(),
                    "PATH", ToolchainProvider.prependToPath(nodeHome, System.getenv("PATH"))));
            if (code != 0) {
                throw new UpdateException(phase.errorCode, formatFailure(mavenCommand, code, phaseOutput));
            }
        } catch (UpdateException e) {
            logPhaseFailure(phase, phaseOutput, e);
            rollback(plan.root(), log, plan.gitBinary(), plan.currentSha(), e);
            throw e;
        } catch (IOException e) {
            UpdateException failure = new UpdateException(phase.errorCode, UpdateProcess.diagnosticDetail(e), e);
            logPhaseFailure(phase, phaseOutput, failure);
            rollback(plan.root(), log, plan.gitBinary(), plan.currentSha(), failure);
            throw failure;
        }
    }

    private static void logPhaseFailure(UpdatePhase phase, List<String> output,
            UpdateException failure) {
        String fullOutput = String.join("\n", output);
        if (fullOutput.isBlank()) {
            logger.error("Обновление: фаза {} завершилась ошибкой [{}]: {}",
                    phase.description, failure.code(), failure.diagnosticMessage());
        } else {
            logger.error("Обновление: фаза {} завершилась ошибкой [{}]; полный вывод:\n{}",
                    phase.description, failure.code(), fullOutput);
        }
    }

    private void relaunch(UpdatePlan plan, Consumer<String> log) {
        if (!"jar".equals(UpdateRelaunch.detectLaunchMode(System.getProperty("java.class.path", "")))) {
            log.accept("Готово. Перезапусти приложение из среды разработки");
            return;
        }
        try {
            UpdateRelaunch.relaunch(plan.root(), log);
        } catch (Exception e) {
            UpdateException failure = new UpdateException(UpdateErrorCode.RELAUNCH_FAILED,
                    UpdateProcess.diagnosticDetail(e), e);
            log.accept(failure.getMessage());
            logger.error("Перезапуск обновления не удался [{}]: {}",
                    failure.code(), failure.diagnosticMessage());
            rollback(plan.root(), log, plan.gitBinary(), plan.currentSha(), failure);
            throw failure;
        }
        Runtime.getRuntime().halt(0);
    }

    private void rollback(Path root, Consumer<String> log, String gitBinary, String currentSha,
            UpdateException original) {
        List<String> command = List.of(gitBinary, "reset", "--hard", currentSha);
        try {
            log.accept("Откат на " + shortenSha(currentSha));
            List<String> output = new ArrayList<>();
            int code = runProcess(root, command, line -> {
                output.add(line);
                log.accept(line);
            });
            if (code != 0) {
                throw new IOException(formatFailure(command, code, output));
            }
        } catch (Exception e) {
            UpdateException failure = new UpdateException(UpdateErrorCode.ROLLBACK_FAILED,
                    UpdateProcess.diagnosticDetail(e), e);
            log.accept(failure.getMessage());
            logger.error("Откат обновления не удался [{}]: {}",
                    failure.code(), failure.diagnosticMessage());
            original.addSuppressed(failure);
        }
    }

    private static boolean isWindows() {
        return UpdateRelaunch.isWindows();
    }

    private static Path resolveRepositoryRoot(String gitBinary) {
        if (gitBinary != null) {
            return firstNonNull(() -> findRepositoryRootWithGit(gitBinary), UpdateService::findRepositoryRootInFileSystem);
        }
        return firstNonNull(UpdateService::findRepositoryRootInFileSystem);
    }

    private static Path findRepositoryRootWithGit(String gitBinary) {
        for (Path base : repositoryRootCandidates()) {
            try {
                String top = captureOutput(base, gitBinary, "rev-parse", "--show-toplevel");
                Path path = Paths.get(top);
                if (Files.isDirectory(path) && isSuiteCheckout(path)) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Path findRepositoryRootInFileSystem() {
        for (Path base : repositoryRootCandidates()) {
            Path dir = base.toAbsolutePath().normalize();
            for (int i = 0; i < 8 && dir != null; i++) {
                if (isSuiteCheckout(dir)) {
                    return dir;
                }
                dir = dir.getParent();
            }
        }
        return null;
    }

    private static List<Path> repositoryRootCandidates() {
        List<Path> bases = new ArrayList<>();
        Path app = InstallLayout.appDir();
        if (app != null) {
            bases.add(app.resolve("src"));
            bases.add(app);
        }
        bases.add(Paths.get(System.getProperty("user.dir")));
        return bases;
    }

    private static boolean isSuiteCheckout(Path directory) {
        try {
            Path pom = directory.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                return false;
            }
            return Files.readString(pom, StandardCharsets.UTF_8)
                    .contains("<artifactId>harmonia-suite</artifactId>");
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> commitSubjects(Path root, String gitBinary) throws IOException {
        List<String> lines = new ArrayList<>();
        List<String> command = List.of(gitBinary, "log", "--format=%s", "-n", String.valueOf(COMMIT_SUBJECT_LIMIT),
                "HEAD..FETCH_HEAD");
        int code = runProcess(root, command, lines::add);
        if (code != 0) {
            throw new IOException(formatFailure(command, code, lines));
        }
        return lines.stream().filter(l -> !l.isBlank()).toList();
    }

    private static String captureOutput(Path dir, String... command) throws IOException {
        return captureOutput(dir, line -> {
        }, command);
    }

    private static String captureOutput(Path dir, Consumer<String> log, String... command) throws IOException {
        return UpdateProcess.captureOutput(dir, log, command);
    }

    private static void runCommand(Path dir, List<String> command) throws IOException {
        runCommand(dir, command, line -> {
        });
    }

    private static void runCommand(Path dir, List<String> command, Consumer<String> log) throws IOException {
        UpdateProcess.runOrThrow(dir, command, log);
    }

    private static int runProcess(Path dir, List<String> command, Consumer<String> log) throws IOException {
        return runProcess(dir, command, log, Map.of());
    }

    private static int runProcess(Path dir, List<String> command, Consumer<String> log, Map<String, String> env)
            throws IOException {
        return UpdateProcess.runProcess(dir, command, log, env).exitCode();
    }
}
