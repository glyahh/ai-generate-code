package com.dbts.glyahhaigeneratecode.constant;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 历史 AI 文本写入 ChatMemory 前按长度与 fenced 代码块做截断与占位 ->
 * 从正文剥离 workflow 阶段行并拼接阶段状态串 ->
 * 用语义关键字与各阶段失败正则判断成功证据或失败阶段，供对话压缩与前端展示消费。
 */
public interface ChatHistoryMemoryCompactionConstant {

    // 1. 单条 AI 记忆正文允许的最大字符数,超过后在灌入 Redis/压缩路径触发摘要或截断逻辑
    int MEMORY_AI_MESSAGE_MAX_LENGTH = 2400;

    /**
     * 超过此长度后固定截断（不走额外 AI 总结调用）
     */
    // 2. fenced 代码块（html/css/js）在超长时保留的字符上限,避免 Redis 上下文撑爆
    int MEMORY_AI_CODE_BLOCK_KEEP_LENGTH = 2200;

    /** Redis/DB 中 AI 正文缺失时写入 ChatMemory 的占位，避免 LangChain4j {@link dev.langchain4j.data.message.AiMessage} 拒绝 null。 */
    // 3. 空 AI 文本时写入记忆的占位字符串,保证 LangChain4j 不因 null 文本拒绝构造 AiMessage
    String EMPTY_AI_MEMORY_PLACEHOLDER = "（历史AI消息为空）";

    // 4. 匹配 markdown 中 ```html 代码块正文的正则,用于从长回复中提取 HTML 片段再按上限截断
    Pattern HTML_BLOCK_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    // 5. 匹配 ```css 代码块正文的正则,用于抽取样式片段参与记忆侧压缩
    Pattern CSS_BLOCK_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    // 6. 匹配 ```js / ```javascript 代码块正文的正则,用于抽取脚本片段参与记忆侧压缩
    Pattern JS_BLOCK_PATTERN = Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    // 9. 工作流阶段状态行的固定前缀,与 SSE/落库文本中的阶段广播行对齐
    String WORKFLOW_STAGE_STATUS_PREFIX = "[workflow_stage_status]";

    // 10. 按行匹配并删除整条 workflow 阶段状态行的正则,用于清洗展示给用户的历史正文
    Pattern WORKFLOW_STAGE_STATUS_LINE_PATTERN = Pattern.compile("(?m)^\\[workflow_stage_status\\].*$");

    // 11. 判定「疑似失败」时扫描的中英关键字列表,命中后结合阶段正则缩小失败归因范围
    List<String> WORKFLOW_FAILURE_KEYWORDS = List.of(
            "失败", "报错", "error", "异常", "中断", "超时", "failed", "exception", "timeout"
    );

    // 12. 判定工作流已成功产出时的正向证据短语列表,用于在未见失败关键词时推断成功落点
    List<String> WORKFLOW_SUCCESS_EVIDENCE_KEYWORDS = List.of(
            "代码已生成完毕", "写入文件", "项目已生成完毕", "代码生成完成", "工作流结束，生成完成"
    );

    // 13. 各 workflow 阶段 key 与「该阶段失败」语义正则的映射,用于从一段长文本中定位最先失败阶段
    Map<String, Pattern> WORKFLOW_STAGE_FAILURE_PATTERNS = Map.of(
            "initializing", Pattern.compile("(初始化|提示词增强|开始准备).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(初始化|提示词增强|开始准备)", Pattern.CASE_INSENSITIVE),
            "image_collecting", Pattern.compile("(图片|图像|插画|logo|架构图|mermaid).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(图片|图像|插画|logo|架构图|mermaid)", Pattern.CASE_INSENSITIVE),
            "code_generating", Pattern.compile("(代码生成|项目构建|生成代码|写入文件|写文件|创建文件).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(代码生成|项目构建|生成代码|写入文件|写文件|创建文件)", Pattern.CASE_INSENSITIVE),
            "code_checking", Pattern.compile("(代码检查|质量检查|代码质检|lint|测试).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(代码检查|质量检查|代码质检|lint|测试)", Pattern.CASE_INSENSITIVE),
            "ready", Pattern.compile("(就绪|完成|结束).{0,40}(失败|报错|error|异常|中断|超时)|(失败|报错|error|异常|中断|超时).{0,40}(就绪|完成|结束)", Pattern.CASE_INSENSITIVE)
    );
}
