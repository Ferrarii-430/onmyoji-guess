package com.example.onmyojiguess.service;

import com.example.onmyojiguess.model.FeedData;
import com.example.onmyojiguess.model.GuessInfo;
import com.example.onmyojiguess.model.RoundResultHint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对弈竞猜本地解析器（规则优先，本地无法判定时才交给 AI，降低 token 消耗）
 * <p>
 * 押注帖不限定固定开头，只要某一行能同时确定：
 * 1. 押注方向：左(红)=RED / 右(蓝)=BLUE（兼容全角括号、押翻盘、胜率等附加文本）
 * 2. 场次：时间(如 18:00，10~22 点每 2 小时一场) 或 "第N场"(N=1~7，第1场=10点场)
 * <p>
 * 完全不含 红/蓝/左/右 关键字的动态直接判定为无关帖，不进 AI。
 */
@Component
public class GuessParser {

    /** 竞猜时间，如 18:00，兼容全角冒号 */
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2})[:：](\\d{1,2})");
    /** 押左(红)，兼容全角括号 */
    private static final Pattern RED_PATTERN = Pattern.compile("左\\s*[（(]\\s*红\\s*[)）]");
    /** 押右(蓝)，兼容全角括号 */
    private static final Pattern BLUE_PATTERN = Pattern.compile("右\\s*[（(]\\s*蓝\\s*[)）]");
    /** 第N场，N=1~7，支持汉字数字 */
    private static final Pattern ROUND_NO_PATTERN = Pattern.compile("第\\s*([1-7一二三四五六七两])\\s*场");
    /** 候选判定：文本含任一 红/蓝/左/右 才可能是竞猜帖 */
    private static final Pattern CANDIDATE_PATTERN = Pattern.compile("[红蓝左右]");
    /** 赛果帖: "上局 右(蓝)竞猜成功(.jpg)"，容忍尾部杂质 */
    private static final Pattern RECAP_PATTERN =
            Pattern.compile("上局\\s*(左|右)\\s*[（(]\\s*(红|蓝)\\s*[)）]\\s*竞猜(成功|失败)");

    /** 竞猜场次开始小时：10 点开始，24 点结束，每 2 小时一局 */
    private static final Set<Integer> ROUND_HOURS = Set.of(10, 12, 14, 16, 18, 20, 22);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Map<String, Integer> CN_NUM = Map.of(
            "一", 1, "二", 2, "两", 2, "三", 3, "四", 4, "五", 5, "六", 6, "七", 7);

    /**
     * 本地规则解析 feed 正文，提取可确定的竞猜押注信息
     *
     * @return 可确定的竞猜列表；空列表不代表无关，需结合 {@link #isCandidate} 判断
     */
    public List<GuessInfo> parse(FeedData feed) {
        List<GuessInfo> guesses = new ArrayList<>();
        if (feed.getCreateTime() == null || !StringUtils.hasText(feed.getText())) {
            return guesses;
        }
        LocalDateTime created = feed.getCreateTime().atZone(ZONE).toLocalDateTime();

        for (String line : feed.getText().split("\\r?\\n")) {
            String side;
            if (RED_PATTERN.matcher(line).find()) {
                side = GuessInfo.SIDE_RED;
            } else if (BLUE_PATTERN.matcher(line).find()) {
                side = GuessInfo.SIDE_BLUE;
            } else {
                continue;
            }

            Integer roundNo = extractRoundNo(line);
            if (roundNo == null) {
                continue;
            }

            int roundHour = 8 + 2 * roundNo;
            LocalDate roundDate = resolveRoundDate(created, roundHour);

            GuessInfo info = new GuessInfo();
            info.setFeedId(feed.getFeedId());
            info.setUserName(feed.getUserName());
            info.setUid(feed.getUid());
            info.setRoundDate(roundDate);
            info.setRoundNo(roundNo);
            info.setRoundStart(roundDate.atTime(roundHour, 0));
            info.setBetSide(side);
            info.setRawLine(line.trim());
            guesses.add(info);
        }
        return guesses;
    }

    /**
     * 赛果推断：帖子含 "上局 左/右(红/蓝) 竞猜成功/失败" 时，推断上一局胜方
     * <p>
     * 成功 -> 上局胜方 = 帖中方向；失败 -> 上局胜方 = 反方向。
     * 帖子自身必须有可识别的场次行（用于定位"上局"是哪一局），否则返回 null。
     */
    public RoundResultHint parseRoundResultHint(FeedData feed) {
        if (feed.getCreateTime() == null || !StringUtils.hasText(feed.getText())) {
            return null;
        }
        LocalDateTime created = feed.getCreateTime().atZone(ZONE).toLocalDateTime();
        List<String> lines = List.of(feed.getText().split("\\r?\\n"));

        // 自身场次: 取第一条可识别场次的行 (赛果帖一般自带当日押注行)
        Integer ownRoundNo = lines.stream()
                .map(this::extractRoundNo)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (ownRoundNo == null) {
            return null;
        }

        for (String line : lines) {
            Matcher m = RECAP_PATTERN.matcher(line);
            if (!m.find()) {
                continue;
            }
            String side = "左".equals(m.group(1)) ? GuessInfo.SIDE_RED : GuessInfo.SIDE_BLUE;
            boolean success = "成功".equals(m.group(3));
            String winSide = success ? side : (GuessInfo.SIDE_RED.equals(side) ? GuessInfo.SIDE_BLUE : GuessInfo.SIDE_RED);

            int prevRoundNo = ownRoundNo - 1;
            int prevHour = 8 + 2 * ownRoundNo - 2;
            LocalDate prevDate;
            if (prevRoundNo < 1) {
                // 第1场的"上局"= 前一天的第7场(22点场)
                prevRoundNo = 7;
                prevDate = created.toLocalDate().minusDays(1);
            } else {
                prevDate = resolveRoundDate(created, prevHour);
            }
            return new RoundResultHint(prevDate, prevRoundNo, winSide, feed.getUserName());
        }
        return null;
    }

    /**
     * 候选筛选：文本含 红/蓝/左/右 任一字才可能是竞猜帖
     */
    public boolean isCandidate(String text) {
        return StringUtils.hasText(text) && CANDIDATE_PATTERN.matcher(text).find();
    }

    /**
     * 从单行中识别场次序号: 优先时间(HH:MM, 10~22点), 其次 "第N场"
     */
    private Integer extractRoundNo(String line) {
        Matcher timeMatcher = TIME_PATTERN.matcher(line);
        if (timeMatcher.find()) {
            int hour = Integer.parseInt(timeMatcher.group(1));
            if (ROUND_HOURS.contains(hour)) {
                return (hour - 10) / 2 + 1;
            }
        }
        Matcher roundMatcher = ROUND_NO_PATTERN.matcher(line);
        if (roundMatcher.find()) {
            return toRoundNo(roundMatcher.group(1));
        }
        return null;
    }

    /**
     * 场次日期判定：默认取发布日期；末场(20/22点)在次日凌晨 4 点前补发时归属前一天
     */
    public static LocalDate resolveRoundDate(LocalDateTime created, int roundHour) {
        LocalDate date = created.toLocalDate();
        if (roundHour >= 20 && created.getHour() < 4) {
            date = date.minusDays(1);
        }
        return date;
    }

    private Integer toRoundNo(String num) {
        if (num.length() == 1 && num.charAt(0) >= '1' && num.charAt(0) <= '7') {
            return num.charAt(0) - '0';
        }
        return CN_NUM.get(num);
    }
}
