package com.example.onmyojiguess.dto;

import java.util.List;

/**
 * 某一局双方式神数据
 */
public record CurrentShikigamiResponse(
        String roundDate,
        int roundNo,
        List<ShikigamiUnit> red,
        List<ShikigamiUnit> blue) {
}
