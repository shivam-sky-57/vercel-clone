package com.vercel.build_server.runner;

import com.vercel.build_server.service.CommandExecutor;
import com.vercel.build_server.service.RedisLogPublisher;
import com.vercel.build_server.service.S3UploaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;

@Slf4j
@Component
@RequiredArgsConstructor
public class BuildTaskRunner implements CommandLineRunner {

    private final RedisLogPublisher logPublisher;
    private final CommandExecutor commandExecutor;
    private final S3UploaderService s3UploaderService;
    private final ApplicationContext applicationContext;

    @Value("${PROJECT_ID:${project.id:}}")
    private String projectId;

    @Value("${GIT_REPOSITORY__URL:${git.repository.url:}}")
    private String gitRepositoryUrl;

    @Value("${build.workspace.dir:/app/output}")
    private String workspaceDirConfig;

    @Override
    public void run(String... args) {
        log.info("Build Server Worker starting up...");

        if (projectId == null || projectId.isBlank() || gitRepositoryUrl == null || gitRepositoryUrl.isBlank()) {
            log.warn("PROJECT_ID or GIT_REPOSITORY__URL is empty. Standing by or verification run.");
            return;
        }

        int exitCode = 0;
        try {
            logPublisher.log("==================================================");
            logPublisher.log("🚀 Executing Build for Project: " + projectId);
            logPublisher.log("📦 Git Repository URL: " + gitRepositoryUrl);
            logPublisher.log("==================================================");

            File workingDir = resolveWorkingDirectory();

            // 1. Clone Git Repository
            logPublisher.log("Step 1/4: Cloning Git repository...");
            int cloneStatus = commandExecutor.runCommand(
                    "git clone " + gitRepositoryUrl + " " + workingDir.getAbsolutePath(),
                    null
            );
            if (cloneStatus != 0) {
                throw new RuntimeException("Git clone failed with exit code: " + cloneStatus);
            }

            // 2. Install NPM dependencies
            logPublisher.log("Step 2/4: Running npm install...");
            int installStatus = commandExecutor.runCommand("npm install", workingDir);
            if (installStatus != 0) {
                throw new RuntimeException("npm install failed with exit code: " + installStatus);
            }

            // 3. Compile frontend build
            logPublisher.log("Step 3/4: Running npm run build...");
            int buildStatus = commandExecutor.runCommand("npm run build", workingDir);
            if (buildStatus != 0) {
                throw new RuntimeException("npm run build failed with exit code: " + buildStatus);
            }

            // 4. Locate dist/ or build/ output folder
            File distFolder = new File(workingDir, "dist");
            File buildFolder = new File(workingDir, "build");
            File targetFolder = distFolder.exists() ? distFolder : (buildFolder.exists() ? buildFolder : null);

            if (targetFolder == null || !targetFolder.isDirectory()) {
                throw new RuntimeException("Build succeeded, but neither 'dist' nor 'build' folder was generated!");
            }

            // 5. Upload files to AWS S3 (__outputs/<PROJECT_ID>/...)
            logPublisher.log("Step 4/4: Uploading static files to AWS S3...");
            s3UploaderService.uploadDirectory(projectId, targetFolder.toPath());

            logPublisher.log("==================================================");
            logPublisher.log("🎉 Done... Project " + projectId + " deployed successfully to S3!");
            logPublisher.log("==================================================");

        } catch (Exception e) {
            log.error("Fatal Build Error: {}", e.getMessage(), e);
            logPublisher.log("❌ Fatal Build Error: " + e.getMessage());
            exitCode = 1;
        } finally {
            final int finalExitCode = exitCode;
            log.info("Build worker finished with exit code: {}", finalExitCode);
            if (isContainerEnvironment()) {
                System.exit(SpringApplication.exit(applicationContext, () -> finalExitCode));
            }
        }
    }

    private File resolveWorkingDirectory() {
        File dir = new File(workspaceDirConfig);
        if (dir.exists()) {
            log.info("Cleaning up existing workspace directory from previous test: {}", dir.getAbsolutePath());
            org.springframework.util.FileSystemUtils.deleteRecursively(dir);
        }
        dir.mkdirs();
        return dir;
    }

    private boolean isContainerEnvironment() {
        return new File("/.dockerenv").exists() || System.getenv("ECS_CONTAINER_METADATA_URI") != null;
    }
}
