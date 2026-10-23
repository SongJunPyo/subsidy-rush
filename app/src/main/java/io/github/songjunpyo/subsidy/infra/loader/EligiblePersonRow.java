package io.github.songjunpyo.subsidy.infra.loader;

/** eligible_person.csv 한 줄 (SPEC 9.1). */
public record EligiblePersonRow(
        long personId,
        String ciHash,
        String name,
        int birthYear,
        String regionCode,
        String targetGroup,
        long supportAmount
) {
}
