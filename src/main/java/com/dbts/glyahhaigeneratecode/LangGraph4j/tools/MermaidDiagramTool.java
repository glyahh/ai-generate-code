package com.dbts.glyahhaigeneratecode.LangGraph4j.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import com.dbts.glyahhaigeneratecode.LangGraph4j.enums.ImageCategoryEnum;
import com.dbts.glyahhaigeneratecode.LangGraph4j.model.ImageResource;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.manage.OssManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MermaidDiagramTool {

    public static final String MERMAID_ERROR_MARKER = "__MERMAID_ERROR__";

    @Resource
    private OssManager ossManager;
    
    @Tool("将 Mermaid 代码转换为架构图图片，用于展示系统结构和技术关系")
    public List<ImageResource> generateMermaidDiagram(@P("Mermaid 图表代码") String mermaidCode,
                                                      @P("架构图描述") String description) {
        if (StrUtil.isBlank(mermaidCode)) {
            return new ArrayList<>();
        }
        try {
            // 转换为SVG图片
            File diagramFile = convertMermaidToSvg(mermaidCode);
            // 上传到COS
            String keyName = String.format("/mermaid/%s/%s",
                    RandomUtil.randomString(5), diagramFile.getName());
            String cosUrl = ossManager.uploadFile(keyName, diagramFile);
            // 清理临时文件
            FileUtil.del(diagramFile);
            if (StrUtil.isNotBlank(cosUrl)) {
                return Collections.singletonList(ImageResource.builder()
                        .category(ImageCategoryEnum.ARCHITECTURE)
                        .description(description)
                        .url(cosUrl)
                        .build());
            }
            // SVG 已生成但上传失败时原逻辑静默返回空列表，集成测试 diagrams.get(0) 会 IOOBE；打日志便于区分「CLI 失败」与「OSS 失败」
            log.warn("Mermaid SVG 已生成但 OSS 上传未返回 URL: keyName={}", keyName);
        } catch (Exception e) {
            log.error("生成架构图失败: {}", e.getMessage(), e);
            return Collections.singletonList(buildMermaidErrorMarker(description));
        }
        return new ArrayList<>();
    }

    public static boolean isMermaidErrorMarker(ImageResource image) {
        if (image == null) {
            return false;
        }
        return MERMAID_ERROR_MARKER.equals(image.getDescription());
    }

    private ImageResource buildMermaidErrorMarker(String description) {
        return ImageResource.builder()
                .category(ImageCategoryEnum.ARCHITECTURE)
                .description(MERMAID_ERROR_MARKER)
                .url(description == null ? "" : description)
                .build();
    }

    /**
     * 将Mermaid代码转换为SVG图片
     */
    private File convertMermaidToSvg(String mermaidCode) {
        // 创建临时输入文件
        File tempInputFile = FileUtil.createTempFile("mermaid_input_", ".mmd", new File("temp"),  true);
        FileUtil.writeUtf8String(mermaidCode, tempInputFile);
        // 创建临时输出文件
        File tempOutputFile = FileUtil.createTempFile("mermaid_output_", ".svg", new File("temp"),true);
        // 根据操作系统选择命令
        String command = SystemUtil.getOsInfo().isWindows() ? "mmdc.cmd" : "mmdc";
        // 使用 ProcessBuilder 传参列表，避免路径含空格时拼成一条 shell 字符串被错误拆分（例如工作区在 ...\all Code\... 时 mmdc 收不到完整 -i/-o）
        ProcessBuilder pb = new ProcessBuilder(
                command,
                "-i", tempInputFile.getAbsolutePath(),
                "-o", tempOutputFile.getAbsolutePath(),
                "-b", "transparent"
        );
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new MyException(ErrorCode.SYSTEM_ERROR, "Mermaid CLI 执行超时");
            }
            String cliOutput = IoUtil.readUtf8(process.getInputStream());
            if (process.exitValue() != 0) {
                throw new MyException(ErrorCode.SYSTEM_ERROR,
                        "Mermaid CLI 退出码 " + process.exitValue() + ": " + StrUtil.maxLength(cliOutput, 800));
            }
        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "Mermaid CLI 执行异常: " + e.getMessage());
        }
        // 检查输出文件
        if (!tempOutputFile.exists() || tempOutputFile.length() == 0) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "Mermaid CLI 执行失败");
        }
        // 清理输入文件，保留输出文件供上传使用
        FileUtil.del(tempInputFile);
        return tempOutputFile;
    }
}
