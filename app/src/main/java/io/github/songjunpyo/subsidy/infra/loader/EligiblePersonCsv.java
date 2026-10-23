package io.github.songjunpyo.subsidy.infra.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** eligible_person.csv를 chunkSize 단위로 읽는다. 헤더 1줄은 건너뛴다. */
public final class EligiblePersonCsv {

    public static final int CHUNK_SIZE = 1000;

    private EligiblePersonCsv() {
    }

    /** @return 읽은 행 수 */
    public static long readChunks(Path csv, Long rowLimit, Consumer<List<EligiblePersonRow>> consumer) {
        long limit = rowLimit == null ? Long.MAX_VALUE : rowLimit;
        long read = 0;
        List<EligiblePersonRow> chunk = new ArrayList<>(CHUNK_SIZE);
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while (read < limit && (line = reader.readLine()) != null) {
                chunk.add(parse(line));
                read++;
                if (chunk.size() == CHUNK_SIZE) {
                    consumer.accept(chunk);
                    chunk = new ArrayList<>(CHUNK_SIZE);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!chunk.isEmpty()) {
            consumer.accept(chunk);
        }
        return read;
    }

    /** 헤더를 뺀 데이터 행 수 (rowLimit이 있으면 그 값을 넘지 않는다). */
    public static long countRows(Path csv, Long rowLimit) {
        try (var lines = Files.lines(csv, StandardCharsets.UTF_8)) {
            long rows = lines.count() - 1;
            return rowLimit == null ? rows : Math.min(rows, rowLimit);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static EligiblePersonRow parse(String line) {
        String[] f = line.split(",", -1);
        return new EligiblePersonRow(
                Long.parseLong(f[0]), f[1], f[2], Integer.parseInt(f[3]), f[4], f[5], Long.parseLong(f[6]));
    }
}
