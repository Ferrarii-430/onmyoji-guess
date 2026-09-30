package com.example.onmyojiguess.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 从网易大神动态接口解析出的单条 feed 数据
 */
@Data
public class FeedData {

    /** feed 唯一 id（去重键） */
    private String feedId;

    /** 用户昵称（userKey_ds 的 key） */
    private String userName;

    /** 用户 uid（userKey_ds 的 value） */
    private String uid;

    /** feed 类型 */
    private Integer feedType;

    /** 动态正文文本 */
    private String text;

    /** 发布时间 */
    private LocalDateTime createTime;

    /** 编辑时间 */
    private LocalDateTime updateTime;

    /** ip 属地 */
    private String ipLocation;

    /**
     * 是否有效竞猜帖（三态）：
     * TRUE=本地规则确认有效竞猜；FALSE=本地确认无关（不含红/蓝/左/右）；NULL=候选帖，待 AI 判定
     */
    private Boolean isGuess;

    /** 解析出的竞猜信息（可能多条） */
    private List<GuessInfo> guesses;

    /** 赛果线索（"上局X竞猜成功/失败"推断上一局胜方），无则为 null */
    private RoundResultHint resultHint;
}
