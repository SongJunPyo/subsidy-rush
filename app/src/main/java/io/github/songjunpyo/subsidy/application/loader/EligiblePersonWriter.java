package io.github.songjunpyo.subsidy.application.loader;

import java.nio.file.Path;

/** 적재 방식 하나. 테이블 비우기와 시간 측정은 EligiblePersonLoader가 한다. */
public interface EligiblePersonWriter {

    LoadMode mode();

    /** @return 적재한 행 수 */
    long write(Path csv, Long rowLimit);
}
