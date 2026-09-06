package com.harmoniasuite.mapping;

import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.dto.DeltaRowDto;
import com.harmoniasuite.dto.EntryDto;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EntryMapper {

    EntryDto toDto(TranslationEntry entry);

    List<EntryDto> toDtoList(List<TranslationEntry> entries);

    @Mapping(source = "id", target = "cellId")
    @Mapping(source = "file", target = "filePath")
    @Mapping(source = "source", target = "source", defaultValue = "")
    @Mapping(source = "translation", target = "translation", defaultValue = "")
    @Mapping(source = "status", target = "status", defaultValue = "")
    DeltaRowDto toDeltaRowDto(TranslationEntry entry);

    List<DeltaRowDto> toDeltaRowDtoList(List<TranslationEntry> entries);
}
