package io.github.songjunpyo.subsidy.application.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * loader 프로필 진입점.
 * --mode=jpa|jdbc|mybatis-batch|mybatis-foreach|load-data --file=<절대 경로> [--rows=N] [--result=<jsonl 경로>]
 */
@Component
@Profile("loader")
public class EligiblePersonLoadRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EligiblePersonLoadRunner.class);

    private final EligiblePersonLoader loader;

    public EligiblePersonLoadRunner(EligiblePersonLoader loader) {
        this.loader = loader;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption("mode")) {
            log.warn("--mode가 없어 적재하지 않는다. 사용법: --mode={} --file=<절대 경로> [--rows=N] [--result=<jsonl 경로>]",
                    LoadMode.options());
            return;
        }
        LoadMode mode = LoadMode.from(single(args, "mode"));
        Path file = Path.of(single(args, "file"));
        if (!file.isAbsolute() || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("--file은 존재하는 파일의 절대 경로여야 한다: " + file);
        }
        Long rows = args.containsOption("rows") ? Long.valueOf(single(args, "rows")) : null;

        log.info("적재 시작: mode={}, file={}, rows={}", mode.option(), file, rows == null ? "전체" : rows);
        LoadResult result = loader.load(mode, file, rows);
        log.info("적재 결과: {}", result.toJson());

        if (args.containsOption("result")) {
            Files.writeString(Path.of(single(args, "result")), result.toJson() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        if (!result.matched()) {
            throw new IllegalStateException("행 수 불일치: " + result.toJson());
        }
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("--" + name + " 값이 하나 필요하다");
        }
        return values.getFirst();
    }
}
