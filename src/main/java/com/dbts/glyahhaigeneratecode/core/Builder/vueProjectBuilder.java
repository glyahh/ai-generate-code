package com.dbts.glyahhaigeneratecode.core.Builder;

import cn.hutool.core.io.FileUtil;
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
     * 在虚拟线程中异步执行 {@link #buildProject(String)}，不阻塞调用方
     *
     * @param projectPath Vue 项目根目录绝对路径
     */
    public void BuildVirtualThreadForBuildVue (String projectPath){
        // 1. 创建命名虚拟线程并启动
        Thread.ofVirtual().name("vue-builder-"+System.currentTimeMillis())
                .start(() -> {
                    try {
                        // 2. 同步执行构建
                        buildProject(projectPath);
                    } catch (Exception e) {
                        // 3. 失败转统一业务异常（线程内抛出由未捕获处理器处理）
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
        // 1. 校验项目目录存在
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在: {}", projectPath);
            return false;
        }
        // 2. 校验 package.json
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return false;
        }
        log.info("开始构建 Vue 项目: {}", projectPath);

        // 3. 清理旧 dist，避免脏产物
        File distDirToClean = new File(projectDir, "dist");
        if (distDirToClean.exists()) {
            try {
                FileUtil.del(distDirToClean);
                log.info("已清理旧 dist 目录，准备本轮构建");
            } catch (Exception e) {
                log.warn("清理旧 dist 失败（继续构建）: {}", e.getMessage());
            }
        }

        // 4. 尝试自动修复常见 vite.config 缺 import 问题
        try {
            fixViteConfigIfNeeded(projectDir);
        } catch (Exception e) {
            log.warn("Vite 配置自动修复失败（将继续尝试构建）: {}", e.getMessage());
        }

        // 5. npm install
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败");
            return false;
        }

        // 6. SFC 语法预检（失败可尝试工具修复后再检）
        if (!validateVueSfcBeforeBuild(projectDir)) {
            log.error("Vue SFC 语法校验失败，终止构建");
            return false;
        }

        // 7. npm run build
        if (!executeNpmBuild(projectDir)) {
            log.error("npm run build 执行失败");
            return false;
        }
        // 8. 确认 dist 已生成
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
        // 1. 收集可能存在的 vite 配置文件路径
        List<Path> candidates = new ArrayList<>();
        candidates.add(projectDir.toPath().resolve("vite.config.ts"));
        candidates.add(projectDir.toPath().resolve("vite.config.js"));
        candidates.add(projectDir.toPath().resolve("vite.config.mjs"));
        candidates.add(projectDir.toPath().resolve("vite.config.cjs"));

        for (Path p : candidates) {
            // 2. 文件不存在则跳过
            if (!Files.isRegularFile(p)) {
                continue;
            }
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            // 3. 未使用 fileURLToPath 则无需补丁
            if (!raw.contains("fileURLToPath")) {
                continue;
            }
            // 4. 已有 import 则跳过
            boolean hasFileUrlToPathImport =
                    raw.contains("import { fileURLToPath") ||
                    raw.contains("import{fileURLToPath") ||
                    raw.contains("import {fileURLToPath");
            if (hasFileUrlToPathImport) {
                continue;
            }

            // 5. 在文件头插入 import（兼容 shebang）
            String patchLine = "import { fileURLToPath } from 'node:url'\n";
            String fixed = raw.startsWith("#!")
                    ? raw.replaceFirst("^#!.*\\R", "$0" + patchLine)
                    : patchLine + raw;
            Files.writeString(p, fixed, StandardCharsets.UTF_8);
            log.info("已自动修复 Vite 配置缺失 import: {}", p.toAbsolutePath());
        }
    }





    /**
     * 在项目目录执行 npm install
     *
     * @param projectDir 项目根目录
     * @return 退出码 0 为成功
     */
    private boolean executeNpmInstall(File projectDir) {
        // 1. 组装 npm(.cmd) install 命令
        log.info("执行 npm install...");
        String npmInstall = String.format("%s install", buildCommand("npm"));
        // 2. 交给通用执行器（5 分钟超时）
        return executeCommand(projectDir, npmInstall, 300);
    }

    /**
     * 在项目目录执行 npm run build
     *
     * @param projectDir 项目根目录
     * @return 退出码 0 为成功
     */
    private boolean executeNpmBuild(File projectDir) {
        // 1. 组装 npm run build
        log.info("执行 npm run build...");
        String npmBuild = String.format("%s run build", buildCommand("npm"));
        // 2. 执行命令（3 分钟超时）
        return executeCommand(projectDir, npmBuild, 180);
    }

    /**
     * 当前 JVM 是否跑在 Windows 上
     *
     * @return true 表示 Windows
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * Windows 下为 npm/node 追加 .cmd 后缀，其它 OS 原样返回
     *
     * @param baseCommand 如 npm
     * @return 可传给 ProcessBuilder 的命令前缀
     */
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
        // 1. 首次全量 SFC parse
        log.info("执行 Vue SFC parse 语法校验（@vue/compiler-sfc）...");
        boolean ok = executeVueSfcParseCheck(projectDir, 60);
        if (ok) {
            return true;
        }

        // 2. 失败则调用修复工具扫 src 下 .vue
        try {
            VueSfcSyntaxCheckFixTool.FixSummary summary =
                    vueSfcSyntaxCheckFixTool.fixProjectVueFiles(projectDir.getAbsolutePath());
            log.warn("Vue SFC 语法修复已执行: scanned={}, changed={}, failed={}",
                    summary.scannedFiles(), summary.changedFiles(), summary.failedFiles());
        } catch (Exception e) {
            log.warn("Vue SFC 语法修复工具执行失败（仍会继续二次校验）: {}", e.getMessage());
        }

        // 3. 修复后再跑一次 parse 检查
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
            // 1. 落地临时 node 脚本到项目根（避免命令行引号被 shell 拆坏）
            Path scriptFile = projectDir.toPath().resolve(".gly_sfc_check.cjs");
            Files.writeString(scriptFile, buildSfcCheckScript(), StandardCharsets.UTF_8);
            // 2. node 执行该脚本
            String cmd = String.format("%s %s", "node", scriptFile.getFileName().toString());
            return executeCommand(projectDir, cmd, timeoutSeconds);
        } catch (Exception e) {
            log.warn("Vue SFC parse 校验脚本执行失败（将视为校验失败）: {}", e.getMessage());
            return false;
        }
    }

//    /**
//     * 生成用于遍历 src/**/*.vue 并调用 @vue/compiler-sfc 的 Node 脚本源码
//     *
//     * @return .cjs 脚本内容
//     */
    // 用于修复极少数缺少<style>标签无法执行npm run build的情况
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
            // 1. 记录即将执行的命令
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);

            // 2. 启动子进程（stdout/stderr 合并）
            ProcessBuilder processBuilder = new ProcessBuilder(command.split("\\s+"));
            processBuilder.directory(workingDir);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            // 3. 异步读输出，防止缓冲区塞满导致死锁
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
                }
            });
            readerThread.start();

            // 4. 带超时等待结束
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程", timeoutSeconds);
                process.destroyForcibly();
                readerThread.join(2000);
                log.error("命令输出（截断）: {}", truncate(output.toString(), 4000));
                return false;
            }
            readerThread.join(2000);
            // 5. 根据退出码判断成败
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

    /**
     * 日志输出截断，防止单条过长
     *
     * @param input  原始字符串
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    private String truncate(String input, int maxLen) {
        // 1. null 当空
        if (input == null) {
            return "";
        }
        // 2. 未超长原样
        if (input.length() <= maxLen) {
            return input;
        }
        // 3. 截断并提示
        return input.substring(0, maxLen) + "\n...（输出已截断）";
    }

}
