package com.jobbuddy.backend.common.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

@Data
public class MapBackedDto {
    @JsonIgnore
    private final Map<String, Object> fields = new LinkedHashMap<String, Object>();

    public MapBackedDto() {
    }

    public MapBackedDto(Map<String, Object> fields) {
        if (fields != null) {
            this.fields.putAll(fields);
        }
    }

    @JsonAnyGetter
    public Map<String, Object> fields() {
        return fields;
    }

    @JsonAnySetter
    public void set(String name, Object value) {
        fields.put(name, value);
    }

    @JsonIgnore
    public Object get(String name) {
        return fields.get(name);
    }

    @JsonIgnore
    public Map<String, Object> toMap() {
        return new LinkedHashMap<String, Object>(fields);
    }

    public void put(String name, Object value) {
        fields.put(name, value);
    }

    public void putAll(Map<String, Object> values) {
        if (values != null) {
            fields.putAll(values);
        }
    }

    public static <T extends MapBackedDto> T fromMap(Map<String, Object> source, Supplier<T> supplier) {
        T target = supplier.get();
        target.putAll(source);
        return target;
    }

    public static <T extends MapBackedDto> List<T> fromMapList(List<Map<String, Object>> source, Function<Map<String, Object>, T> mapper) {
        List<T> result = new ArrayList<T>();
        if (source == null) {
            return result;
        }
        for (Map<String, Object> item : source) {
            result.add(mapper.apply(item));
        }
        return result;
    }
}
