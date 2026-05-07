package com.dbts.glyahhaigeneratecode.LangGraph4j.ai;

import com.dbts.glyahhaigeneratecode.LangGraph4j.state.QualityResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

        // 白名单降级
        // 模型偶发把"非阻断项"写到 errors 字段（如 package.json 缺 "type":"module"），
        if (qualityResult.getErrors() != null && !qualityResult.getErrors().isEmpty()) {
            // 实际错误
            List<String> realErrors = new ArrayList<>();
            // 降级错误(建议项)
            List<String> downgraded = new ArrayList<>();

            for (String err : qualityResult.getErrors()) {
                if (isNonBlockingError(err)) {
                    downgraded.add("[降级建议] " + err);
                } else {
                    realErrors.add(err);
                }
            }

            // 如果降级错误不为空，则将降级错误添加到 suggestions 中，并从 errors 中移除
            if (!downgraded.isEmpty()) {
                List<String> merged = new ArrayList<>(qualityResult.getSuggestions());
                merged.addAll(downgraded);
                qualityResult.setSuggestions(merged);
                qualityResult.setErrors(realErrors);
                if (realErrors.isEmpty()) qualityResult.setIsValid(Boolean.TRUE);
            }
        }

        // 模型偶发 isValid=true 仍附带非阻断项到 errors；统一并入 suggestions，避免下游误判
        if (Boolean.TRUE.equals(qualityResult.getIsValid()) && !qualityResult.getErrors().isEmpty()) {
            List<String> merged = new ArrayList<>(qualityResult.getSuggestions());
            merged.addAll(qualityResult.getErrors());
            qualityResult.setSuggestions(merged);
            qualityResult.setErrors(Collections.emptyList());
        }
        return qualityResult;
    }

    /**
     * 判断错误是否属于非阻断错误
     * 将不会直接报错的错误降级为建议项
     * @param err
     * @return
     */
    private static boolean isNonBlockingError(String err) {
        if (err == null) return false;
        String lower = err.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("type") && lower.contains("module")) return true;
        if (lower.contains("readme") || lower.contains("favicon")) return true;
        if (lower.contains("seo") || lower.contains("meta")) return true;
        if (lower.contains("comment") || lower.contains("注释")) return true;
        if (lower.contains("naming") || lower.contains("命名")) return true;
        if (lower.contains("a11y") || lower.contains("accessibility")) return true;
        return false;
    }
}
