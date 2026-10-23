package io.github.songjunpyo.subsidy.application.loader;

import java.util.Arrays;

/** 적재 방식 (SPEC 9.4). */
public enum LoadMode {
    JPA("jpa"),
    JDBC("jdbc"),
    MYBATIS_BATCH("mybatis-batch"),
    MYBATIS_FOREACH("mybatis-foreach"),
    LOAD_DATA("load-data");

    private final String option;

    LoadMode(String option) {
        this.option = option;
    }

    public String option() {
        return option;
    }

    public static LoadMode from(String option) {
        return Arrays.stream(values())
                .filter(m -> m.option.equals(option))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "알 수 없는 --mode: " + option + " (가능: " + options() + ")"));
    }

    public static String options() {
        return String.join("|", Arrays.stream(values()).map(LoadMode::option).toList());
    }
}
