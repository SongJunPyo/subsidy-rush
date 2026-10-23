package io.github.songjunpyo.subsidy.infra.persistence;

import io.github.songjunpyo.subsidy.domain.EligiblePerson;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EligiblePersonRepository extends JpaRepository<EligiblePerson, Long> {
}
