package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import com.dbts.glyahhaigeneratecode.ai.tool.tool_assist.FileModifyTool_Assist;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.service.AppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileToolsProjectRootResolutionTest {

    private final long appId = 456L;
    private final Path rootDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "multi_file_" + appId).normalize().toAbsolutePath();

    @AfterEach
    void cleanup() throws Exception {
        if (!Files.exists(rootDir)) {
            return;
        }
        // best-effort recursive delete
        try (var walk = Files.walk(rootDir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // ignore
                        }
                    });
        }
    }

    @Test
    void writeAndRead_shouldUseTypeBasedOutputDir_forMultiFile() throws Exception {
        AppService appService = mock(AppService.class);
        App app = new App();
        app.setId(appId);
        app.setCodeGenType("multi_file");
        when(appService.getById(appId)).thenReturn(app);

        FileWriteTool writeTool = new FileWriteTool();
        ReflectionTestUtils.setField(writeTool, "appService", appService);

        String content = "<!doctype html><html><body>ok</body></html>";
        String writeResult = writeTool.writeFile("index.html", content, appId);
        assertTrue(writeResult.contains("文件写入成功"));

        Path expected = rootDir.resolve("index.html");
        assertTrue(Files.exists(expected));
        assertEquals(content, Files.readString(expected));

        FileReadTool readTool = new FileReadTool();
        ReflectionTestUtils.setField(readTool, "appService", appService);
        String readBack = readTool.readFile("index.html", appId);
        assertEquals(content, readBack);
    }

    @Test
    void modify_shouldAllowCoreWebFiles_forMultiFile() throws Exception {
        AppService appService = mock(AppService.class);
        App app = new App();
        app.setId(appId);
        app.setCodeGenType("multi_file");
        when(appService.getById(appId)).thenReturn(app);

        FileWriteTool writeTool = new FileWriteTool();
        ReflectionTestUtils.setField(writeTool, "appService", appService);
        writeTool.writeFile("index.html", "HELLO_WORLD", appId);

        FileModifyTool modifyTool = new FileModifyTool();
        ReflectionTestUtils.setField(modifyTool, "appService", appService);
        ReflectionTestUtils.setField(modifyTool, "fileModifyToolAssist", new FileModifyTool_Assist());
        String result = modifyTool.modifyFile("index.html", "HELLO_WORLD", "HELLO_OK", appId);
        assertTrue(result.contains("文件修改成功"));

        Path expected = rootDir.resolve("index.html");
        assertEquals("HELLO_OK", Files.readString(expected));
    }

    @Test
    void readFile_shouldRejectAbsolutePathOutsideProjectRoot() throws Exception {
        long id = 789L;
        AppService appService = mock(AppService.class);
        App app = new App();
        app.setId(id);
        app.setCodeGenType("multi_file");
        when(appService.getById(id)).thenReturn(app);

        Path outside = Path.of(System.getProperty("java.io.tmpdir")).resolve("glyahh_file_tool_guard_test.txt");
        Files.writeString(outside, "x");
        try {
            FileReadTool readTool = new FileReadTool();
            ReflectionTestUtils.setField(readTool, "appService", appService);
            String msg = readTool.readFile(outside.toAbsolutePath().toString(), id);
            assertTrue(msg.contains("禁止读取项目目录外"), msg);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void readDir_shouldNotIncludeNodeModulesOrDist() throws Exception {
        AppService appService = mock(AppService.class);
        App app = new App();
        app.setId(appId);
        app.setCodeGenType("multi_file");
        when(appService.getById(appId)).thenReturn(app);

        FileWriteTool writeTool = new FileWriteTool();
        ReflectionTestUtils.setField(writeTool, "appService", appService);

        // Create project structure
        writeTool.writeFile("src/App.vue", "app", appId);
        writeTool.writeFile("src/main.ts", "main", appId);
        writeTool.writeFile("package.json", "{}", appId);

        // Simulate node_modules and dist
        Path nodeModulesDir = rootDir.resolve("node_modules");
        Files.createDirectories(nodeModulesDir);
        Files.writeString(nodeModulesDir.resolve("package.json"), "{}");

        Path distDir = rootDir.resolve("dist");
        Files.createDirectories(distDir);
        Files.writeString(distDir.resolve("index.html"), "<html>");

        FileDirReadTool dirTool = new FileDirReadTool();
        ReflectionTestUtils.setField(dirTool, "appService", appService);
        String output = dirTool.readDir("", appId);

        assertTrue(output.contains("src/App.vue"), "should contain src/App.vue: " + output);
        assertTrue(output.contains("src/main.ts"), "should contain src/main.ts: " + output);
        assertFalse(output.contains("node_modules"), "must not contain node_modules: " + output);
        assertFalse(output.contains("dist/"), "must not contain dist contents: " + output);
    }

    @Test
    void writeFile_shouldRejectAbsolutePathOutsideProjectRoot() throws Exception {
        long id = 790L;
        AppService appService = mock(AppService.class);
        App app = new App();
        app.setId(id);
        app.setCodeGenType("multi_file");
        when(appService.getById(id)).thenReturn(app);

        Path outside = Path.of(System.getProperty("java.io.tmpdir")).resolve("glyahh_file_write_guard_test.txt");
        try {
            FileWriteTool writeTool = new FileWriteTool();
            ReflectionTestUtils.setField(writeTool, "appService", appService);
            String msg = writeTool.writeFile(outside.toAbsolutePath().toString(), "hack", id);
            assertTrue(msg.contains("禁止写入项目目录外"), msg);
            assertFalse(Files.exists(outside), "must not create file outside project");
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}

