package com.example.onmyojiguess.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.onmyojiguess.dto.CurrentShikigamiResponse;
import com.example.onmyojiguess.dto.ShikigamiUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对局式神数据服务 (游戏画面 OCR 采集入库 + 查询)
 * <p>
 * 所有属性值按 OCR 原样字符串保存, 不做数值转换;
 * 同一局重复上报通过唯一键 (round_date, round_no, side, no) upsert 覆盖, 以最后一次为准。
 */
@Service
@RequiredArgsConstructor
public class ShikigamiService {

    /** OCR 画面最多 5 个座位 */
    private static final int MAX_SEATS = 5;
    private static final String SIDE_RED = "RED";
    private static final String SIDE_BLUE = "BLUE";

    private static final String SQL_UPSERT = """
            INSERT INTO guess_shikigami
                (round_date, round_no, side, no, name, attack, hp, defense, speed,
                 crit_rate, crit_damage, effect_hit, effect_resist, mitama)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE
                name = new.name, attack = new.attack, hp = new.hp, defense = new.defense,
                speed = new.speed, crit_rate = new.crit_rate, crit_damage = new.crit_damage,
                effect_hit = new.effect_hit, effect_resist = new.effect_resist, mitama = new.mitama
            """;

    private static final String SQL_BY_ROUND = """
            SELECT side, no, name, attack, hp, defense, speed,
                   crit_rate, crit_damage, effect_hit, effect_resist, mitama
            FROM guess_shikigami
            WHERE round_date = ? AND round_no = ?
            ORDER BY no
            """;

    private final JdbcTemplate jdbcTemplate;
    private final GuessQueryService guessQueryService;

    /**
     * 保存一局的双方式神数据 (upsert, 同一局重复上报以最后一次为准)
     *
     * @return 实际写入行数
     */
    public int save(String roundDate, int roundNo, JSONArray red, JSONArray blue) {
        List<Object[]> rows = new ArrayList<>();
        collect(rows, roundDate, roundNo, SIDE_RED, red);
        collect(rows, roundDate, roundNo, SIDE_BLUE, blue);
        if (rows.isEmpty()) {
            return 0;
        }
        jdbcTemplate.batchUpdate(SQL_UPSERT, rows);
        return rows.size();
    }

    /**
     * 查询指定局双方式神数据
     */
    public CurrentShikigamiResponse getByRound(String roundDate, int roundNo) {
        List<ShikigamiUnit> red = new ArrayList<>();
        List<ShikigamiUnit> blue = new ArrayList<>();
        jdbcTemplate.query(SQL_BY_ROUND, rs -> {
            ShikigamiUnit unit = new ShikigamiUnit(
                    rs.getInt("no"), rs.getString("name"), rs.getString("attack"),
                    rs.getString("hp"), rs.getString("defense"), rs.getString("speed"),
                    rs.getString("crit_rate"), rs.getString("crit_damage"),
                    rs.getString("effect_hit"), rs.getString("effect_resist"),
                    rs.getString("mitama"));
            if (SIDE_RED.equals(rs.getString("side"))) {
                red.add(unit);
            } else {
                blue.add(unit);
            }
        }, roundDate, roundNo);
        return new CurrentShikigamiResponse(roundDate, roundNo, red, blue);
    }

    /**
     * 当前对局双方式神数据
     */
    public CurrentShikigamiResponse current() {
        GuessQueryService.RoundKey key = guessQueryService.currentRoundKey();
        return getByRound(key.date().toString(), key.roundNo());
    }

    private void collect(List<Object[]> rows, String roundDate, int roundNo, String side, JSONArray arr) {
        int count = Math.min(arr.size(), MAX_SEATS);
        for (int i = 0; i < count; i++) {
            JSONObject o = arr.getJSONObject(i);
            rows.add(new Object[]{
                    roundDate, roundNo, side, o.getIntValue("no"),
                    str(o, "name"), str(o, "attack"), str(o, "hp"),
                    str(o, "defense"), str(o, "speed"), str(o, "critRate"),
                    str(o, "critDamage"), str(o, "effectHit"), str(o, "effectResist"),
                    str(o, "mitama")});
        }
    }

    /** 元素缺失字段按空字符串处理 */
    private String str(JSONObject o, String key) {
        String v = o.getString(key);
        return v == null ? "" : v.trim();
    }
}
