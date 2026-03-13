package com.dbts.glyahhaigeneratecode.service;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 项目打包下载 服务接口。
 *
 * 负责将指定生成代码目录打包为 zip 并写入响应流。
 */
public interface ProjectDownloadService {

    /**
     * 打包并下载项目目录
     *
     * @param response    HTTP 响应
     * @param appName     应用名称，用于 zip 文件名
     * @param projectPath 项目所在目录的绝对路径（位于 temp/code_output 下）
     */
    void downloadProject(HttpServletResponse response, String appName, String projectPath);
}

