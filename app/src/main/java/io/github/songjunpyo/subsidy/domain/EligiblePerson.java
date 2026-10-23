package io.github.songjunpyo.subsidy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 대상자 명단. 이벤트 중 바뀌지 않는다 (SPEC 1절).
 * person_id를 직접 넣는 엔티티라 save()가 INSERT 전에 SELECT를 한다 (SPEC 9.4, 관찰 대상).
 */
@Entity
@Table(name = "eligible_person")
public class EligiblePerson {

    @Id
    @Column(name = "person_id")
    private Long personId;

    @Column(name = "ci_hash", nullable = false, length = 64)
    private String ciHash;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(name = "birth_year", nullable = false)
    private int birthYear;

    @Column(name = "region_code", nullable = false, length = 2)
    private String regionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_group", nullable = false, length = 20)
    private TargetGroup targetGroup;

    @Column(name = "support_amount", nullable = false)
    private long supportAmount;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected EligiblePerson() {
    }

    public EligiblePerson(Long personId, String ciHash, String name, int birthYear,
                          String regionCode, TargetGroup targetGroup, long supportAmount) {
        this.personId = personId;
        this.ciHash = ciHash;
        this.name = name;
        this.birthYear = birthYear;
        this.regionCode = regionCode;
        this.targetGroup = targetGroup;
        this.supportAmount = supportAmount;
    }

    public Long getPersonId() {
        return personId;
    }

    public String getCiHash() {
        return ciHash;
    }

    public String getName() {
        return name;
    }

    public int getBirthYear() {
        return birthYear;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public TargetGroup getTargetGroup() {
        return targetGroup;
    }

    public long getSupportAmount() {
        return supportAmount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
