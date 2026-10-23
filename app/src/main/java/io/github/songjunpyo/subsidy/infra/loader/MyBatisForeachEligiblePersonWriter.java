package io.github.songjunpyo.subsidy.infra.loader;

import io.github.songjunpyo.subsidy.application.loader.EligiblePersonWriter;
import io.github.songjunpyo.subsidy.application.loader.LoadMode;
import io.github.songjunpyo.subsidy.infra.persistence.EligiblePersonMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** mybatis-foreach: 1000건을 INSERT ... VALUES (...), (...) 한 문장으로. */
@Component
@Profile("loader")
public class MyBatisForeachEligiblePersonWriter implements EligiblePersonWriter {

    private final EligiblePersonMapper mapper;

    public MyBatisForeachEligiblePersonWriter(EligiblePersonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LoadMode mode() {
        return LoadMode.MYBATIS_FOREACH;
    }

    @Override
    public long write(Path csv, Long rowLimit) {
        return EligiblePersonCsv.readChunks(csv, rowLimit, mapper::insertAll);
    }
}
