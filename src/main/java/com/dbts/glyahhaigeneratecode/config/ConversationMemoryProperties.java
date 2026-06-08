package com.dbts.glyahhaigeneratecode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "conversation.memory")
public class ConversationMemoryProperties {

    /**
     * cm:state:{appId} TTL（秒），默认 14 天。
     */
    private long stateTtlSeconds = 14L * 24L * 3600L;

    /**
     * cm:ref:{refId} TTL（秒），默认 3 天。
     */
    private long refTtlSeconds = 3L * 24L * 3600L;

    /**
     * 每个 app 保留最近 ref 条数上限。
     */
    private int refKeepCountPerApp = 500;

    /**
     * 每个 app 保留最近天数。
     */
    private int refKeepDaysPerApp = 30;

    /**
     * 每个 app 的 ref 总字节数上限。
     */
    private long refKeepBytesPerApp = 200L * 1024L * 1024L;

    /**
     * manifest 稳定性检查重试次数。
     */
    private int manifestStableRetryTimes = 3;

    /**
     * manifest 稳定性检查重试间隔（毫秒）。
     */
    private long manifestStableRetrySleepMs = 150L;

    /**
     * 是否启用工具写盘后的 fileNote 批量摘要。
     */
    private boolean fileNoteEnabled = true;

    /**
     * 写盘后 debounce 窗口（毫秒）；权威收口仍以 onRoundCompleted 同步 flush 为准。
     */
    private long fileNoteDebounceMs = 400L;

    /**
     * 单轮参与摘要的路径数上限。
     */
    private int fileNoteMaxPathsPerRound = 40;

    /**
     * 无 diff 时读盘截断字符数（仅用于摘要模型输入）。
     */
    private int fileNoteInputChars = 2000;

    /**
     * 单路径 note 最大字符数（落库硬截断）。
     */
    private int fileNoteMaxNoteChars = 200;

    /**
     * LangChain4j ChatMemory（memoryId=appId）TTL（秒），仅 AI 轨。
     */
    private long chatTtlSeconds = 600L;

    /**
     * 用户回显全文缓存 chat:echo_memory:{appId} TTL（秒）。
     */
    private long echoMemoryTtlSeconds = 3600L;
}

