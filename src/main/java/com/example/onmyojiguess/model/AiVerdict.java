package com.example.onmyojiguess.model;

import lombok.Data;

/**
 * AI 判定结果
 */
@Data
public class AiVerdict {

    /** 对应 feed id */
    private String feedId;

    /** 是否押注贴 */
    private boolean bet;

    /** 押注方向 RED=左(红) BLUE=右(蓝)，非押注贴为 null */
    private String side;

    /** 场次序号 1~7（第1场=10点场），非押注贴为 null */
    private Integer roundNo;
}
