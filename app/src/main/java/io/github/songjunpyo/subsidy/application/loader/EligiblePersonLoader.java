package io.github.songjunpyo.subsidy.application.loader;

import io.github.songjunpyo.subsidy.infra.loader.EligiblePersonCsv;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 테이블을 비우고, 선택한 방식으로 적재하고, 소요 시간과 행 수를 잰다 (SPEC 9.4). */
@Service
@Profile("loader")
public class EligiblePersonLoader {

    private final Map<LoadMode, EligiblePersonWriter> writers = new EnumMap<>(LoadMode.class);
    private final JdbcTemplate jdbcTemplate;

    public EligiblePersonLoader(List<EligiblePersonWriter> writers, JdbcTemplate jdbcTemplate) {
        writers.forEach(w -> this.writers.put(w.mode(), w));
        this.jdbcTemplate = jdbcTemplate;
    }

    public LoadResult load(LoadMode mode, Path csv, Long rowLimit) {
        EligiblePersonWriter writer = writers.get(mode);
        long csvRows = EligiblePersonCsv.countRows(csv, rowLimit);

        jdbcTemplate.execute("TRUNCATE TABLE eligible_person");
        Map<String, Long> before = globalStatus();
        OffsetDateTime startedAt = OffsetDateTime.now();
        long started = System.nanoTime();

        long written = writer.write(csv, rowLimit);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        Map<String, Long> after = globalStatus();
        long dbRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM eligible_person", Long.class);

        return new LoadResult(startedAt, mode, rowLimit, csvRows, written, dbRows, elapsedMillis,
                after.get("Com_select") - before.get("Com_select"),
                after.get("Com_insert") - before.get("Com_insert"),
                after.get("Questions") - before.get("Questions"));
    }

    private Map<String, Long> globalStatus() {
        Map<String, Long> status = new HashMap<>();
        jdbcTemplate.query(
                "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_select', 'Com_insert', 'Questions')",
                rs -> { status.put(rs.getString(1), rs.getLong(2)); });
        return status;
    }
}
