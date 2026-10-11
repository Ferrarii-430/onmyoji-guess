/**
 * @Author: CZY
 * @Date: 2026-09-29 15:58:07
 * @LastEditors: CZY
 * @LastEditTime: 2026-09-29 15:58:07
 * @Description:
 **/

package com.example.onmyojiguess.config;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Config {
    /**
     * 网易大神 key: 用户昵称, value: 用户 uid
     */
    public static final Map<String, String> USER_KEY_DS = Stream.of(
            new AbstractMap.SimpleEntry<>("面灵气喵", "462382f1127b46c5add1185d88f0ea40"),
            new AbstractMap.SimpleEntry<>("七面相", "840742d60e4a43208605ae68ca8c3f64")
//            new AbstractMap.SimpleEntry<>("靠脸混饭", "e87f855f36f24b34b9d8f8a4fb2d62b2"),
//            new AbstractMap.SimpleEntry<>("徐ok", "c3c989fae4074d04b478b8ba47ae4120"),
//            new AbstractMap.SimpleEntry<>("徐清林", "21657a558bdd4ddfb6501298350336e7"),
//            new AbstractMap.SimpleEntry<>("就听好歌不说话", "adf8c487c3e44c1ea6ac49f777820e14"),
//            new AbstractMap.SimpleEntry<>("CC南浔", "74db771d92a54c28ae3e98d19aa565a3"),
//            new AbstractMap.SimpleEntry<>("槐夏三十", "a9724e98c1cb4a4e931ebc3f467ea73d"),
//            new AbstractMap.SimpleEntry<>("查查尔", "d9dc2a75497c4a91b2db1e909a36544d"),
//            new AbstractMap.SimpleEntry<>("天真珈百璃", "30e383c884f844a18a7a76fe3c1e888f"),
//            new AbstractMap.SimpleEntry<>("晨时微凉", "72584a679e2f45b6859566b5523400d5"),
//            new AbstractMap.SimpleEntry<>("二蛋搬砖", "3efc40a0a9754bd0922c3d75752beaf4"),
//            new AbstractMap.SimpleEntry<>("yys梧桐", "6bcf88d6fc1341e698d51be1d2eb8758")
    ).collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (a, b) -> a,
            LinkedHashMap::new
    ));
}
