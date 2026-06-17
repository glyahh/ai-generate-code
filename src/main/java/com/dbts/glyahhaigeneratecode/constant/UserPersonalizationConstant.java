package com.dbts.glyahhaigeneratecode.constant;

import java.time.Duration;

/**
 * 用户个性化配置常量。
 * 含 Redis 双 key 前缀、缓存 TTL 与防护参数、注入标签。
 *
 * @author glyahh
 */
public final class UserPersonalizationConstant {

    private UserPersonalizationConstant() {}

    /** 应用风格 Redis key 前缀：user:favourite:app:{userId} */
    public static final String REDIS_KEY_APP = "user:favourite:app:";

    /** 回答风格 Redis key 前缀：user:favourite:style:{userId} */
    public static final String REDIS_KEY_STYLE = "user:favourite:style:";

    /** 正常缓存 TTL：2 小时 */
    public static final long CACHE_TTL_SECONDS = Duration.ofHours(2).toSeconds();

    /** 随机抖动上限：10 分钟（防雪崩） */
    public static final long CACHE_TTL_JITTER_SECONDS = Duration.ofMinutes(10).toSeconds();

    /** 空值占位 TTL：60 秒（防穿透） */
    public static final long CACHE_NULL_TTL_SECONDS = 60;

    /** 空值缓存占位符 */
    public static final String CACHE_NULL_PLACEHOLDER = "{}";

    /** prompt 字段最大字符数 */
    public static final int PROMPT_MAX_LENGTH = 2000;

    /** 注入标签：应用风格 */
    public static final String INJECT_TAG_APP_STYLE = "[user_app_style]";

    /** 注入标签：回答风格 */
    public static final String INJECT_TAG_ANSWER_STYLE = "[user_answer_style]";
}