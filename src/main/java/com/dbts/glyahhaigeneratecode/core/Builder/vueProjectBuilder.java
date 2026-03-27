package com.dbts.glyahhaigeneratecode.core.Builder;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * vue项目打包部署
 */
@Slf4j
@Component
public class vueProjectBuilder {

    /**
     * 创建虚拟线程，并执行 Vue 项目构建任务
     * @param projectPath
     */
    public void BuildVirtualThreadForBuildVue (String projectPath){
        Thread.ofVirtual().name("vue-builder-"+System.currentTimeMillis())
                .start(() -> {
                    try {
                        buildProject(projectPath);
                    } catch (Exception e) {
                        throw new MyException(ErrorCode.SYSTEM_ERROR, "Vue项目代码构建失败: " + e.getMessage());
                    }
                });
    }


    /**
     * 构建 Vue 项目
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject (String projectPath) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在: {}", projectPath);
            return false;
        }
        // 检查 package.json 是否存在
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return false;
        }
        log.info("开始构建 Vue 项目: {}", projectPath);
        // 执行 npm install
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败");
            return false;
        }
        // 执行 npm run build
        if (!executeNpmBuild(projectDir)) {
            log.error("npm run build 执行失败");
            return false;
        }
        // 验证 dist 目录是否生成
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists()) {
            log.error("构建完成但 dist 目录未生成: {}", distDir.getAbsolutePath());
            return false;
        }
        log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
        return true;
    }





    /**
     * 执行 npm install 命令
     */
    private boolean executeNpmInstall(File projectDir) {
        log.info("执行 npm install...");
        String npmInstall = String.format("%s install", buildCommand("npm"));
        return executeCommand(projectDir, npmInstall, 300); // 5分钟超时
    }

    /**
     * 执行 npm run build 命令
     */
    private boolean executeNpmBuild(File projectDir) {
        log.info("执行 npm run build...");
        String npmBuild = String.format("%s run build", buildCommand("npm"));
        return executeCommand(projectDir, npmBuild, 180); // 3分钟超时
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
    private String buildCommand(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }



    /**
     * 执行命令
     *
     * @param workingDir     工作目录
     * @param command        命令字符串
     * @param timeoutSeconds 超时时间（秒）
     * @return 是否执行成功
     */
    private boolean executeCommand(File workingDir, String command, int timeoutSeconds) {
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);

            // 通过 ProcessBuilder 读取 stdout/stderr，避免只看退出码无法定位真实失败原因
            ProcessBuilder processBuilder = new ProcessBuilder(command.split("\\s+"));
            processBuilder.directory(workingDir);
            processBuilder.redirectErrorStream(true); // 合并 stdout/stderr，便于统一记录

            Process process = processBuilder.start();

            // 读取命令输出，限制最大长度避免日志/内存过大
            StringBuilder output = new StringBuilder();
            int maxOutputChars = 200_000;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (output.length() < maxOutputChars) {
                            output.append(line).append(System.lineSeparator());
                        }
                    }
                } catch (Exception ignored) {
                    // 读取日志失败不影响主流程
                }
            });
            readerThread.start();

            // 等待进程完成，设置超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程", timeoutSeconds);
                process.destroyForcibly();
                readerThread.join(2000);
                log.error("命令输出（截断）: {}", truncate(output.toString(), 4000));
                return false;
            }
            readerThread.join(2000);
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功: {}", command);
                return true;
            } else {
                log.error("命令执行失败，退出码: {}\n命令输出（截断）:\n{}", exitCode, truncate(output.toString(), 8000));
                return false;
            }
        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}", command, e.getMessage());
            return false;
        }
    }

    private String truncate(String input, int maxLen) {
        if (input == null) {
            return "";
        }
        if (input.length() <= maxLen) {
            return input;
        }
        return input.substring(0, maxLen) + "\n...（输出已截断）";
    }

}
