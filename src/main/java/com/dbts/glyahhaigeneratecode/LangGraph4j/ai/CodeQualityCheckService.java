package com.dbts.glyahhaigeneratecode.LangGraph4j.ai;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 代码质量检查服务：调用 {@link CodeExamService} 并将模型返回的 JSON 解析为 {@link QualityResult}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeQualityCheckService {

    private final CodeExamService codeExamService;

    /**
     * 对拼接后的源码内容做 AI 质检。
     *
     * @param codeContent 待检查代码文本（多文件拼接）
     * @return 解析后的结果；解析失败时按策略视为通过（与节点内「异常不阻断」一致）
     */
    public QualityResult checkCodeQuality(String codeContent) {
        QualityResult qualityResult = codeExamService.examineCode(codeContent);
        return normalizeQualityResult(qualityResult);
    }

    private static QualityResult normalizeQualityResult(QualityResult qualityResult) {
        if (qualityResult == null) {
            return QualityResult.builder()
                    .isValid(true)
                    .errors(Collections.emptyList())
                    .suggestions(List.of("AI 返回为空，已视为通过"))
                    .build();
        }
        // 补全缺失字段
        if (qualityResult.getIsValid() == null) {
            qualityResult.setIsValid(Boolean.TRUE);
        }
        if (qualityResult.getErrors() == null) {
            qualityResult.setErrors(Collections.emptyList());
        }
        if (qualityResult.getSuggestions() == null) {
            qualityResult.setSuggestions(Collections.emptyList());
        }
        return qualityResult;
    }
}
