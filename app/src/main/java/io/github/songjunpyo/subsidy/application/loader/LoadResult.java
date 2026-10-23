package io.github.songjunpyo.subsidy.application.loader;

import java.time.OffsetDateTime;

/**
 * 적재 1회의 결과. comSelect·comInsert·questions는 적재 전후 MySQL 전역 카운터 차이다
 * (exporter 등 다른 접속의 쿼리도 조금 섞인다).
 */
public record LoadResult(
        OffsetDateTime startedAt,
        LoadMode mode,
        Long rowLimit,
        long csvRows,
        long writtenRows,
        long dbRows,
        long elapsedMillis,
        long comSelect,
        long comInsert,
        long questions
) {

    public boolean matched() {
        return csvRows == writtenRows && writtenRows == dbRows;
    }

    public long rowsPerSecond() {
        return elapsedMillis == 0 ? 0 : dbRows * 1000 / elapsedMillis;
    }

    public String toJson() {
        return """
                {"startedAt":"%s","mode":"%s","rowLimit":%s,"csvRows":%d,"writtenRows":%d,"dbRows":%d,\
                "matched":%b,"elapsedMillis":%d,"rowsPerSecond":%d,"comSelect":%d,"comInsert":%d,"questions":%d}"""
                .formatted(startedAt, mode.option(), rowLimit, csvRows, writtenRows, dbRows,
                        matched(), elapsedMillis, rowsPerSecond(), comSelect, comInsert, questions);
    }
}
