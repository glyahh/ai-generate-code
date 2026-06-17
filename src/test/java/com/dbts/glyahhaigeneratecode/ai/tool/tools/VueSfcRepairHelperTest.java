package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import com.dbts.glyahhaigeneratecode.ai.tool.tool_assist.VueSfcRepairHelper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * VueSfcRepairHelper 的单元测试。
 * 测试 Vue 单文件组件（SFC）的标签平衡修复逻辑。
 */
class VueSfcRepairHelperTest {

    @Test
    void repairVueSfcContent_shouldAppendMissingTemplateClosingTag() {
        String input = "<template>\n  <div>Hello</div>\n";
        String result = VueSfcRepairHelper.repairVueSfcContent(input);
        assertTrue(result.contains("</template>"), "应补上缺失的 </template>");
        assertEquals(1, countOccurrences(result, "<template"), "应恰好有一个 <template");
        assertEquals(1, countOccurrences(result, "</template>"), "应恰好有一个 </template>");
    }

    @Test
    void repairVueSfcContent_shouldAppendMissingScriptClosingTag() {
        String input = "<template><div>A</div></template>\n<script setup>\nconst x = 1;\n";
        String result = VueSfcRepairHelper.repairVueSfcContent(input);
        assertTrue(result.contains("</script>"), "应补上缺失的 </script>");
    }

    @Test
    void repairVueSfcContent_shouldAppendMissingStyleClosingTag() {
        String input = "<template><div>A</div></template>\n<script setup>const x=1;</script>\n<style scoped>\nbody { color: red; }\n";
        String result = VueSfcRepairHelper.repairVueSfcContent(input);
        assertTrue(result.contains("</style>"), "应补上缺失的 </style>");
    }

    @Test
    void repairVueSfcContent_shouldRemoveExcessClosingTags() {
        String input = "<template><div>A</div></template>\n</template>\n</template>\n" +
                "<script setup>const x=1;</script>\n</script>\n";
        String result = VueSfcRepairHelper.repairVueSfcContent(input);
        assertEquals(1, countOccurrences(result, "</template>"), "多余的 </template> 应被移除");
        assertEquals(1, countOccurrences(result, "</script>"), "多余的 </script> 应被移除");
        assertEquals(0, countOccurrences(result, "</style>"), "不应有 </style>");
    }

    @Test
    void repairVueSfcContent_shouldHandleNullAndEmpty() {
        assertNull(VueSfcRepairHelper.repairVueSfcContent(null), "null 应返回 null");
        assertEquals("", VueSfcRepairHelper.repairVueSfcContent(""), "空字符串应返回空字符串");
    }

    @Test
    void repairVueSfcContent_shouldHandleAlreadyBalancedContent() {
        String input = "<template>\n  <div>OK</div>\n</template>\n<script setup>\nconst x = 1;\n</script>\n<style>\nbody {}\n</style>\n";
        String result = VueSfcRepairHelper.repairVueSfcContent(input);
        assertEquals(input.stripTrailing(), result.stripTrailing(), "已平衡的内容不应被修改");
    }

    @Test
    void repairVueSfcContent_shouldStripTrailingWhitespace() {
        String input = "<template><div>X</div></template>  \n  \n";
        String result = VueSfcRepairHelper.repairVueSfcContent(input);
        assertEquals(input.stripTrailing(), result);
    }

    // ---------- helpers ----------

    private int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
