package com.example.onmyojiguess.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.example.onmyojiguess.dto.CurrentGuessResponse;
import com.example.onmyojiguess.dto.CurrentShikigamiResponse;
import com.example.onmyojiguess.model.GuessInfo;
import com.example.onmyojiguess.service.GuessQueryService;
import com.example.onmyojiguess.service.ShikigamiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 竞猜查询接口
 */
@RestController
@RequestMapping("/api/guess")
@RequiredArgsConstructor
public class GuessQueryController {

    private final GuessQueryService guessQueryService;
    private final ShikigamiService shikigamiService;

    /**
     * 当前最新对局的用户竞猜、竞猜结果与统计数据
     */
    @GetMapping("/current")
    public CurrentGuessResponse current() {
        return guessQueryService.getCurrent();
    }

    /**
     * 手动录入/修正某局胜方（覆盖自动推断），用于胜率统计兜底
     *
     * @param roundDate 场次日期 yyyy-MM-dd
     * @param roundNo   场次序号 1~7
     * @param winSide   胜方 RED(左红) / BLUE(右蓝)
     */
    @PostMapping("/round/result")
    public Map<String, Object> setRoundResult(@RequestParam String roundDate,
                                              @RequestParam int roundNo,
                                              @RequestParam String winSide) {
        LocalDate date;
        try {
            date = LocalDate.parse(roundDate);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roundDate 格式应为 yyyy-MM-dd");
        }
        if (roundNo < 1 || roundNo > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roundNo 取值 1~7");
        }
        if (!GuessInfo.SIDE_RED.equals(winSide) && !GuessInfo.SIDE_BLUE.equals(winSide)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "winSide 取值 RED 或 BLUE");
        }
        guessQueryService.setRoundResult(date, roundNo, winSide);
        return Map.of("ok", true, "roundDate", roundDate, "roundNo", roundNo, "winSide", winSide);
    }

    /**
     * 保存某局双方式神数据 (游戏画面 OCR 采集上报, upsert 以最后一次为准)
     *
     * @param roundDate 场次日期 yyyy-MM-dd
     * @param roundNo   场次序号 1~7
     * @param red       红方(左)式神数据 JSON 数组字符串, 长度 0~5
     * @param blue      蓝方(右)式神数据 JSON 数组字符串, 长度 0~5
     */
    @PostMapping("/shikigami")
    public Map<String, Object> saveShikigami(@RequestParam String roundDate,
                                             @RequestParam int roundNo,
                                             @RequestParam String red,
                                             @RequestParam String blue) {
        LocalDate date;
        try {
            date = LocalDate.parse(roundDate);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roundDate 格式应为 yyyy-MM-dd");
        }
        if (roundNo < 1 || roundNo > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roundNo 取值 1~7");
        }
        JSONArray redArr = parseJsonArray(red);
        JSONArray blueArr = parseJsonArray(blue);
        int saved = shikigamiService.save(date.toString(), roundNo, redArr, blueArr);
        return Map.of("ok", true, "roundDate", roundDate, "roundNo", roundNo, "saved", saved);
    }

    /**
     * 当前对局双方式神数据
     */
    @GetMapping("/shikigami/current")
    public CurrentShikigamiResponse currentShikigami() {
        return shikigamiService.current();
    }

    /**
     * red/blue 必须为合法 JSON 数组 ("null" 亦视为非法)
     */
    private JSONArray parseJsonArray(String json) {
        JSONArray arr;
        try {
            arr = JSON.parseArray(json);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "red/blue 格式应为 JSON 数组");
        }
        if (arr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "red/blue 格式应为 JSON 数组");
        }
        return arr;
    }
}
