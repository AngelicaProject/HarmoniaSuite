package com.harmoniasuite.service;

import com.harmoniasuite.config.InstallLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

final class UpdateRelaunch {

    static final String BUILT_JAR = "harmonia-suite.jar";

    private UpdateRelaunch() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static String launchMode(String classPath) {
        String normalized = classPath == null ? "" : classPath.replace('\\', '/');
        return normalized.contains("target/classes") ? "dev" : "jar";
    }

    static List<String> relaunchCommand(Path root) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String javaBin = info.command().orElse(defaultJavaBin());
        List<String> args = new ArrayList<>(info.arguments().map(List::of).orElse(List.of()));
        return relaunchCommand(root, javaBin, args, bundledRuntimeJava());
    }

    static Path builtJar(Path root) {
        return root.resolve("target").resolve(BUILT_JAR);
    }

    static List<String> relaunchCommand(Path root, String javaBin, List<String> args, Path runtimeJava) {
        Path builtJar = builtJar(root);
        int jarFlag = args.indexOf("-jar");
        if (jarFlag >= 0) {
            List<String> command = new ArrayList<>();
            command.add(javaBin);
            command.addAll(args.subList(0, jarFlag));
            command.add("-jar");
            command.add(builtJar.toString());
            if (jarFlag + 2 <= args.size()) {
                command.addAll(args.subList(jarFlag + 2, args.size()));
            }
            return command;
        }
        if (runtimeJava != null) {
            List<String> command = new ArrayList<>();
            command.add(runtimeJava.toString());
            command.add("-jar");
            command.add(builtJar.toString());
            command.addAll(args);
            return command;
        }
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.addAll(args);
        return command;
    }

    private static String defaultJavaBin() {
        return Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
    }

    static Path exeInstallPath() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            return null;
        }
        try {
            Path exe = Paths.get(appPath);
            return Files.isRegularFile(exe) ? exe : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Path writeRelaunchVbs(long pid, Path built, Path appJar, Path exe) throws IOException {
        String log = Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("harmonia-relaunch.log").toString();
        String nl = "\r\n";
        String body = "On Error Resume Next" + nl
                + "Set fso = CreateObject(\"Scripting.FileSystemObject\")" + nl
                + "Set lg = fso.OpenTextFile(" + vbsLiteral(log) + ", 8, True)" + nl
                + "lg.WriteLine Now & \" wait " + pid + "\"" + nl
                + "Set wmi = GetObject(\"winmgmts:\\\\.\\root\\cimv2\")" + nl
                + "For i = 1 To 120" + nl
                + "  If wmi.ExecQuery(\"SELECT ProcessId FROM Win32_Process WHERE ProcessId="
                + pid + "\").Count = 0 Then Exit For" + nl
                + "  WScript.Sleep 1000" + nl
                + "Next" + nl
                + "lg.WriteLine Now & \" copy\"" + nl
                + "fso.CopyFile " + vbsLiteral(built.toString()) + ", "
                + vbsLiteral(appJar.toString()) + ", True" + nl
                + "For i = 1 To 10" + nl
                + "  If Err.Number = 0 Then Exit For" + nl
                + "  Err.Clear" + nl
                + "  WScript.Sleep 1000" + nl
                + "  fso.CopyFile " + vbsLiteral(built.toString()) + ", "
                + vbsLiteral(appJar.toString()) + ", True" + nl
                + "Next" + nl
                + "If Err.Number = 0 Then" + nl
                + "  lg.WriteLine Now & \" start\"" + nl
                + "  Set sh = CreateObject(\"WScript.Shell\")" + nl
                + "  sh.Environment(\"PROCESS\")(\"HARMONIA_NO_BROWSER\") = \"1\"" + nl
                + "  sh.Run " + vbsLiteral(exe.toString()) + ", 1, False" + nl
                + "Else" + nl
                + "  lg.WriteLine Now & \" copy failed\"" + nl
                + "End If" + nl
                + "lg.Close" + nl
                + "fso.DeleteFile WScript.ScriptFullName" + nl;
        return writeUtf16Script(body);
    }

    private static Path writeUtf16Script(String body) throws IOException {
        Path script = Files.createTempFile("harmonia-update-", ".vbs");
        try {
            Files.write(script, ((char) 0xFEFF + body).getBytes(StandardCharsets.UTF_16LE));
            return script;
        } catch (IOException e) {
            Files.deleteIfExists(script);
            throw e;
        }
    }

    private static String vbsLiteral(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static void wdetach(Path script) throws IOException {
        Process process = new ProcessBuilder("wscript", "//Nologo", "//B", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .start();
        process.toHandle().onExit();
    }

    private static Path bundledRuntimeJava() {
        Path app = InstallLayout.appDir();
        if (app == null || app.getParent() == null) {
            return null;
        }
        Path java = app.getParent().resolve("runtime").resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java");
        return Files.isRegularFile(java) ? java : null;
    }

    static Path writeRelaunch(List<String> command) throws IOException {
        long pid = ProcessHandle.current().pid();
        if (isWindows()) {
            return writeWindowsRelaunchVbs(pid, command);
        }
        Path script = Files.createTempFile("harmonia-update-", ".sh");
        StringBuilder body = new StringBuilder();
        body.append("#!/bin/sh\n");
        body.append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n");
        body.append("cd ").append(shellLiteral(System.getProperty("user.dir"))).append("\n");
        body.append("rm -- \"$0\"\n");
        body.append("exec");
        for (String arg : command) {
            body.append(' ').append(shellLiteral(arg));
        }
        body.append('\n');
        try {
            Files.writeString(script, body.toString(), StandardCharsets.UTF_8);
            return script;
        } catch (IOException e) {
            Files.deleteIfExists(script);
            throw e;
        }
    }

    private static Path writeWindowsRelaunchVbs(long pid, List<String> command) throws IOException {
        String nl = "\r\n";
        String commandLine = command.stream()
                .map(UpdateRelaunch::windowsLiteral)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        String body = "On Error Resume Next" + nl
                + "Set wmi = GetObject(\"winmgmts:\\\\.\\root\\cimv2\")" + nl
                + "For i = 1 To 120" + nl
                + "  If wmi.ExecQuery(\"SELECT ProcessId FROM Win32_Process WHERE ProcessId="
                + pid + "\").Count = 0 Then Exit For" + nl
                + "  WScript.Sleep 1000" + nl
                + "Next" + nl
                + "Set sh = CreateObject(\"WScript.Shell\")" + nl
                + "sh.CurrentDirectory = " + vbsLiteral(System.getProperty("user.dir")) + nl
                + "sh.Run " + vbsLiteral(commandLine) + ", 1, False" + nl
                + "Set fso = CreateObject(\"Scripting.FileSystemObject\")" + nl
                + "fso.DeleteFile WScript.ScriptFullName" + nl;
        return writeUtf16Script(body);
    }

    private static String shellLiteral(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String windowsLiteral(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\') {
                backslashes++;
            } else if (current == '"') {
                appendBackslashes(quoted, backslashes * 2 + 1);
                quoted.append('"');
                backslashes = 0;
            } else {
                appendBackslashes(quoted, backslashes);
                quoted.append(current);
                backslashes = 0;
            }
        }
        appendBackslashes(quoted, backslashes * 2);
        return quoted.append('"').toString();
    }

    private static void appendBackslashes(StringBuilder target, int count) {
        target.append("\\".repeat(count));
    }

    static void detach(Path script) throws IOException {
        if (isWindows()) {
            wdetach(script);
            return;
        }
        Process process = new ProcessBuilder("sh", script.toString())
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        process.toHandle().onExit();
    }
}
