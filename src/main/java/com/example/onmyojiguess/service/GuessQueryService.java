package com.example.onmyojiguess.service;

import com.example.onmyojiguess.dto.CurrentGuessResponse;
import com.example.onmyojiguess.dto.CurrentGuessResponse.RoundInfo;
import com.example.onmyojiguess.dto.CurrentGuessResponse.UserBet;
import com.example.onmyojiguess.dto.CurrentGuessResponse.UserWinRate;
import com.example.onmyojiguess.model.GuessInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * 当前对局竞猜查询服务（带内存缓存）
 * <p>
 * 查询结果组装后缓存 {@link #CACHE_TTL_MS} 毫秒，避免接口高频调用时反复查库；
 * 定时抓取完成或手动录入赛果后会主动失效缓存。
 */
@Service
@RequiredArgsConstructor
public class GuessQueryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 缓存有效期 15 秒 */
    private static final long CACHE_TTL_MS = 15_000L;

    private static final String SQL_BETS = """
            SELECT g.user_name, g.bet_side, g.raw_line, g.create_time,
                   f.content_text, f.create_time AS feed_time
            FROM guess_record g
            LEFT JOIN feed f ON f.feed_id = g.feed_id
            WHERE g.round_date = ? AND g.round_no = ?
            ORDER BY g.user_name, g.id
            """;

    private static final String SQL_WIN_RATES = """
            SELECT g.user_name AS user_name,
                   COUNT(*) AS total,
                   SUM(CASE WHEN g.bet_side = r.win_side THEN 1 ELSE 0 END) AS win
            FROM guess_record g
            JOIN round_result r ON r.round_date = g.round_date AND r.round_no = g.round_no
            GROUP BY g.user_name
            """;

    private final JdbcTemplate jdbcTemplate;

    private volatile CacheEntry cache;

    private record CacheEntry(CurrentGuessResponse payload, long builtAt) {
    }

    /**
     * 当前最新对局竞猜数据 (带 TTL 缓存)
     */
    public CurrentGuessResponse getCurrent() {
        CacheEntry entry = cache;
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.builtAt() > CACHE_TTL_MS) {
            synchronized (this) {
                entry = cache;
                if (entry == null || now - entry.builtAt() > CACHE_TTL_MS) {
                    entry = new CacheEntry(build(), System.currentTimeMillis());
                    cache = entry;
                }
            }
        }
        return entry.payload();
    }

    /**
     * 手动录入/修正赛果并刷新缓存
     */
    public void setRoundResult(LocalDate roundDate, int roundNo, String winSide) {
        jdbcTemplate.update("""
                        INSERT INTO round_result (round_date, round_no, win_side, source) VALUES (?, ?, ?, 'MANUAL')
                        ON DUPLICATE KEY UPDATE win_side = VALUES(win_side), source = 'MANUAL', reporter = NULL
                        """,
                Date.valueOf(roundDate), roundNo, winSide);
        cache = null;
    }

    public void evict() {
        cache = null;
    }

    /**
     * 当前对局标识: 10:00 前 = 昨日第7场; 其余按小时定位 (11 点仍属 10 点场)
     */
    public record RoundKey(LocalDate date, int roundNo) {
    }

    public RoundKey currentRoundKey() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (now.toLocalTime().isBefore(LocalTime.of(10, 0))) {
            return new RoundKey(now.toLocalDate().minusDays(1), 7);
        }
        return new RoundKey(now.toLocalDate(), Math.min(7, (now.getHour() - 10) / 2 + 1));
    }

    private CurrentGuessResponse build() {
        // 当前对局: 10:00 前 = 昨日第7场; 其余按小时定位 (11 点仍属 10 点场)
        RoundKey key = currentRoundKey();
        LocalDate date = key.date();
        int roundNo = key.roundNo();
        LocalDateTime roundStart = date.atTime(8 + 2 * roundNo, 0);
        LocalDateTime roundEnd = roundStart.plusHours(2);
        String status = LocalDateTime.now(ZONE).isBefore(roundEnd) ? "BETTING" : "FINISHED";
        String winSide = queryWinSide(date, roundNo);

        List<UserBet> bets = queryBets(date, roundNo, winSide);
        int redCount = (int) bets.stream().filter(b -> GuessInfo.SIDE_RED.equals(b.betSide())).count();
        int blueCount = (int) bets.stream().filter(b -> GuessInfo.SIDE_BLUE.equals(b.betSide())).count();

        RoundInfo round = new RoundInfo(date.toString(), roundNo,
                roundStart.toString(), roundEnd.toString(), status, winSide, redCount, blueCount);
        return new CurrentGuessResponse(round, bets, queryWinRates());
    }

    private String queryWinSide(LocalDate date, int roundNo) {
        List<String> rs = jdbcTemplate.queryForList(
                "SELECT win_side FROM round_result WHERE round_date = ? AND round_no = ?",
                String.class, Date.valueOf(date), roundNo);
        return rs.isEmpty() ? null : rs.get(0);
    }

    private List<UserBet> queryBets(LocalDate date, int roundNo, String winSide) {
        return jdbcTemplate.query(SQL_BETS, (rs, i) -> {
            String side = rs.getString("bet_side");
            String rawLine = rs.getString("raw_line");
            Timestamp judgeTime = rs.getTimestamp("create_time");
            Timestamp feedTime = rs.getTimestamp("feed_time");
            return new UserBet(
                    rs.getString("user_name"),
                    side,
                    sideText(side),
                    rawLine,
                    rawLine == null ? "AI" : "POST",
                    judgeTime == null ? null : judgeTime.toLocalDateTime().toString(),
                    winSide == null ? null : winSide.equals(side),
                    rs.getString("content_text"),
                    feedTime == null ? null : feedTime.toLocalDateTime().toString());
        }, Date.valueOf(date), roundNo);
    }

    private List<UserWinRate> queryWinRates() {
        List<UserWinRate> list = jdbcTemplate.query(SQL_WIN_RATES, (rs, i) -> {
            int total = rs.getInt("total");
            int win = rs.getInt("win");
            double rate = total == 0 ? 0.0 : Math.round(win * 1000.0 / total) / 1000.0;
            return new UserWinRate(rs.getString("user_name"), total, win, rate);
        });
        list.sort(Comparator.comparingDouble(UserWinRate::winRate).reversed()
                .thenComparing(Comparator.comparingInt(UserWinRate::total).reversed()));
        return list;
    }

    private String sideText(String side) {
        return GuessInfo.SIDE_RED.equals(side) ? "左(红)" : "右(蓝)";
    }
}
