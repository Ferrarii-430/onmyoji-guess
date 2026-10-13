package com.example.onmyojiguess.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.onmyojiguess.model.AiVerdict;
import com.example.onmyojiguess.model.FeedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 兜底判定实现（V4.1-Flash，经 Spring AI OpenAI 兼容协议接入）
 * <p>
 * app.ai.provider=deepseek (默认) 时生效。原 AiGuessJudgeService 逻辑原样保留。
 * <p>
 * 降低 token 消耗的手段：
 * 1. 仅本地规则无法判定、且含 红/蓝/左/右 关键字的候选帖才送 AI（调用方负责）
 * 2. 每个帖子正文截断至 {@link #MAX_TEXT_LEN} 字符，换行压平
 * 3. 多帖合并为一次请求（每批 {@link #BATCH_SIZE} 条），系统提示词固定前缀可命中 DeepSeek 上下文缓存
 * 4. 帖子判定结果入库去重，每个 feed 最多送 AI 一次（调用方负责）
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "deepseek", matchIfMissing = true)
public class DeepSeekJudgeService implements AiGuessJudge {

    private static final int BATCH_SIZE = 10;
    /** 正文截断上限, 全局配置 app.ai.max-text-len 对所有 AI 实现通用 */
    private final int maxTextLen;

    /**
     * 分步判定提示词 (参考 StartLux-Decision 的 is_related -> has_side_choice -> side -> round 阶梯过滤):
     * 固定前缀可命中 DeepSeek 上下文缓存, 加长不显著增加成本。
     */
    private static final String SYSTEM_PROMPT = """
            你是"阴阳师对弈竞猜"押注帖判定器。每天10:00~24:00每2小时一局: 第1场=10点场、第2场=12点场……第7场=22点场(第N场开始小时=8+2N)。
            押注方向: 左(红)=RED, 右(蓝)=BLUE。

            对每个帖子按以下顺序分步判定, 任何一步不通过即 is_bet=false, 后续步骤不再考虑:
            第1步 is_related: 内容是否与阴阳师对弈竞猜活动相关? 闲聊、其他游戏、活动推广、年度报告等无关内容 -> is_bet=false
            第2步 has_side_choice: 是否包含对未来某局押左(红)或右(蓝)的建议、分析或预测?
                 警惕这些陷阱——它们只是"提及"红蓝/结果, 不是押注建议 -> is_bet=false:
                 - 赛果回顾: "上局 右(蓝)竞猜成功""昨晚蓝方赢了"
                 - 历史战绩统计: "总战绩32胜17负, 总胜率65.3%""今天7胜0负"
                 - 纯感想、致谢、求关注、闲聊
            第3步 side: 按帖子表达的方向定 RED/BLUE, "押翻盘 左(红)""90%+"等附加文字不影响方向本身
            第4步 round: 场次识别, 三种写法:
                 - 时间: "18:00"即18点场=第5场, 14:00=第3场, 22:00=第7场
                 - 序数: "第N场""今天第3场""第三场"直接给出场次
                 - 均未出现: 从上下文推断, 推断不出填 null (round_no=null 不影响 is_bet=true)

            判定示例:
            - "【对弈竞猜体验服】最后一天 18:00 押翻盘 左(红)" -> is_bet=true, side=RED, round_no=5
            - "第三场 右(蓝) 90%+" -> is_bet=true, side=BLUE, round_no=3
            - "16:00 左(红) 上局 右(蓝)竞猜成功" -> is_bet=true, side=RED, round_no=4 (上局回顾不是本次押注)
            - "7胜0负完美答卷 总胜率65.3% 死皮赖脸求关注" -> is_bet=false (战绩统计与感想)
            - "【拾光回忆录】十周年年度报告 传送门..." -> is_bet=false (无关内容)

            只输出JSON, 不要输出任何其他文字, 格式固定:
            {"results":[{"id":"帖子id原样返回","is_bet":true,"side":"RED或BLUE或null","round_no":1到7的整数或null}]}
            is_bet为false时side和round_no均为null。
            """;

    private final ChatClient chatClient;

    public DeepSeekJudgeService(
            ChatClient.Builder chatClientBuilder,
            @Value("${app.ai.max-text-len:3000}") int maxTextLen) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
        this.maxTextLen = maxTextLen;
    }

    /**
     * 批量判定候选帖
     *
     * @return feedId -> 判定结果；AI 未返回的 feedId 不在 map 中（由调用方保持其未判定状态，下轮重试）
     */
    @Override
    public Map<String, AiVerdict> judge(List<FeedData> candidates) {
        Map<String, AiVerdict> verdicts = new HashMap<>();
        for (int from = 0; from < candidates.size(); from += BATCH_SIZE) {
            List<FeedData> batch = candidates.subList(from, Math.min(from + BATCH_SIZE, candidates.size()));
            try {
                String reply = chatClient.prompt()
                        .user(buildUserPrompt(batch))
                        .call()
                        .content();
                parseReply(reply, batch, verdicts);
            } catch (Exception e) {
                log.error("AI 判定批次失败, feedIds={}", batch.stream().map(FeedData::getFeedId).toList(), e);
            }
        }
        return verdicts;
    }

    /**
     * 统一数据格式: 每帖为 "#序号 id=<feedId>\n<压平后的正文>", 以 --- 分隔
     */
    private String buildUserPrompt(List<FeedData> batch) {
        StringBuilder sb = new StringBuilder("待判定帖子:\n");
        for (int i = 0; i < batch.size(); i++) {
            FeedData feed = batch.get(i);
            sb.append("#").append(i + 1).append(" id=").append(feed.getFeedId()).append('\n')
              .append(AiGuessJudge.prepareText(feed.getText(), maxTextLen));
            if (i < batch.size() - 1) {
                sb.append("\n---\n");
            }
        }
        return sb.toString();
    }

    private void parseReply(String reply, List<FeedData> batch, Map<String, AiVerdict> verdicts) {
        if (reply == null || reply.isBlank()) {
            log.warn("AI 返回为空, feedIds={}", batch.stream().map(FeedData::getFeedId).toList());
            return;
        }
        try {
            // 防止模型输出 ```json 代码围栏等杂讯
            int start = reply.indexOf('{');
            int end = reply.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalStateException("响应中未找到 JSON: " + abbreviate(reply));
            }
            JSONObject root = JSON.parseObject(reply.substring(start, end + 1));
            JSONArray results = root.getJSONArray("results");
            if (results == null) {
                throw new IllegalStateException("响应缺少 results 字段: " + abbreviate(reply));
            }
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                String feedId = item.getString("id");
                if (feedId == null || batch.stream().noneMatch(f -> f.getFeedId().equals(feedId))) {
                    continue;
                }
                AiVerdict verdict = new AiVerdict();
                verdict.setFeedId(feedId);
                verdict.setBet(item.getBooleanValue("is_bet"));
                verdict.setSide(item.getString("side"));
                Integer roundNo = item.getInteger("round_no");
                verdict.setRoundNo(roundNo != null && roundNo >= 1 && roundNo <= 7 ? roundNo : null);
                verdicts.put(feedId, verdict);
            }
        } catch (Exception e) {
            log.error("AI 响应解析失败: {}", e.getMessage(), e);
        }
    }

    private String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
