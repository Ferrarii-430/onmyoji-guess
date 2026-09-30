package com.example.onmyojiguess.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单条对弈竞猜记录
 */
@Data
public class GuessInfo {

    /** 押左边（红方） */
    public static final String SIDE_RED = "RED";
    /** 押右边（蓝方） */
    public static final String SIDE_BLUE = "BLUE";

    /** 对应 feed id */
    private String feedId;

    /** 用户昵称 */
    private String userName;

    /** 用户 uid */
    private String uid;

    /** 竞猜场次日期（feed 发布日期） */
    private LocalDate roundDate;

    /** 场次序号 1~7，对应 10/12/14/16/18/20/22 点场 */
    private Integer roundNo;

    /** 该局开始时间 */
    private LocalDateTime roundStart;

    /** 押注方向：RED=左(红) BLUE=右(蓝) */
    private String betSide;

    /** 原始匹配文本行 */
    private String rawLine;
}
