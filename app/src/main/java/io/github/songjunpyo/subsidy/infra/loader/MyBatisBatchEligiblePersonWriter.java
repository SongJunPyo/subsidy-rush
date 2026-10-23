package io.github.songjunpyo.subsidy.infra.loader;

import io.github.songjunpyo.subsidy.application.loader.EligiblePersonWriter;
import io.github.songjunpyo.subsidy.application.loader.LoadMode;
import io.github.songjunpyo.subsidy.infra.persistence.EligiblePersonMapper;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** mybatis-batch: ExecutorType.BATCH, 1000건마다 flushStatements() + commit. */
@Component
@Profile("loader")
public class MyBatisBatchEligiblePersonWriter implements EligiblePersonWriter {

    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisBatchEligiblePersonWriter(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Override
    public LoadMode mode() {
        return LoadMode.MYBATIS_BATCH;
    }

    @Override
    public long write(Path csv, Long rowLimit) {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            EligiblePersonMapper mapper = session.getMapper(EligiblePersonMapper.class);
            return EligiblePersonCsv.readChunks(csv, rowLimit, chunk -> {
                chunk.forEach(mapper::insert);
                session.flushStatements();
                session.commit();
            });
        }
    }
}
