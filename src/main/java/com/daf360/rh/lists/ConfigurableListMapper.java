package com.daf360.rh.lists;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * MapStruct mapper for configurable list entities and their response DTOs.
 *
 * <p>{@link ConfigurableListValue#getListTypeId()} maps directly to
 * {@link ListValueResponse#getListTypeId()} by name — no nested association needed
 * because {@code listTypeId} is a plain Long column on the entity.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface ConfigurableListMapper {

    ListTypeResponse toTypeResponse(ConfigurableListType type);

    List<ListTypeResponse> toTypeResponseList(List<ConfigurableListType> types);

    ListValueResponse toValueResponse(ConfigurableListValue value);

    List<ListValueResponse> toValueResponseList(List<ConfigurableListValue> values);
}
