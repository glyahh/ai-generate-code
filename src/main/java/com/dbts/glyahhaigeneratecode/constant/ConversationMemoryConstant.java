package com.dbts.glyahhaigeneratecode.constant;

import java.util.Set;

/**
 * 会话记忆注入与 manifest 扫描相关默认阈值、目录与扩展名白名单。
 */
public interface ConversationMemoryConstant {

    /**
     * 构建快照 / 变更清单时跳过的目录名（路径段精确匹配，小写比较由调用方决定）。
     */
    Set<String> SNAPSHOT_IGNORE_DIRS = Set.of(
            "node_modules", ".git", "dist", "target", "temp", "build", "coverage", ".idea", ".vscode"
    );

    /**
     * 允许读入并注入模型上下文的文本类扩展名（不含点，小写）。
     */
    Set<String> TEXT_FILE_EXTS = Set.of(
            "java", "kt", "js", "ts", "tsx", "jsx", "vue", "html", "htm", "css", "scss", "less",
            "json", "yaml", "yml", "xml", "md", "txt", "properties", "sql", "sh", "bat", "ps1"
    );

    /**
     * 单次注入流程中，正文类内容累计字符上限的默认值（可与配置覆盖配合）。
     */
    int DEFAULT_INJECT_CHAR_BUDGET = 20000;

    /**
     * 单次注入流程中，按粗略 token 估算的预算默认值（可与配置覆盖配合）。
     */
    int DEFAULT_INJECT_TOKEN_BUDGET = 5000;

    /**
     * 从磁盘按页读取单文件时的默认页大小（字符量级截断）。
     */
    int DEFAULT_PAGE_SIZE = 10000;

    /**
     * 注入块中「文件头」摘要单行/前缀的最大字符数。
     */
    int INJECT_FILE_HEADER_MAX_LENGTH = 160;
}
