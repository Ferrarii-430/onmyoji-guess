package com.example.onmyojiguess.service;

import com.example.onmyojiguess.model.AiVerdict;
import com.example.onmyojiguess.model.FeedData;
import com.example.onmyojiguess.model.GuessInfo;
import com.example.onmyojiguess.model.RoundResultHint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态与竞猜数据入库（MySQL 去重保存）
 * <p>
 * feed 表按 feed_id 唯一去重；guess_record 表按 (feed_id, round_start) 唯一去重。
 * is_guess 三态判定随 feed 持久化，实现"每个 feed 最多送 AI 一次"的去重：
 * 重复抓取时已判定(非 NULL)的 feed 不再进入 AI 队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedStorageService {

    // 注: 使用 VALUES() 而非 8.0.19+ 的行别名(AS new)语法, 兼容低版本 MySQL / MariaDB
    private static final String INSERT_FEED = """
            INSERT INTO feed (feed_id, user_name, uid, feed_type, content_text, ip_location, create_time, update_time, is_guess)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                feed_type = VALUES(feed_type),
                content_text = VALUES(content_text),
                ip_location = VALUES(ip_location),
                update_time = VALUES(update_time),
                is_guess = COALESCE(VALUES(is_guess), is_guess)
            """;

    private static final String INSERT_GUESS = """
            INSERT INTO guess_record (feed_id, user_name, uid, round_date, round_no, round_start, bet_side, raw_line)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                bet_side = VALUES(bet_side),
                raw_line = COALESCE(VALUES(raw_line), raw_line)
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 保存 feed 与本地规则解析出的竞猜；候选帖 is_guess 传 null
     */
    public void saveAll(List<FeedData> feeds) {
        if (feeds == null || feeds.isEmpty()) {
            return;
        }
        List<Object[]> feedArgs = feeds.stream().map(f -> new Object[]{
                f.getFeedId(), f.getUserName(), f.getUid(), f.getFeedType(),
                f.getText(), f.getIpLocation(),
                toTs(f.getCreateTime()), toTs(f.getUpdateTime()), f.getIsGuess()
        }).toList();

        List<GuessInfo> guesses = feeds.stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsGuess()))
                .flatMap(f -> f.getGuesses().stream())
                .toList();

        jdbcTemplate.batchUpdate(INSERT_FEED, feedArgs);
        insertGuesses(guesses);
        log.info("入库完成: feed {} 条, 本地竞猜 {} 条", feedArgs.size(), guesses.size());
    }

    /**
     * 查询尚未判定(is_guess IS NULL)的 feed，用于 AI 兜底去重（含重启后场景）
     */
    public Set<String> findUnjudgedFeedIds(Collection<String> feedIds) {
        if (feedIds == null || feedIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", Collections.nCopies(feedIds.size(), "?"));
        String sql = "SELECT feed_id FROM feed WHERE is_guess IS NULL AND feed_id IN (" + placeholders + ")";
        return new HashSet<>(jdbcTemplate.queryForList(sql, String.class, feedIds.toArray()));
    }

    /**
     * 落库 AI 判定结果：更新 feed.is_guess；押注贴且能确定场次/方向时写入 guess_record
     */
    public void applyAiVerdicts(List<FeedData> candidates, Map<String, AiVerdict> verdicts) {
        if (candidates == null || candidates.isEmpty() || verdicts.isEmpty()) {
            return;
        }
        List<GuessInfo> guesses = new ArrayList<>();
        for (FeedData feed : candidates) {
            AiVerdict verdict = verdicts.get(feed.getFeedId());
            if (verdict == null) {
                continue;
            }
            jdbcTemplate.update("UPDATE feed SET is_guess = ? WHERE feed_id = ?",
                    verdict.isBet(), feed.getFeedId());
            if (verdict.isBet() && isValidSide(verdict.getSide()) && verdict.getRoundNo() != null
                    && feed.getCreateTime() != null) {
                LocalDateTime created = feed.getCreateTime().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
                int roundHour = 8 + 2 * verdict.getRoundNo();
                LocalDate roundDate = GuessParser.resolveRoundDate(created, roundHour);

                GuessInfo info = new GuessInfo();
                info.setFeedId(feed.getFeedId());
                info.setUserName(feed.getUserName());
                info.setUid(feed.getUid());
                info.setRoundDate(roundDate);
                info.setRoundNo(verdict.getRoundNo());
                info.setRoundStart(roundDate.atTime(roundHour, 0));
                info.setBetSide(verdict.getSide());
                info.setRawLine(null);
                guesses.add(info);
            }
        }
        insertGuesses(guesses);
        long betCount = verdicts.values().stream().filter(AiVerdict::isBet).count();
        log.info("AI 判定落库完成: 判定 {} 条, 其中押注贴 {} 条, 竞猜记录 {} 条", verdicts.size(), betCount, guesses.size());
    }

    private static final String INSERT_ROUND_RESULT_AUTO = """
            INSERT IGNORE INTO round_result (round_date, round_no, win_side, source, reporter)
            VALUES (?, ?, ?, 'AUTO', ?)
            """;

    private static final String UPSERT_ROUND_RESULT_MANUAL = """
            INSERT INTO round_result (round_date, round_no, win_side, source)
            VALUES (?, ?, ?, 'MANUAL')
            ON DUPLICATE KEY UPDATE
                win_side = VALUES(win_side),
                source = 'MANUAL',
                reporter = NULL
            """;

    /**
     * 应用赛果线索（AUTO 来源）：已存在记录不覆盖，手动录入优先
     */
    public void applyRoundResultHints(List<RoundResultHint> hints) {
        if (hints == null || hints.isEmpty()) {
            return;
        }
        List<Object[]> args = hints.stream()
                .map(h -> new Object[]{Date.valueOf(h.roundDate()), h.roundNo(), h.winSide(), h.reporter()})
                .toList();
        jdbcTemplate.batchUpdate(INSERT_ROUND_RESULT_AUTO, args);
        log.info("赛果线索落库 {} 条 (已存在的自动线索不覆盖)", args.size());
    }

    /**
     * 手动录入/修正某局胜方（覆盖任何来源）
     */
    public void setRoundResult(LocalDate roundDate, int roundNo, String winSide) {
        jdbcTemplate.update(UPSERT_ROUND_RESULT_MANUAL, Date.valueOf(roundDate), roundNo, winSide);
        log.info("手动录入赛果: {} 第{}场 胜方={}", roundDate, roundNo, winSide);
    }

    private void insertGuesses(List<GuessInfo> guesses) {
        if (guesses.isEmpty()) {
            return;
        }
        List<Object[]> args = guesses.stream().map(g -> new Object[]{
                g.getFeedId(), g.getUserName(), g.getUid(),
                Date.valueOf(g.getRoundDate()), g.getRoundNo(),
                toTs(g.getRoundStart()), g.getBetSide(), g.getRawLine()
        }).toList();
        jdbcTemplate.batchUpdate(INSERT_GUESS, args);
    }

    private boolean isValidSide(String side) {
        return GuessInfo.SIDE_RED.equals(side) || GuessInfo.SIDE_BLUE.equals(side);
    }

    private Timestamp toTs(LocalDateTime time) {
        return time == null ? null : Timestamp.valueOf(time);
    }
}
