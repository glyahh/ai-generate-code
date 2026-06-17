package com.dbts.glyahhaigeneratecode.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatHistoryEchoRedisSupportTest {

    @Test
    void echoMemoryKeyPrefix_constant() {
        assertEquals("chat:echo_memory:", ChatHistoryEchoRedisSupport.ECHO_MEMORY_KEY_PREFIX);
    }
}
