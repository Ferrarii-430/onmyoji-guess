package com.example.onmyojiguess.dto;

import java.util.List;

/**
 * 当前对局竞猜查询接口响应体
 */
public record CurrentGuessResponse(
        RoundInfo round,
        List<UserBet> bets,
        List<UserWinRate> userWinRates) {

    /**
     * 当前对局信息
     *
     * @param date      场次日期
     * @param roundNo   场次序号 1~7
     * @param roundStart 开局时间
     * @param roundEnd   收官时间
     * @param status     BETTING=进行中 FINISHED=已结束
     * @param winSide    胜方 RED/BLUE, 未产生赛果为 null
     * @param redCount   当局押红人数
     * @param blueCount  当局押蓝人数
     */
    public record RoundInfo(
            String date,
            int roundNo,
            String roundStart,
            String roundEnd,
            String status,
            String winSide,
            int redCount,
            int blueCount) {
    }

    /**
     * 单个用户在当前对局的竞猜
     *
     * @param betSide     押注方向 RED/BLUE
     * @param betSideText 押注方向中文: 左(红)/右(蓝)
     * @param rawLine     动态原文匹配行, AI 判定的为 null
     * @param source      POST=动态原文解析 AI=模型判定
     * @param judgeTime   竞猜入库时间
     * @param win         竞猜结果: true=中 false=未中 null=尚无赛果
     * @param content     来源动态正文 (竞猜相关 content)
     * @param postTime    来源动态发布时间
     */
    public record UserBet(
            String userName,
            String betSide,
            String betSideText,
            String rawLine,
            String source,
            String judgeTime,
            Boolean win,
            String content,
            String postTime) {
    }

    /**
     * 用户历史胜率 (仅统计已有赛果的对局)
     */
    public record UserWinRate(String userName, int total, int win, double winRate) {
    }
}
