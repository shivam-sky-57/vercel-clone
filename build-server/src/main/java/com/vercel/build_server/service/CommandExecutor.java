package com.vercel.build_server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandExecutor {

    private final RedisLogPublisher logPublisher;

    public int runCommand(String command, File workingDirectory) throws Exception {
        logPublisher.log(">> Executing command: " + command);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder processBuilder;

        if (isWindows) {
            processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            processBuilder = new ProcessBuilder("sh", "-c", command);
        }

        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory);
        }

        Process process = processBuilder.start();

        // Asynchronously capture stdout
        CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logPublisher.log(line);
                }
            } catch (Exception e) {
                logPublisher.log("[ERROR] Failed reading stdout: " + e.getMessage());
            }
        });

        // Asynchronously capture stderr
        CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logPublisher.log(line);
                }
            } catch (Exception e) {
                logPublisher.log("[ERROR] Failed reading stderr: " + e.getMessage());
            }
        });

        // Wait for both output streams to flush
        CompletableFuture.allOf(stdoutFuture, stderrFuture).join();

        int exitCode = process.waitFor();
        logPublisher.log(">> Command finished with exit code: " + exitCode);
        return exitCode;
    }
}
