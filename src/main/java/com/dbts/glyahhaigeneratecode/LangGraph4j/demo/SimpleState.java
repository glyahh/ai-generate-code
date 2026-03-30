package com.dbts.glyahhaigeneratecode.LangGraph4j.demo;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channels;
import org.bsc.langgraph4j.state.Channel;

import java.util.*;

// 定义我们图的状态
class SimpleState extends AgentState {
    public static final String MESSAGES_KEY = "messages";

    // 定义状态的模式。
    // MESSAGES_KEY会包含字符串列表，并会添加新消息。
    // 可以存储不同类型的数据在状态通道中
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            MESSAGES_KEY, Channels.appender(ArrayList::new)
    );

    public SimpleState(Map<String, Object> initData) {
        super(initData);
    }

    public List<String> messages() {
        return this.<List<String>>value(MESSAGES_KEY).orElseGet(List::of);
    }
}