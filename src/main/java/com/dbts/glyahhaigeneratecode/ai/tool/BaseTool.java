package com.dbts.glyahhaigeneratecode.ai.tool;

import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 工具基类
 * 定义所有工具的通用接口
 *
 * 模板类
 */
public abstract class BaseTool {

    /**
     * 获取工具的英文名称（对应方法名）
     *
     * @return 工具英文名称
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称
     *
     * @return 工具中文名称
     */
    public abstract String getDisplayName();

    /**
     * 生成工具请求时的返回值（显示给用户）
     *
     * @return 格式化的工具请求显示内容
     */
    public String generateToolRequestResponse() {
        return String.format("\n\n[选择工具] %s\n", getDisplayName());
    }

    /**
     * 生成工具执行结果格式（保存到数据库）
     *
     * @param arguments 工具执行参数
     * @return 格式化的工具执行结果
     */
    public abstract String generateToolExecutedResult (JSONObject arguments);

    /**
     * 统一解析各代码类型对应的项目根目录。
     * <p>
     * - HTML / MULTI_FILE: {CODE_OUTPUT_ROOT_DIR}/{type}_{appId}
     * - VUE: 兼容旧目录结构 {CODE_OUTPUT_ROOT_DIR}/vue_project_{appId}
     * </p>
     */
    protected Path resolveProjectRoot(Long appId, AppService appService) {
        if (appId == null || appId <= 0 || appService == null) {
            return null;
        }
        try {
            App app = appService.getById(appId);
            CodeGenTypeEnum type = app == null ? null : CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (type == null) {
                return null;
            }
            if (Objects.equals(type, CodeGenTypeEnum.VUE)) {
                return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
            }
            return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, type.getValue() + "_" + appId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 返回规范化绝对路径的项目根，供越界校验使用。
     * {@link #resolveProjectRoot} 失败时回退 {@code vue_project_{appId}}，与各文件工具既有行为一致。
     *
     * @return 绝对路径；{@code appId} 无效时返回 {@code null}
     */
    protected Path resolveNormalizedProjectRoot(Long appId, AppService appService) {
        if (appId == null || appId <= 0) {
            return null;
        }
        Path root = resolveProjectRoot(appId, appService);
        if (root == null) {
            root = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        }
        return root.normalize().toAbsolutePath();
    }

    /**
     * 将绝对文件路径转为相对项目根的路径（正斜杠）。
     */
    protected String toRelativePath(Path projectRoot, Path absoluteFile) {
        if (projectRoot == null || absoluteFile == null) {
            return null;
        }
        try {
            return projectRoot.relativize(absoluteFile.normalize().toAbsolutePath())
                    .toString()
                    .replace('\\', '/');
        } catch (Exception e) {
            return null;
        }
    }
}
