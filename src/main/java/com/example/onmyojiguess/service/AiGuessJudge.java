package com.example.onmyojiguess.service;

import com.example.onmyojiguess.model.AiVerdict;
import com.example.onmyojiguess.model.FeedData;

import java.util.List;
import java.util.Map;

/**
 * 竞猜 AI 判定接口
 * <p>
 * 通过配置 {@code app.ai.provider} 切换实现:
 * <ul>
 *   <li>deepseek (默认): DeepSeek V4.1-Flash, OpenAI 兼容协议, 支持多帖批量判定</li>
 *   <li>startlux: StartLux-Decision 类型化决策模型, 单帖请求, 返回校准概率</li>
 * </ul>
 */
public interface AiGuessJudge {

    /**
     * 批量判定候选帖
     *
     * @return feedId -> 判定结果; AI 未能判定的 feedId 不在 map 中 (由调用方保持其未判定状态, 下轮重试)
     */
    Map<String, AiVerdict> judge(List<FeedData> candidates);

    /**
     * 统一发送给模型的文本预处理: 压平换行 + 截断
     */
    static String prepareText(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }
}
