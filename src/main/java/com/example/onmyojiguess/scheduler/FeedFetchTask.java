package com.example.onmyojiguess.scheduler;

import com.example.onmyojiguess.config.Config;
import com.example.onmyojiguess.model.AiVerdict;
import com.example.onmyojiguess.model.FeedData;
import com.example.onmyojiguess.model.RoundResultHint;
import com.example.onmyojiguess.service.AiGuessJudge;
import com.example.onmyojiguess.service.DsFeedClient;
import com.example.onmyojiguess.service.FeedStorageService;
import com.example.onmyojiguess.service.GuessQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时抓取大神动态（竞猜场次感知调度）
 * <p>
 * 竞猜周期: 每天 10:00~24:00, 每 2 小时一局 (10/12/14/16/18/20/22 点开局)。
 * 押注帖一般在对局开始一段时间后才发布, 开局瞬间抓取大概率没有有效数据,
 * 因此只在每局最后一个小时的 4 个刻度各抓取一次:
 * 第 10 点场 -> 11:00 / 11:15 / 11:30 / 11:45, 第 12 点场 -> 13:00 / ... , 以此类推至 23:45,
 * 全天共 7 局 x 4 次 = 28 次; 其余时间(含开局整点、凌晨)不执行。
 * <p>
 * 单线程调度器天然防重叠; 用户之间随机间隔 3~6 秒发送请求, 避免请求过快。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFetchTask {

    /**
     * 奇数小时(每局的后一个小时)的 0/15/30/45 分触发
     */
    private static final String CRON = "0 0,15,30,45 11,13,15,17,19,21,23 * * *";

    /** 相邻请求最小间隔 */
    private static final long MIN_GAP_MS = 3_000L;
    /** 相邻请求最大间隔 */
    private static final long MAX_GAP_MS = 6_000L;

    private final DsFeedClient dsFeedClient;
    private final FeedStorageService feedStorageService;
    /** AI 判定实现由 app.ai.provider 决定: deepseek(默认) / startlux */
    private final AiGuessJudge aiGuessJudge;
    private final GuessQueryService guessQueryService;

    /** 是否在应用启动完成后立即执行一次采集 */
    @Value("${app.fetch.run-on-startup:true}")
    private boolean runOnStartup;

    /** 采集进行中标记: 防止启动采集与 cron 触发重叠 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 应用启动完成后按开关执行一次采集
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        if (!runOnStartup) {
            log.info("app.fetch.run-on-startup=false, 跳过启动采集");
            return;
        }
        log.info("app.fetch.run-on-startup=true, 启动后立即执行一次采集");
        try {
            fetchAll();
        } catch (Exception e) {
            log.error("启动采集执行失败", e);
        }
    }

    @Scheduled(cron = CRON, zone = "Asia/Shanghai")
    public void fetchAll() {
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮采集仍在进行, 跳过本次触发");
            return;
        }
        try {
            doFetchAll();
        } finally {
            running.set(false);
        }
    }

    private void doFetchAll() {
        log.info("==== 开始抓取大神动态, 共 {} 个用户 ====", Config.USER_KEY_DS.size());
        List<FeedData> allFeeds = new ArrayList<>();
        int success = 0;
        boolean first = true;
        for (var entry : Config.USER_KEY_DS.entrySet()) {
            if (!first) {
                sleepGap();
            }
            first = false;
            try {
                allFeeds.addAll(dsFeedClient.fetchFeeds(entry.getKey(), entry.getValue()));
                success++;
            } catch (Exception e) {
                log.error("[{}] 抓取失败, uid={}", entry.getKey(), entry.getValue(), e);
            }
        }
        log.info("==== 抓取结束, 成功 {}/{}, 共 {} 条动态 ====", success, Config.USER_KEY_DS.size(), allFeeds.size());

        // 1. 全量入库 (feed 去重 + 本地竞猜去重), 候选帖 is_guess 保持 NULL
        feedStorageService.saveAll(allFeeds);

        // 2. 赛果线索落库 ("上局X竞猜成功/失败"自动推断, 已有记录不覆盖)
        List<RoundResultHint> hints = allFeeds.stream()
                .map(FeedData::getResultHint)
                .filter(Objects::nonNull)
                .toList();
        if (!hints.isEmpty()) {
            feedStorageService.applyRoundResultHints(hints);
        }

        // 3. AI 兜底: 仅处理仍未判定的候选帖 (查库去重, 每个 feed 最多送 AI 一次)
        List<FeedData> candidates = allFeeds.stream().filter(f -> f.getIsGuess() == null).toList();
        if (!candidates.isEmpty()) {
            List<String> candidateIds = candidates.stream().map(FeedData::getFeedId).toList();
            Set<String> unjudgedIds = feedStorageService.findUnjudgedFeedIds(candidateIds);
            if (!unjudgedIds.isEmpty()) {
                List<FeedData> toJudge = candidates.stream()
                        .filter(f -> unjudgedIds.contains(f.getFeedId()))
                        .toList();
                log.info("AI 兜底判定候选帖 {} 条", toJudge.size());
                Map<String, AiVerdict> verdicts = aiGuessJudge.judge(toJudge);
                if (!verdicts.isEmpty()) {
                    feedStorageService.applyAiVerdicts(toJudge, verdicts);
                }
            }
        }

        // 4. 刷新查询缓存
        guessQueryService.evict();
    }

    private void sleepGap() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_GAP_MS, MAX_GAP_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
