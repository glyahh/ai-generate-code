package com.dbts.glyahhaigeneratecode.core.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 目录元信息稳定性等待工具。
 * <p>用于在生成 manifest 前轮询目录文件数与最新 mtime，连两次无变化时认为落盘完成，
 * 避免把半写文件吸入 manifest / diff。</p>
 */
public final class ConversationMemoryDirStabilitySupport {

    private ConversationMemoryDirStabilitySupport() {
    }

    /**
     * 目录指标。
     */
    public record DirMetrics(long fileCount, long latestMtime) {
    }

    /**
     * 等待目录元信息稳定，连续两次采样一致即返回。
     *
     * @param root       代码目录（传 null 时直接返回）
     * @param retryTimes 最大重试次数（小于 1 时按 1 处理）
     * @param sleepMs    每次重试间隔毫秒（小于 50 时按 50 处理）
     */
    public static void awaitStableDirectory(Path root, int retryTimes, long sleepMs) {
        if (root == null) {
            return;
        }
        int safeRetries = Math.max(1, retryTimes);
        long safeSleep = Math.max(50L, sleepMs);
        long lastCount = -1;
        long lastMtime = -1;
        for (int i = 0; i < safeRetries; i++) {
            DirMetrics metrics = collectDirMetrics(root);
            if (metrics.fileCount() == lastCount && metrics.latestMtime() == lastMtime) {
                return;
            }
            lastCount = metrics.fileCount();
            lastMtime = metrics.latestMtime();
            // 当非最后一次重试, 每次停下来0.15秒
            if (i < safeRetries - 1) {
                try {
                    Thread.sleep(safeSleep);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 统计目录文件数与最新修改时间用于稳定性判定。
     *
     * @param root 代码目录
     * @return 目录指标；目录不存在或不可读时返回 (0, 0)
     */
    public static DirMetrics collectDirMetrics(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new DirMetrics(0, 0);
        }
        long count = 0;
        long maxMtime = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            count = files.size();
            for (Path path : files) {
                try {
                    long mtime = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
                    if (mtime > maxMtime) {
                        maxMtime = mtime;
                    }
                } catch (IOException ignore) {
                    // 单文件失败忽略
                }
            }
        } catch (Exception ignore) {
            // 目录不可读时返回默认值
        }
        return new DirMetrics(count, maxMtime);
    }
}
