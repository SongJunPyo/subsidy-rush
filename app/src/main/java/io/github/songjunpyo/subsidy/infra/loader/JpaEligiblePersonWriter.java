package io.github.songjunpyo.subsidy.infra.loader;

import io.github.songjunpyo.subsidy.application.loader.EligiblePersonWriter;
import io.github.songjunpyo.subsidy.application.loader.LoadMode;
import io.github.songjunpyo.subsidy.domain.EligiblePerson;
import io.github.songjunpyo.subsidy.domain.TargetGroup;
import io.github.songjunpyo.subsidy.infra.persistence.EligiblePersonRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** jpa: Spring Data saveAll(). 1000건마다 saveAll 1회 = 트랜잭션 1개. */
@Component
@Profile("loader")
public class JpaEligiblePersonWriter implements EligiblePersonWriter {

    private final EligiblePersonRepository repository;

    public JpaEligiblePersonWriter(EligiblePersonRepository repository) {
        this.repository = repository;
    }

    @Override
    public LoadMode mode() {
        return LoadMode.JPA;
    }

    @Override
    public long write(Path csv, Long rowLimit) {
        return EligiblePersonCsv.readChunks(csv, rowLimit, chunk -> repository.saveAll(
                chunk.stream()
                        .map(r -> new EligiblePerson(r.personId(), r.ciHash(), r.name(), r.birthYear(),
                                r.regionCode(), TargetGroup.valueOf(r.targetGroup()), r.supportAmount()))
                        .toList()));
    }
}
