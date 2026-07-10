package com.dbconverter.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversionItem {
    private String filePath;
    private String sourceSql;
    private String targetSql;
    private String conversionType;
    private int lineNumber;
}
