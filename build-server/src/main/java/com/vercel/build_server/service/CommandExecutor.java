package com.vercel.build_server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class CommandExecutor {

    public int runCommand(String command, File workingDirectory) throws Exception {
        log.info(">> Executing command: {}", command);

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
                    System.out.println(line);
                }
            } catch (Exception e) {
                log.error("Failed to read process stdout: {}", e.getMessage());
            }
        });

        // Asynchronously capture stderr
        CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (Exception e) {
                log.error("Failed to read process stderr: {}", e.getMessage());
            }
        });

        // Wait for both output streams to flush
        CompletableFuture.allOf(stdoutFuture, stderrFuture).join();

        int exitCode = process.waitFor();
        log.info(">> Command finished with exit code: {}", exitCode);
        return exitCode;
    }
}
