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
     * cm:page:* TTL（秒），默认 12 小时。
     */
    private long pageTtlSeconds = 12L * 3600L;

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
}

