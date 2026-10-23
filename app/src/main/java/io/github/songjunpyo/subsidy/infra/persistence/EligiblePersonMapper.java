package io.github.songjunpyo.subsidy.infra.persistence;

import io.github.songjunpyo.subsidy.infra.loader.EligiblePersonRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EligiblePersonMapper {

    void insert(EligiblePersonRow row);

    int insertAll(@Param("rows") List<EligiblePersonRow> rows);
}
