package com.example.onmyojiguess.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.onmyojiguess.model.FeedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 网易大神动态抓取客户端
 */
@Slf4j
@Component
public class DsFeedClient {

    private static final String BASE_URL = "https://inf.ds.163.com";
    private static final String FEED_PATH = "/v1/web/feed/basic/getSomeOneFeeds";
    private static final String FEED_TYPES = "1,2,3,4,6,7,10,11";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 网络异常最大重试次数 */
    private static final int MAX_RETRIES = 3;
    /** 重试间隔基数(毫秒), 实际间隔 = 基数 * 重试轮数 */
    private static final long RETRY_BACKOFF_MS = 2000;

    private final RestClient restClient;
    private final GuessParser guessParser;

    public DsFeedClient(GuessParser guessParser) {
        this.guessParser = guessParser;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .defaultHeader(HttpHeaders.REFERER, "https://ds.163.com/")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .build();
    }

    /**
     * 抓取指定用户的最新动态并解析竞猜信息
     */
    public List<FeedData> fetchFeeds(String userName, String uid) {
        String body = fetchFeedBodyWithRetry(userName, uid);

        JSONObject root = JSON.parseObject(body);
        if (root == null || root.getIntValue("code") != 200) {
            log.warn("[{}] 接口返回异常, uid={}, body={}", userName, uid,
                    body == null ? "null" : body.substring(0, Math.min(body.length(), 200)));
            return List.of();
        }
        JSONObject result = root.getJSONObject("result");
        if (result == null) {
            return List.of();
        }
        JSONArray feeds = result.getJSONArray("feeds");
        if (feeds == null || feeds.isEmpty()) {
            log.info("[{}] 无动态数据, uid={}", userName, uid);
            return List.of();
        }

        List<FeedData> list = new ArrayList<>();
        for (int i = 0; i < feeds.size(); i++) {
            FeedData data = convert(userName, uid, feeds.getJSONObject(i));
            if (data != null) {
                data.setGuesses(guessParser.parse(data));
                if (!data.getGuesses().isEmpty()) {
                    // 本地规则已确认有效竞猜
                    data.setIsGuess(true);
                } else if (!guessParser.isCandidate(data.getText())) {
                    // 不含 红/蓝/左/右 关键字, 本地确认无关
                    data.setIsGuess(false);
                }
                // 其余为候选帖 (isGuess=null), 交由 AI 判定
                data.setResultHint(guessParser.parseRoundResultHint(data));
                list.add(data);
            }
        }
        log.info("[{}] 抓取动态 {} 条, 本地确认竞猜 {} 条, 待AI判定 {} 条", userName, list.size(),
                list.stream().filter(f -> Boolean.TRUE.equals(f.getIsGuess())).count(),
                list.stream().filter(f -> f.getIsGuess() == null).count());
        return list;
    }

    /**
     * 请求动态接口, 网络异常(连接超时/IO错误)时自动重试
     */
    private String fetchFeedBodyWithRetry(String userName, String uid) {
        ResourceAccessException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return restClient.get()
                        .uri(FEED_PATH + "?feedTypes=" + FEED_TYPES + "&someOneUid={uid}", uid)
                        .retrieve()
                        .body(String.class);
            } catch (ResourceAccessException e) {
                lastException = e;
                log.warn("[{}] 请求失败(第 {}/{} 次), uid={}, err={}",
                        userName, attempt, MAX_RETRIES, uid, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
                }
            }
        }
        log.error("[{}] 请求重试 {} 次仍失败, uid={}", userName, MAX_RETRIES, uid, lastException);
        return null;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private FeedData convert(String userName, String uid, JSONObject feed) {
        // 已删除的动态跳过
        if (feed.getLongValue("deleteTime") > 0) {
            return null;
        }
        FeedData data = new FeedData();
        data.setFeedId(feed.getString("id"));
        data.setUserName(userName);
        data.setUid(uid);
        data.setFeedType(feed.getInteger("type"));
        data.setCreateTime(toDateTime(feed.getLong("createTime")));
        data.setUpdateTime(toDateTime(feed.getLong("updateTime")));
        JSONObject ipInfo = feed.getJSONObject("displayIpInfo");
        if (ipInfo != null) {
            data.setIpLocation(ipInfo.getString("ipLocationName"));
        }

        // content 是内嵌 JSON 字符串，取 body.text 正文
        String contentStr = feed.getString("content");
        if (contentStr != null) {
            try {
                JSONObject content = JSON.parseObject(contentStr);
                JSONObject body = content.getJSONObject("body");
                if (body != null) {
                    data.setText(body.getString("text"));
                }
            } catch (Exception e) {
                log.warn("content 解析失败, feedId={}, err={}", data.getFeedId(), e.getMessage());
            }
        }
        return data;
    }

    private LocalDateTime toDateTime(Long epochMillis) {
        return epochMillis == null ? null
                : Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDateTime();
    }
}
