/*
 * Copyright 2019 liaochong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.liaochong.myexcel.core;

import com.github.liaochong.myexcel.core.annotation.ExcelColumn;
import com.github.liaochong.myexcel.core.annotation.ExcelTable;
import com.github.liaochong.myexcel.core.annotation.ExcludeColumn;
import com.github.liaochong.myexcel.core.constant.Constants;
import com.github.liaochong.myexcel.core.container.Pair;
import com.github.liaochong.myexcel.core.container.ParallelContainer;
import com.github.liaochong.myexcel.core.converter.WriteConverterContext;
import com.github.liaochong.myexcel.core.reflect.ClassFieldContainer;
import com.github.liaochong.myexcel.utils.ReflectUtil;
import com.github.liaochong.myexcel.utils.StringUtil;
import com.github.liaochong.myexcel.utils.TempFileOperator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * CSV文件构建器
 *
 * @author liaochong
 * @version 1.0
 */
public class CsvBuilder<T> {

    private static final Pattern PATTERN_COMMA = Pattern.compile(",+");

    private static final Pattern PATTERN_QUOTES = Pattern.compile("\"");

    private Class<T> type;

    private String globalDefaultValue;
    /**
     * 默认值集合
     */
    private Map<Field, String> defaultValueMap;
    /**
     * 标题
     */
    private List<String> titles;

    private boolean isAppend = true;

    private CsvBuilder(Class<T> type) {
        this.type = type;
    }

    public static <T> CsvBuilder<T> of(Class<T> clazz) {
        return new CsvBuilder<>(clazz);
    }

    public Csv build(List<T> beans, Class<?>... groups) {
        isAppend = false;
        Path path = TempFileOperator.createTempFile("d_t_c", Constants.CSV);
        return this.build(beans, new Csv(path), groups);
    }

    public Csv build(List<T> beans, Csv csv, Class<?>... groups) {
        ClassFieldContainer classFieldContainer = ReflectUtil.getAllFieldsOfClass(type);
        List<Field> fields = getFields(classFieldContainer, groups);
        if (beans == null || beans.isEmpty()) {
            return csv;
        }
        List<List<?>> contents = getRenderContent(beans, fields);
        this.writeToCsv(contents, csv);
        return csv;
    }

    private List<Field> getFields(ClassFieldContainer classFieldContainer, Class<?>... groups) {
        ExcelTable excelTable = classFieldContainer.getClazz().getAnnotation(ExcelTable.class);
        boolean excelTableExist = Objects.nonNull(excelTable);
        boolean excludeParent = false;
        boolean includeAllField = false;
        boolean ignoreStaticFields = true;
        if (excelTableExist) {
            excludeParent = excelTable.excludeParent();
            includeAllField = excelTable.includeAllField();
            if (!excelTable.defaultValue().isEmpty()) {
                globalDefaultValue = excelTable.defaultValue();
            }
            ignoreStaticFields = excelTable.ignoreStaticFields();
        }
        List<Field> preElectionFields = this.getPreElectionFields(classFieldContainer, excludeParent, includeAllField);
        if (ignoreStaticFields) {
            preElectionFields = preElectionFields.stream()
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .collect(Collectors.toList());
        }
        List<Class<?>> selectedGroupList = Objects.nonNull(groups) ? Arrays.stream(groups).filter(Objects::nonNull).collect(Collectors.toList()) : Collections.emptyList();
        boolean useFieldNameAsTitle = excelTableExist && excelTable.useFieldNameAsTitle();
        List<String> titles = new ArrayList<>(preElectionFields.size());
        List<Field> sortedFields = preElectionFields.stream()
                .filter(field -> !field.isAnnotationPresent(ExcludeColumn.class) && filterFields(selectedGroupList, field))
                .sorted(this::sortFields)
                .collect(Collectors.toList());
        defaultValueMap = new HashMap<>(preElectionFields.size());
        boolean needToAddTitle = this.titles == null;
        for (int i = 0, size = sortedFields.size(); i < size; i++) {
            Field field = sortedFields.get(i);
            ExcelColumn excelColumn = field.getAnnotation(ExcelColumn.class);
            if (excelColumn != null) {
                if (needToAddTitle) {
                    if (useFieldNameAsTitle && excelColumn.title().isEmpty()) {
                        titles.add(field.getName());
                    } else {
                        titles.add(excelColumn.title());
                    }
                }
                if (!excelColumn.defaultValue().isEmpty()) {
                    defaultValueMap.put(field, excelColumn.defaultValue());
                }
            } else {
                if (needToAddTitle) {
                    if (useFieldNameAsTitle) {
                        titles.add(field.getName());
                    } else {
                        titles.add(null);
                    }
                }
            }
        }

        boolean hasTitle = titles.stream().anyMatch(StringUtil::isNotBlank);
        if (hasTitle) {
            this.titles = titles;
        }
        return sortedFields;
    }

    private List<Field> getPreElectionFields(ClassFieldContainer classFieldContainer, boolean excludeParent, boolean includeAllField) {
        if (includeAllField) {
            if (excludeParent) {
                return classFieldContainer.getDeclaredFields();
            } else {
                return classFieldContainer.getFields();
            }
        }
        if (excludeParent) {
            return classFieldContainer.getDeclaredFields().stream()
                    .filter(field -> field.isAnnotationPresent(ExcelColumn.class)).collect(Collectors.toList());
        } else {
            return classFieldContainer.getFieldsByAnnotation(ExcelColumn.class);
        }
    }

    private boolean filterFields(List<Class<?>> selectedGroupList, Field field) {
        if (selectedGroupList.isEmpty()) {
            return true;
        }
        ExcelColumn excelColumn = field.getAnnotation(ExcelColumn.class);
        if (excelColumn == null) {
            return false;
        }
        Class<?>[] groupArr = excelColumn.groups();
        if (groupArr.length == 0) {
            return false;
        }
        List<Class<?>> reservedGroupList = Arrays.stream(groupArr).collect(Collectors.toList());
        return reservedGroupList.stream().anyMatch(selectedGroupList::contains);
    }

    private int sortFields(Field field1, Field field2) {
        ExcelColumn excelColumn1 = field1.getAnnotation(ExcelColumn.class);
        ExcelColumn excelColumn2 = field2.getAnnotation(ExcelColumn.class);
        if (excelColumn1 == null && excelColumn2 == null) {
            return 0;
        }
        int defaultOrder = 0;
        int order1 = defaultOrder;
        if (excelColumn1 != null) {
            order1 = excelColumn1.order();
        }
        int order2 = defaultOrder;
        if (excelColumn2 != null) {
            order2 = excelColumn2.order();
        }
        if (order1 == order2) {
            return 0;
        }
        return order1 > order2 ? 1 : -1;
    }

    /**
     * 获取需要被渲染的内容
     *
     * @param data         数据集合
     * @param sortedFields 排序字段
     * @return 结果集
     */
    private List<List<?>> getRenderContent(List<T> data, List<Field> sortedFields) {
        List<ParallelContainer> resolvedDataContainers = IntStream.range(0, data.size()).parallel().mapToObj(index -> {
            List<?> resolvedDataList = this.getRenderContent(data.get(index), sortedFields);
            return new ParallelContainer<>(index, resolvedDataList);
        }).collect(Collectors.toCollection(LinkedList::new));
        data.clear();

        // 重排序
        return resolvedDataContainers.stream()
                .sorted(Comparator.comparing(ParallelContainer::getIndex))
                .map(ParallelContainer<List<Pair<? extends Class, ?>>>::getData).collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * 获取需要被渲染的内容
     *
     * @param data         数据集合
     * @param sortedFields 排序字段
     * @return 结果集
     */
    private List<?> getRenderContent(T data, List<Field> sortedFields) {
        return sortedFields.stream()
                .map(field -> {
                    Pair<? extends Class, Object> value = WriteConverterContext.convert(field, data);
                    if (value.getValue() != null) {
                        return value;
                    }
                    String defaultValue = defaultValueMap.get(field);
                    if (defaultValue != null) {
                        return Pair.of(field.getType(), defaultValue);
                    }
                    if (globalDefaultValue != null) {
                        return Pair.of(field.getType(), globalDefaultValue);
                    }
                    return value;
                })
                .map(Pair::getValue)
                .collect(Collectors.toCollection(LinkedList::new));
    }

    private void writeToCsv(List<List<?>> data, Csv csv) {
        if (!isAppend && titles != null) {
            data.add(0, titles);
        }
        List<String> content = data.stream().map(d -> {
            return d.stream().map(v -> {
                if (v == null) {
                    return "\"\"";
                }
                String vStr = v.toString();
                vStr = PATTERN_QUOTES.matcher(vStr).replaceAll("\"\"");
                boolean hasComma = PATTERN_COMMA.matcher(v.toString()).find();
                if (hasComma) {
                    vStr = "\"" + vStr + "\"";
                }
                return vStr;
            }).collect(Collectors.joining(Constants.COMMA));
        }).collect(Collectors.toCollection(LinkedList::new));

        try {
            Files.write(csv.getFilePath(), content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
