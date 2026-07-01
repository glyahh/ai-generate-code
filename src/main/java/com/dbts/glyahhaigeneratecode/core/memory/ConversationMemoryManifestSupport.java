package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.constant.ConversationMemoryConstant;
import com.dbts.glyahhaigeneratecode.mapper.SnapshotHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.SnapshotHistory;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 代码目录 manifest 工具。
 * <p>负责：项目根目录解析、manifest 构建/查找/diff/落盘、文件元数据
 * （相对路径、忽略目录、文本代码判断、语言推断、SHA-256）。</p>
 * <p>静态工具类，依赖以参数传入；不持有 Spring bean。</p>
 */
public final class ConversationMemoryManifestSupport {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryManifestSupport.class);

    /** 上一轮缺失时，回退保留的最大 changedFiles 条数。 */
    public static final int FIRST_ROUND_CHANGED_FILES_LIMIT = 20;

    private ConversationMemoryManifestSupport() {
    }

    /**
     * Manifest 条目。
     */
    public record ManifestItem(String path, String hash, long size, long mtime, String lang) {
    }

    /**
     * Manifest 包。
     */
    public record ManifestBundle(List<ManifestItem> items) {
    }

    /**
     * 解析项目根目录。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @return 项目根路径
     */
    public static Path resolveProjectRoot(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE) {
            return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        }
        String type = codeGenTypeEnum == null ? CodeGenTypeEnum.MULTI_FILE.getValue() : codeGenTypeEnum.getValue();
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, type + "_" + appId);
    }

    /**
     * 生成当前代码目录 manifest。
     *
     * @param root 代码目录
     * @return manifest 包；目录不可用时返回空 manifest
     */
    public static ManifestBundle buildManifest(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new ManifestBundle(Collections.emptyList());
        }
        List<ManifestItem> items = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    // 是普通文件
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(root, path))
                    .filter(path -> isTextCodeFile(path))
                    // 使用unix分隔符对所有文件按照字典序进行排序
                    .sorted(Comparator.comparing(path -> toRelativeUnixPath(root, path), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (Path file : files) {
                try {
                    String relative = toRelativeUnixPath(root, file);
                    long size = Files.size(file);
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    String lang = detectLang(relative);
                    String hash = sha256Hex(Files.readAllBytes(file));
                    items.add(new ManifestItem(relative, hash, size, mtime, lang));
                } catch (Exception ignore) {
                    // 单文件读取失败跳过
                }
            }
        } catch (Exception e) {
            log.warn("构建 manifest 失败，root={}", root, e);
        }
        return new ManifestBundle(items);
    }

    /**
     * 从 snapshot_history 读取最新 manifest。
     *
     * @param appId        应用 id
     * @param mapper       snapshot mapper
     * @param objectMapper JSON 序列化器
     * @return manifest 包；未命中时返回 null
     */
    public static ManifestBundle findLatestManifest(Long appId, SnapshotHistoryMapper mapper, ObjectMapper objectMapper) {
        try {
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(SnapshotHistory::getAppId, appId);
            queryWrapper.orderBy(SnapshotHistory::getId, false);
            queryWrapper.limit(1);
            List<SnapshotHistory> list = mapper.selectListByQuery(queryWrapper);
            if (list == null || list.isEmpty()) {
                return null;
            }
            String json = list.getFirst().getManifestJson();
            if (StrUtil.isBlank(json)) {
                return null;
            }
            // TypeReference 通过匿名内部类保留编译期泛型信息，让 Jackson 拿到完整类型 List<ManifestItem>
            List<ManifestItem> items = objectMapper.readValue(json, new TypeReference<>() {
            });
            return new ManifestBundle(items == null ? Collections.emptyList() : items);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算前后 manifest changedFiles。
     *
     * @param previous 上一轮 manifest
     * @param current  当前 manifest
     * @return changedFiles
     */
    public static List<String> diffChangedFiles(ManifestBundle previous, ManifestBundle current) {
        if (current == null || current.items().isEmpty()) {
            return Collections.emptyList();
        }
        if (previous == null || previous.items().isEmpty()) {
            return current.items().stream().map(ManifestItem::path).limit(FIRST_ROUND_CHANGED_FILES_LIMIT).toList();
        }
        Map<String, ManifestItem> prevMap = previous.items().stream()
                .collect(Collectors.toMap(ManifestItem::path, item -> item, (a, b) -> a));
        List<String> changed = new ArrayList<>();
        for (ManifestItem item : current.items()) {
            ManifestItem prev = prevMap.get(item.path());
            if (prev == null || !Objects.equals(prev.hash(), item.hash())) {
                changed.add(item.path());
            }
        }
        return changed;
    }

    /**
     * 写入 snapshot_history。
     *
     * @param appId        应用 id
     * @param roundId      轮次 id
     * @param manifest     manifest
     * @param mapper       snapshot mapper
     * @param objectMapper JSON 序列化器
     * @return 写入的 snapshotId；失败时返回 0
     */
    public static long insertSnapshotHistory(Long appId, Long roundId, ManifestBundle manifest,
                                             SnapshotHistoryMapper mapper, ObjectMapper objectMapper) {
        try {
            String json = objectMapper.writeValueAsString(manifest.items());
            SnapshotHistory row = SnapshotHistory.builder()
                    .appId(appId)
                    .roundId(roundId)
                    .manifestJson(json)
                    .filesCount(manifest.items().size())
                    .createdAt(LocalDateTime.now())
                    .build();
            mapper.insert(row);
            Long id = row.getId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 把 file 相对 root 的路径转成统一 unix 形式（替换 \\ 为 /）。
     */
    static String toRelativeUnixPath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 判断路径是否应忽略。
     *
     * @param root 根目录
     * @param path 文件路径
     * @return true-忽略；false-保留
     */
    static boolean isIgnoredPath(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (ConversationMemoryConstant.SNAPSHOT_IGNORE_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否文本代码文件。
     *
     * @param path 文件
     * @return true-文本代码；false-其他
     */
    static boolean isTextCodeFile(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
        return ConversationMemoryConstant.TEXT_FILE_EXTS.contains(ext);
    }

    /**
     * 推断文件语言标签。
     *
     * @param relative 相对路径
     * @return 语言名
     */
    static String detectLang(String relative) {
        if (relative == null) {
            return "text";
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".vue")) {
            return "vue";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        }
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "javascript";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        }
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) {
            return "css";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        return "text";
    }

    /**
     * 计算 SHA-256 十六进制。
     */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 计算字符串 SHA-256 十六进制。
     */
    public static String sha256Hex(String text) {
        return sha256Hex(StrUtil.blankToDefault(text, "").getBytes(StandardCharsets.UTF_8));
    }
}
