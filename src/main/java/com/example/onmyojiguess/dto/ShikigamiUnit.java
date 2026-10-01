package com.example.onmyojiguess.dto;

/**
 * 单个座位的式神数据 (OCR 采集, 属性值按原样字符串保存)
 *
 * @param no           座位序号 1~5
 * @param name         式神名称
 * @param attack       攻击
 * @param hp           生命
 * @param defense      防御
 * @param speed        速度
 * @param critRate     暴击 (含百分号)
 * @param critDamage   暴击伤害 (含百分号)
 * @param effectHit    命中效果 (含百分号)
 * @param effectResist 效果抵抗 (含百分号)
 * @param mitama       御魂名称 (OCR 失败时为空字符串)
 */
public record ShikigamiUnit(
        int no,
        String name,
        String attack,
        String hp,
        String defense,
        String speed,
        String critRate,
        String critDamage,
        String effectHit,
        String effectResist,
        String mitama) {
}
