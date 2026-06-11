package com.daf360.rh.lists;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConfigurableListValueRepository extends JpaRepository<ConfigurableListValue, Long> {

    @Query("SELECT v FROM ConfigurableListValue v " +
           "WHERE v.listTypeId = :listTypeId " +
           "AND v.isActive = true " +
           "AND (v.paysId IS NULL OR v.paysId = :paysId) " +
           "ORDER BY v.sortOrder ASC, v.labelFr ASC")
    List<ConfigurableListValue> findActiveByListTypeAndPays(
            @Param("listTypeId") Long listTypeId,
            @Param("paysId") Long paysId);

    List<ConfigurableListValue> findByListTypeIdAndPaysIdIsNullAndIsActiveTrueOrderBySortOrderAsc(Long listTypeId);

    List<ConfigurableListValue> findByListTypeIdOrderBySortOrderAscLabelFrAsc(Long listTypeId);

    boolean existsByListTypeIdAndPaysIdAndValueCode(Long listTypeId, Long paysId, String valueCode);
}
