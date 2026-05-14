package com.dbts.glyahhaigeneratecode.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 计划任务（{@link org.springframework.scheduling.annotation.Scheduled}）。
 * <p>
 * 例如 {@link com.dbts.glyahhaigeneratecode.service.impl.ConversationMemoryStateServiceImpl} 中的定时清理；
 * 与 DDL 建表无关（会话记忆建表请执行 {@code sql/conversation_memory_tables.sql}）。
 * </p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
