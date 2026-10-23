package io.github.songjunpyo.subsidy.infra.loader;

import io.github.songjunpyo.subsidy.application.loader.EligiblePersonWriter;
import io.github.songjunpyo.subsidy.application.loader.LoadMode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** jdbc: JdbcTemplate.batchUpdate, 배치 1000건, rewriteBatchedStatements=true. */
@Component
@Profile("loader")
public class JdbcEligiblePersonWriter implements EligiblePersonWriter {

    static final String INSERT_SQL = """
            INSERT INTO eligible_person
                (person_id, ci_hash, name, birth_year, region_code, target_group, support_amount)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbcTemplate;

    public JdbcEligiblePersonWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LoadMode mode() {
        return LoadMode.JDBC;
    }

    @Override
    public long write(Path csv, Long rowLimit) {
        return EligiblePersonCsv.readChunks(csv, rowLimit, chunk ->
                jdbcTemplate.batchUpdate(INSERT_SQL, chunk, chunk.size(), (ps, r) -> {
                    ps.setLong(1, r.personId());
                    ps.setString(2, r.ciHash());
                    ps.setString(3, r.name());
                    ps.setInt(4, r.birthYear());
                    ps.setString(5, r.regionCode());
                    ps.setString(6, r.targetGroup());
                    ps.setLong(7, r.supportAmount());
                }));
    }
}
