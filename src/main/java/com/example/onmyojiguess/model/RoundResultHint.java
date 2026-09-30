package com.example.onmyojiguess.model;

import java.time.LocalDate;

/**
 * 赛果线索：从"上局 右(蓝)竞猜成功/失败"类赛果帖中推断出的上一局胜方
 *
 * @param roundDate 被推断的那一局日期
 * @param roundNo   被推断的那一局场次 1~7
 * @param winSide   推断出的胜方 RED/BLUE
 * @param reporter  报告者(发帖人)昵称
 */
public record RoundResultHint(LocalDate roundDate, int roundNo, String winSide, String reporter) {
}
