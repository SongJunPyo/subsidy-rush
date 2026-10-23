package io.github.songjunpyo.subsidy.infra.loader;

import io.github.songjunpyo.subsidy.application.loader.EligiblePersonWriter;
import io.github.songjunpyo.subsidy.application.loader.LoadMode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** load-data: LOAD DATA LOCAL INFILE 한 문장. 파일 전체를 드라이버가 서버로 보낸다. */
@Component
@Profile("loader")
public class LoadDataEligiblePersonWriter implements EligiblePersonWriter {

    private final JdbcTemplate jdbcTemplate;

    public LoadDataEligiblePersonWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LoadMode mode() {
        return LoadMode.LOAD_DATA;
    }

    @Override
    public long write(Path csv, Long rowLimit) {
        if (rowLimit != null) {
            throw new IllegalArgumentException("load-data는 --rows를 지원하지 않는다 (파일 전체를 적재)");
        }
        String path = csv.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'");
        return jdbcTemplate.update("""
                LOAD DATA LOCAL INFILE '%s' INTO TABLE eligible_person
                CHARACTER SET utf8mb4
                FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n'
                IGNORE 1 LINES
                (person_id, ci_hash, name, birth_year, region_code, target_group, support_amount)"""
                .formatted(path));
    }
}
