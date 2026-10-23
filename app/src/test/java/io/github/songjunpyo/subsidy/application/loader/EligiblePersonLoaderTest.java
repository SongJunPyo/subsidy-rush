package io.github.songjunpyo.subsidy.application.loader;

import io.github.songjunpyo.subsidy.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("loader")
class EligiblePersonLoaderTest {

    private static final int ROWS = 2_500;  // 1000건 묶음 2개 + 남는 500건

    @TempDir
    static Path dir;
    static Path csv;

    @Autowired
    EligiblePersonLoader loader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void writeCsv() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("person_id,ci_hash,name,birth_year,region_code,target_group,support_amount");
        for (int id = 1; id <= ROWS; id++) {
            lines.add("%d,%064x,김민준,%d,11,TRUCK,300000".formatted(id, id, 1945 + id % 60));
        }
        csv = dir.resolve("eligible_person.csv");
        Files.write(csv, lines, StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @EnumSource(LoadMode.class)
    void 모든_방식이_CSV_행_수만큼_적재한다(LoadMode mode) {
        LoadResult result = loader.load(mode, csv, null);

        assertThat(result.csvRows()).isEqualTo(ROWS);
        assertThat(result.dbRows()).isEqualTo(ROWS);
        assertThat(result.matched()).isTrue();

        Map<String, Object> last = jdbcTemplate.queryForMap(
                "SELECT ci_hash, name, birth_year, support_amount FROM eligible_person WHERE person_id = ?", ROWS);
        assertThat(last.get("ci_hash")).isEqualTo("%064x".formatted(ROWS));
        assertThat(last.get("name")).isEqualTo("김민준");
        assertThat(((Number) last.get("birth_year")).intValue()).isEqualTo(1945 + ROWS % 60);
        assertThat(((Number) last.get("support_amount")).longValue()).isEqualTo(300_000L);
    }

    @Test
    void 다시_적재하면_테이블을_비우고_시작한다() {
        loader.load(LoadMode.JDBC, csv, null);
        LoadResult again = loader.load(LoadMode.JDBC, csv, null);

        assertThat(again.dbRows()).isEqualTo(ROWS);
    }

    @Test
    void jpa는_INSERT_전에_건마다_SELECT를_한다() {
        LoadResult jpa = loader.load(LoadMode.JPA, csv, null);
        LoadResult jdbc = loader.load(LoadMode.JDBC, csv, null);

        assertThat(jpa.comSelect()).isGreaterThanOrEqualTo(ROWS);
        assertThat(jdbc.comSelect()).isLessThan(ROWS / 10);
    }

    @Test
    void rows로_앞에서부터_일부만_적재한다() {
        LoadResult result = loader.load(LoadMode.JPA, csv, 1_200L);

        assertThat(result.csvRows()).isEqualTo(1_200);
        assertThat(result.dbRows()).isEqualTo(1_200);
    }

    @Test
    void load_data는_rows를_지원하지_않는다() {
        assertThatThrownBy(() -> loader.load(LoadMode.LOAD_DATA, csv, 1_200L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
