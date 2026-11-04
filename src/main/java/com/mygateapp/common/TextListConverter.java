package com.mygateapp.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;

/** Stores a short list of strings as newline-separated text. */
@Converter
public class TextListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join("\n", values.stream().map(v -> v.replace('\n', ' ').trim()).filter(v -> !v.isEmpty()).toList());
    }

    @Override
    public List<String> convertToEntityAttribute(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("\n")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
