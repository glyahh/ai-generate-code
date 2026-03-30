package com.dbts.glyahhaigeneratecode.core.Builder;

import com.dbts.glyahhaigeneratecode.ai.tool.tools.VueSfcSyntaxCheckFixTool;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * vue项目打包部署
 */
@Slf4j
@Component
public class vueProjectBuilder {

    @Resource
    private VueSfcSyntaxCheckFixTool vueSfcSyntaxCheckFixTool;

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

        // [LLM 缺陷兜底] Vite 配置文件常见缺陷：使用 fileURLToPath 但未 import，导致构建直接失败。
        // 证据：ReferenceError: fileURLToPath is not defined（vite 加载 bundled config 时抛出）。
        try {
            fixViteConfigIfNeeded(projectDir);
        } catch (Exception e) {
            log.warn("Vite 配置自动修复失败（将继续尝试构建）: {}", e.getMessage());
        }

        // 执行 npm install
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败");
            return false;
        }

        // [SFC parse 校验] build 前先用 @vue/compiler-sfc 做语法校验；失败则调用工具修复后重试。
        if (!validateVueSfcBeforeBuild(projectDir)) {
            log.error("Vue SFC 语法校验失败，终止构建");
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
     * 自动修复 LLM 生成的 Vite 配置缺陷（最小侵入）。
     * - 若 vite.config.* 引用了 fileURLToPath，但未引入，则补上：import { fileURLToPath } from 'node:url'
     * 说明：Node ESM 环境下 fileURLToPath 不在全局对象中，不显式 import 会导致构建失败。
     */
    private void fixViteConfigIfNeeded(File projectDir) throws Exception {
        List<Path> candidates = new ArrayList<>();
        candidates.add(projectDir.toPath().resolve("vite.config.ts"));
        candidates.add(projectDir.toPath().resolve("vite.config.js"));
        candidates.add(projectDir.toPath().resolve("vite.config.mjs"));
        candidates.add(projectDir.toPath().resolve("vite.config.cjs"));

        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            // 只在“使用了 fileURLToPath 且没有任何相关 import”时补丁，避免误改用户自定义配置
            if (!raw.contains("fileURLToPath")) {
                continue;
            }
            boolean hasFileUrlToPathImport =
                    raw.contains("import { fileURLToPath") ||
                    raw.contains("import{fileURLToPath") ||
                    raw.contains("import {fileURLToPath");
            if (hasFileUrlToPathImport) {
                continue;
            }

            String patchLine = "import { fileURLToPath } from 'node:url'\n";
            // 插入到顶部；若存在 shebang（极少见）则放在 shebang 后
            String fixed = raw.startsWith("#!")
                    ? raw.replaceFirst("^#!.*\\R", "$0" + patchLine)
                    : patchLine + raw;
            Files.writeString(p, fixed, StandardCharsets.UTF_8);
            log.info("已自动修复 Vite 配置缺失 import: {}", p.toAbsolutePath());
        }
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
     * 在 npm run build 前进行 Vue SFC 语法校验：
     * - 校验通过：直接继续 build
     * - 校验失败：调用 {@link VueSfcSyntaxCheckFixTool} 修复 src 目录下所有 .vue 文件，然后再校验一次
     */
    private boolean validateVueSfcBeforeBuild(File projectDir) {
        log.info("执行 Vue SFC parse 语法校验（@vue/compiler-sfc）...");
        boolean ok = executeVueSfcParseCheck(projectDir, 60);
        if (ok) {
            return true;
        }

        // 证据：vite 构建日志常见 "Element is missing end tag" 等 SFC 解析错误
        try {
            VueSfcSyntaxCheckFixTool.FixSummary summary =
                    vueSfcSyntaxCheckFixTool.fixProjectVueFiles(projectDir.getAbsolutePath());
            log.warn("Vue SFC 语法修复已执行: scanned={}, changed={}, failed={}",
                    summary.scannedFiles(), summary.changedFiles(), summary.failedFiles());
        } catch (Exception e) {
            log.warn("Vue SFC 语法修复工具执行失败（仍会继续二次校验）: {}", e.getMessage());
        }

        log.info("执行 Vue SFC parse 二次语法校验（修复后）...");
        return executeVueSfcParseCheck(projectDir, 60);
    }

    /**
     * 使用 node + @vue/compiler-sfc 解析 src 目录下所有 .vue；发现语法错误则退出码=1，并打印文件与错误位置。
     *
     * 注意：
     * - 该校验依赖 npm install 已完成（确保 node_modules 存在）
     * - 为避免 command split 破坏引号/脚本内容，这里将脚本落成临时 .cjs 文件再执行
     */
    private boolean executeVueSfcParseCheck(File projectDir, int timeoutSeconds) {
        try {
            Path scriptFile = projectDir.toPath().resolve(".gly_sfc_check.cjs");
            Files.writeString(scriptFile, buildSfcCheckScript(), StandardCharsets.UTF_8);
            // Windows 上常见的是 node.exe，而不是 node.cmd；这里固定使用 node，避免 CreateProcess error=2
            String cmd = String.format("%s %s", "node", scriptFile.getFileName().toString());
            return executeCommand(projectDir, cmd, timeoutSeconds);
        } catch (Exception e) {
            log.warn("Vue SFC parse 校验脚本执行失败（将视为校验失败）: {}", e.getMessage());
            return false;
        }
    }

    private String buildSfcCheckScript() {
        return """
                const fs = require('fs');
                const path = require('path');
                const { parse } = require('@vue/compiler-sfc');
                
                function walk(dir, out) {
                  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
                    const p = path.join(dir, e.name);
                    if (e.isDirectory()) walk(p, out);
                    else if (e.isFile() && p.endsWith('.vue')) out.push(p);
                  }
                }
                
                const srcDir = path.join(process.cwd(), 'src');
                if (!fs.existsSync(srcDir)) {
                  console.log('[sfc-check] src 目录不存在，跳过');
                  process.exit(0);
                }
                
                const files = [];
                walk(srcDir, files);
                
                let hasError = false;
                for (const f of files) {
                  const code = fs.readFileSync(f, 'utf8');
                  const res = parse(code, { filename: f });
                  if (res.errors && res.errors.length) {
                    hasError = true;
                    console.log(`\\n[sfc-check] 文件解析失败: ${f}`);
                    for (const err of res.errors) {
                      if (err.loc) {
                        console.log(`[sfc-check] ${err.message} @ ${err.loc.start.line}:${err.loc.start.column}`);
                      } else {
                        console.log(`[sfc-check] ${String(err.message || err)}`);
                      }
                    }
                  }
                }
                
                process.exit(hasError ? 1 : 0);
                """;
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
