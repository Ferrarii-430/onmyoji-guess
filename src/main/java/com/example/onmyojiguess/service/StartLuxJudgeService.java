package com.example.onmyojiguess.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.onmyojiguess.model.AiVerdict;
import com.example.onmyojiguess.model.FeedData;
import com.example.onmyojiguess.model.GuessInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StartLux-Decision 类型化决策模型判定实现
 * <p>
 * app.ai.provider=startlux 时生效。参考文档: StartLux-Decision TypeSafe /v1/systemone 协议。
 * <p>
 * 特性与约束:
 * <ul>
 *   <li>每次请求只判定一个 feed (协议限制), 毫秒级前向传播, 请求间留少量间隔</li>
 *   <li>三个类型化问题: is_related(noul 相关性) / has_side_choice(noul 是否含方向建议) /
 *       side(choice 红/蓝) + round(choice 第几场, 1~7/unknown)</li>
 *   <li>置信度过滤: 相关性 &lt; 0.5、无方向建议、或方向置信度低于
 *       {@code app.ai.startlux.min-confidence} 时判为非押注贴</li>
 *   <li>纯文本决策, 不使用 images 字段</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "startlux")
public class StartLuxJudgeService implements AiGuessJudge {

    private static final String DECIDE_PATH = "/v1/systemone";
    /** 相邻请求间隔 500ms (顺序执行, 不并发) */
    private static final long REQUEST_GAP_MS = 500L;
    /** 相关性 noul 阈值: is_related 低于此值判为无关 */
    private static final double RELATED_THRESHOLD = 0.5;
    /** 方向建议 noul 阈值: has_side_choice 低于此值判为非押注贴 */
    private static final double SIDE_HINT_THRESHOLD = 0.5;

    /** 正文截断上限, 全局配置 app.ai.max-text-len 对所有 AI 实现通用 */
    private final int maxTextLen;

    private final RestClient restClient;
    private final double minConfidence;

    public StartLuxJudgeService(
            @Value("${app.ai.startlux.base-url:http://192.168.1.46:8090}") String baseUrl,
            @Value("${app.ai.startlux.min-confidence:0.55}") double minConfidence,
            @Value("${app.ai.max-text-len:3000}") int maxTextLen) {
        this.minConfidence = minConfidence;
        this.maxTextLen = maxTextLen;
        // 连接 10s / 读取 60s: 模型服务偶发繁忙时留足等待时间
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Map<String, AiVerdict> judge(List<FeedData> candidates) {
        Map<String, AiVerdict> verdicts = new HashMap<>();
        boolean first = true;
        for (FeedData feed : candidates) {
            if (!first) {
                sleepGap();
            }
            first = false;
            try {
                String body = restClient.post()
                        .uri(DECIDE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(JSON.toJSONString(buildRequest(feed)))
                        .retrieve()
                        .body(String.class);
                AiVerdict verdict = parse(feed.getFeedId(), body);
                if (verdict != null) {
                    verdicts.put(feed.getFeedId(), verdict);
                }
            } catch (Exception e) {
                log.error("StartLux 判定失败, feedId={}", feed.getFeedId(), e);
            }
        }
        return verdicts;
    }

    /**
     * 组装类型化决策请求: state=正文, questions=相关性/方向建议/方向/场次
     * <p>
     * 注意: 请求体必须预先序列化为字符串再发送——直接传 Map 会走流式序列化,
     * 请求体以 chunked 编码传输, 而 StartLux 服务的 Python BaseHTTPServer 只按
     * Content-Length 读取, 会收到空 body 报 422 "Expecting value: char 0"。
     */
    private Map<String, Object> buildRequest(FeedData feed) {
        Map<String, Object> questions = new LinkedHashMap<>();

        questions.put("is_related", Map.of(
                "type", "noul",
                "instructions", "以上文字内容是否与阴阳师对弈竞猜活动有关？"));

        questions.put("has_side_choice", Map.of(
                "type", "noul",
                "instructions", "以上文字内容是否包含对左边（红色）或右边（蓝色）的选择建议、分析或预测？"));

        Map<String, Object> side = new LinkedHashMap<>();
        side.put("type", "choice");
        side.put("instructions", "如果以上文字包含对左/右的选择建议，请判断应该押左边（红色）还是右边（蓝色）？");
        side.put("criteria", Map.of("red", "左边（红色）", "blue", "右边（蓝色）"));
        questions.put("side", side);

        Map<String, Object> roundCriteria = new LinkedHashMap<>();
        roundCriteria.put("1", "第1场(10点)");
        roundCriteria.put("2", "第2场(12点)");
        roundCriteria.put("3", "第3场(14点)");
        roundCriteria.put("4", "第4场(16点)");
        roundCriteria.put("5", "第5场(18点)");
        roundCriteria.put("6", "第6场(20点)");
        roundCriteria.put("7", "第7场(22点)");
        roundCriteria.put("unknown", "无法确定或未提及");
        Map<String, Object> round = new LinkedHashMap<>();
        round.put("type", "choice");
        round.put("instructions", "如果以上文字包含对某一场对弈竞猜的押注建议，请判断针对第几场？（每天10:00~24:00每2小时一局：第1场=10点、第2场=12点、第3场=14点、第4场=16点、第5场=18点、第6场=20点、第7场=22点）");
        round.put("criteria", roundCriteria);
        questions.put("round", round);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("state", AiGuessJudge.prepareText(feed.getText(), maxTextLen));
        request.put("questions", questions);
        return request;
    }

    /**
     * 解析概率决策结果 -> AiVerdict
     * <p>
     * 判定链: 相关性不足 -> 非押注贴; 无方向建议 -> 非押注贴;
     * 方向置信度低于阈值 -> 不可信, 判为非押注贴(下轮不重试, 保持保守);
     * 否则押注贴, 方向取 side.choice, 场次取 round.choice(unknown 时为 null, 不写入竞猜表只标记已判定)。
     */
    private AiVerdict parse(String feedId, String body) {
        JSONObject root = JSON.parseObject(body);
        JSONObject answers = root == null ? null : root.getJSONObject("answers");
        if (answers == null) {
            log.warn("StartLux 响应缺少 answers, feedId={}, body={}", feedId, abbreviate(body));
            return null;
        }

        AiVerdict verdict = new AiVerdict();
        verdict.setFeedId(feedId);

        double related = noul(answers, "is_related");
        if (related < RELATED_THRESHOLD) {
            verdict.setBet(false);
            log.debug("[{}] StartLux 判定: 非押注贴 (相关性 {} < {})", feedId, round(related), RELATED_THRESHOLD);
            return verdict;
        }

        double hasSide = noul(answers, "has_side_choice");
        if (hasSide < SIDE_HINT_THRESHOLD) {
            verdict.setBet(false);
            log.debug("[{}] StartLux 判定: 非押注贴 (无方向建议 {} < {})", feedId, round(hasSide), SIDE_HINT_THRESHOLD);
            return verdict;
        }

        JSONObject side = answers.getJSONObject("side");
        String choice = side == null ? null : side.getString("choice");
        double confidence = side == null ? 0 : side.getDoubleValue("confidence");
        boolean red = "red".equalsIgnoreCase(choice);
        if (!red && !"blue".equalsIgnoreCase(choice)) {
            verdict.setBet(false);
            log.warn("[{}] StartLux side.choice 异常: {}", feedId, choice);
            return verdict;
        }
        if (confidence < minConfidence) {
            verdict.setBet(false);
            log.info("[{}] StartLux 判定: 方向置信度 {} 低于阈值 {}, 保守判为非押注贴 (red={}, blue={})",
                    feedId, round(confidence), minConfidence,
                    round(side == null ? 0 : side.getJSONObject("probabilities").getDoubleValue("red")),
                    round(side == null ? 0 : side.getJSONObject("probabilities").getDoubleValue("blue")));
            return verdict;
        }

        verdict.setBet(true);
        verdict.setSide(red ? GuessInfo.SIDE_RED : GuessInfo.SIDE_BLUE);

        JSONObject round = answers.getJSONObject("round");
        String roundChoice = round == null ? null : round.getString("choice");
        try {
            int roundNo = Integer.parseInt(roundChoice);
            if (roundNo >= 1 && roundNo <= 7) {
                verdict.setRoundNo(roundNo);
            }
        } catch (NumberFormatException ignore) {
            // unknown 或缺失: 押注方向有效但场次无法确定, 不写入竞猜表
        }

        log.info("[{}] StartLux 判定: 押注贴 side={} round={} (conf={}, related={}, hasSide={})",
                feedId, verdict.getSide(), verdict.getRoundNo(), round(confidence), round(related), round(hasSide));
        return verdict;
    }

    private double noul(JSONObject answers, String key) {
        JSONObject answer = answers.getJSONObject(key);
        return answer == null ? 0 : answer.getDoubleValue("noul");
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private void sleepGap() {
        try {
            Thread.sleep(REQUEST_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
