package com.harmoniasuite.exception;

public enum UpdateErrorCode {
    SOURCES_NOT_FOUND("sources_not_found", "Не удалось найти исходники приложения"),
    WORKTREE_DIRTY("worktree_dirty", "Обновление отменено: в исходниках есть локальные изменения"),
    GIT_NOT_FOUND("git_not_found", "Не удалось найти Git"),
    GIT_DOWNLOAD_FAILED("git_download_failed", "Не удалось скачать Git для обновления"),
    GIT_RELEASE_NOT_FOUND("git_release_not_found", "В выпуске Git не найден подходящий архив"),
    JDK_NOT_FOUND("jdk_not_found", "Не удалось найти JDK 21 или новее"),
    JDK_DOWNLOAD_FAILED("jdk_download_failed", "Не удалось скачать JDK 21"),
    NODE_VERSION_NOT_FOUND("node_version_not_found", "Не удалось определить требуемую версию Node.js"),
    NODE_VERSION_INVALID("node_version_invalid", "Файл frontend/.node-version содержит неподдерживаемую версию Node.js"),
    NODE_NOT_FOUND("node_not_found", "Не удалось найти совместимый Node.js"),
    NODE_DOWNLOAD_FAILED("node_download_failed", "Не удалось скачать Node.js"),
    TOOLCHAIN_CORRUPT("toolchain_corrupt", "Загруженный компонент обновления повреждён"),
    TARGET_NOT_FOUND("target_not_found", "Не удалось определить целевую ветку обновления"),
    GIT_COMMAND_FAILED("git_command_failed", "Команда Git завершилась ошибкой"),
    LOCAL_VERSION_AHEAD("local_version_ahead", "Локальная версия новее origin/main"),
    HISTORY_DIVERGED("history_diverged", "История исходников расходится с origin/main"),
    PULL_FAILED("pull_failed", "Не удалось получить новую версию из Git"),
    BUILD_FAILED("build_failed", "Сборка новой версии завершилась ошибкой"),
    RELAUNCH_FAILED("relaunch_failed", "Не удалось запустить новую версию"),
    STATUS_CHECK_FAILED("status_check_failed", "Не удалось проверить наличие обновления"),
    ROLLBACK_FAILED("rollback_failed", "Не удалось вернуть предыдущую версию"),
    INTERRUPTED("interrupted", "Операция обновления прервана");

    private final String code;
    private final String userMessage;

    UpdateErrorCode(String code, String userMessage) {
        this.code = code;
        this.userMessage = userMessage;
    }

    public String code() {
        return code;
    }

    public String userMessage() {
        return userMessage;
    }
}
