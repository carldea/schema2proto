/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.schema;

/*-
 * #%L
 * schema2proto-wire
 * %%
 * Copyright (C) 2019 Entur
 * %%
 * Licensed under the EUPL, Version 1.1 or – as soon they will be
 * approved by the European Commission - subsequent versions of the
 * EUPL (the "Licence");
 * 
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 * http://ec.europa.eu/idabc/eupl5
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * #L%
 */

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.*;

import com.google.common.collect.ImmutableList;
import com.squareup.wire.schema.internal.parser.GroupElement;
import com.squareup.wire.schema.internal.parser.MessageElement;
import com.squareup.wire.schema.internal.parser.TypeElement;

public final class MessageType extends Type {
	public static final int FIELD_NUM_INCREMENT = 10;
	private final ProtoType protoType;
	private final Location location;
	private final String documentation;
	private String name;
	private final List<Field> declaredFields;
	private final List<Field> extensionFields;
	private final List<OneOf> oneOfs;
	private final List<Type> nestedTypes;
	private final List<Extensions> extensionsList;
	private final List<Reserved> reserveds;
	private final Options options;
	private int fieldNum = 0;

	public MessageType(ProtoType protoType, Location location, String documentation, String name, List<Field> declaredFields, List<Field> extensionFields,
			List<OneOf> oneOfs, List<Type> nestedTypes, List<Extensions> extensionsList, List<Reserved> reserveds, Options options) {
		this.protoType = protoType;
		this.location = location;
		this.documentation = documentation;
		this.name = name;
		this.declaredFields = declaredFields;
		this.extensionFields = extensionFields;
		this.oneOfs = oneOfs;
		this.nestedTypes = nestedTypes;
		this.extensionsList = extensionsList;
		this.reserveds = reserveds;
		this.options = checkNotNull(options);
	}

	@Override
	public String toString() {
		return "MessageType [name=" + name + "]";
	}

	public int getNextFieldNum() {
		fieldNum++;
		return fieldNum;
	}

	public void advanceFieldNum() {

		int newFieldNum = (fieldNum + FIELD_NUM_INCREMENT) - (fieldNum % FIELD_NUM_INCREMENT);
		if (newFieldNum - (FIELD_NUM_INCREMENT / 3) < fieldNum) {
			// Ensure at least 30% field nums available for later
			fieldNum = newFieldNum;
			advanceFieldNum();
		} else {
			fieldNum = newFieldNum;

		}

	}

	public String getName() {
		return name;
	}

	public void updateName(String newName) {
		this.name = newName;
	}

	@Override
	public Location location() {
		return location;
	}

	@Override
	public ProtoType type() {
		return protoType;
	}

	@Override
	public String documentation() {
		return documentation;
	}

	@Override
	public List<Type> nestedTypes() {
		return nestedTypes;
	}

	@Override
	public Options options() {
		return options;
	}

	public void addField(Field f) {
		declaredFields.add(f);
	}

	public ImmutableList<Field> fields() {
		return ImmutableList.<Field>builder().addAll(declaredFields).addAll(extensionFields).build();
	}

	public void removeDeclaredField(Field f) {
		declaredFields.remove(f);
	}

	public ImmutableList<Field> extensionFields() {
		return ImmutableList.copyOf(extensionFields);
	}

	public ImmutableList<Field> getRequiredFields() {
		ImmutableList.Builder<Field> required = ImmutableList.builder();
		for (Field field : fieldsAndOneOfFields()) {
			if (field.isRequired()) {
				required.add(field);
			}
		}
		return required.build();
	}

	public ImmutableList<Field> fieldsAndOneOfFields() {
		ImmutableList.Builder<Field> result = ImmutableList.builder();
		result.addAll(declaredFields);
		result.addAll(extensionFields);
		for (OneOf oneOf : oneOfs) {
			result.addAll(oneOf.fields());
		}
		return result.build();
	}

	/** Returns the field named {@code name}, or null if this type has no such field. */
	public Field field(String name) {
		for (Field field : declaredFields) {
			if (field.name().equals(name)) {
				return field;
			}
		}
		for (OneOf oneOf : oneOfs) {
			for (Field field : oneOf.fields()) {
				if (field.name().equals(name)) {
					return field;
				}
			}
		}
		return null;
	}

	/**
	 * Returns the field with the qualified name {@code qualifiedName}, or null if this type has no such field.
	 */
	public Field extensionField(String qualifiedName) {
		for (Field field : extensionFields) {
			if (field.qualifiedName().equals(qualifiedName)) {
				return field;
			}
		}
		return null;
	}

	/** Returns the field tagged {@code tag}, or null if this type has no such field. */
	public Field field(int tag) {
		for (Field field : declaredFields) {
			if (field.tag() == tag) {
				return field;
			}
		}
		for (Field field : extensionFields) {
			if (field.tag() == tag) {
				return field;
			}
		}
		return null;
	}

	public List<OneOf> oneOfs() {
		return oneOfs;
	}

	public List<Extensions> extensions() {
		return extensionsList;
	}

	Map<String, Field> extensionFieldsMap() {
		// TODO(jwilson): simplify this to just resolve field values directly.
		Map<String, Field> extensionsForType = new LinkedHashMap<>();
		for (Field field : extensionFields) {
			extensionsForType.put(field.qualifiedName(), field);
		}
		return extensionsForType;
	}

	void addExtensionFields(List<Field> fields) {
		extensionFields.addAll(fields);
	}

	void link(Linker linker) {
		linker = linker.withContext(this);
		for (Field field : declaredFields) {
			field.link(linker);
		}
		for (Field field : extensionFields) {
			field.link(linker);
		}
		for (OneOf oneOf : oneOfs) {
			oneOf.link(linker);
		}
		for (Type type : nestedTypes) {
			type.link(linker);
		}
	}

	void linkOptions(Linker linker) {
		linker = linker.withContext(this);
		for (Type type : nestedTypes) {
			type.linkOptions(linker);
		}
		for (Field field : declaredFields) {
			field.linkOptions(linker);
		}
		for (Field field : extensionFields) {
			field.linkOptions(linker);
		}
		for (OneOf oneOf : oneOfs) {
			oneOf.linkOptions(linker);
		}
		options.link(linker);
	}

	void validate(Linker linker) {
		linker = linker.withContext(this);
		linker.validateFields(fieldsAndOneOfFields(), reserveds);
		linker.validateEnumConstantNameUniqueness(nestedTypes);
		for (Field field : fieldsAndOneOfFields()) {
			field.validate(linker);
		}
		for (Type type : nestedTypes) {
			type.validate(linker);
		}
		for (Extensions extensions : extensionsList) {
			extensions.validate(linker);
		}
	}

	@Override
	Type retainAll(Schema schema, MarkSet markSet) {
		ImmutableList.Builder<Type> retainedNestedTypesBuilder = ImmutableList.builder();
		for (Type nestedType : nestedTypes) {
			Type retainedNestedType = nestedType.retainAll(schema, markSet);
			if (retainedNestedType != null) {
				retainedNestedTypesBuilder.add(retainedNestedType);
			}
		}

		ImmutableList<Type> retainedNestedTypes = retainedNestedTypesBuilder.build();
		if (!markSet.contains(protoType)) {
			// If this type is not retained, and none of its nested types are retained, prune it.
			if (retainedNestedTypes.isEmpty()) {
				return null;
			}
			// If this type is not retained but retained nested types, replace it with an enclosing type.
			return new EnclosingType(location, protoType, documentation, retainedNestedTypes);
		}

		ImmutableList.Builder<OneOf> retainedOneOfsBuilder = ImmutableList.builder();
		for (OneOf oneOf : oneOfs) {
			OneOf retainedOneOf = oneOf.retainAll(schema, markSet, protoType);
			if (retainedOneOf != null) {
				retainedOneOfsBuilder.add(retainedOneOf);
			}
		}
		ImmutableList<OneOf> retainedOneOfs = retainedOneOfsBuilder.build();

		return new MessageType(protoType, location, documentation, name, Field.retainAll(schema, markSet, protoType, declaredFields),
				Field.retainAll(schema, markSet, protoType, extensionFields), retainedOneOfs, retainedNestedTypes, extensionsList, reserveds,
				options.retainAll(schema, markSet));
	}

	static MessageType fromElement(String packageName, ProtoType protoType, MessageElement messageElement) {
		if (!messageElement.getGroups().isEmpty()) {
			GroupElement group = messageElement.getGroups().get(0);
			throw new IllegalStateException(group.getLocation() + ": 'group' is not supported");
		}

		ImmutableList<Field> declaredFields = Field.fromElements(packageName, messageElement.getFields(), false);

		// Extension fields be populated during linking.
		List<Field> extensionFields = new ArrayList<>();

		ImmutableList<OneOf> oneOfs = OneOf.fromElements(packageName, messageElement.getOneOfs(), false);

		ImmutableList.Builder<Type> nestedTypes = ImmutableList.builder();
		for (TypeElement nestedType : messageElement.getNestedTypes()) {
			nestedTypes.add(Type.get(packageName, protoType.nestedType(nestedType.getName()), nestedType));
		}

		List<Extensions> extensionsList = Extensions.fromElements(messageElement.getExtensions());

		List<Reserved> reserveds = Reserved.fromElements(messageElement.getReserveds());

		Options options = new Options(Options.MESSAGE_OPTIONS, messageElement.getOptions());

		return new MessageType(protoType, messageElement.getLocation(), messageElement.getDocumentation(), messageElement.getName(), declaredFields,
				extensionFields, oneOfs, nestedTypes.build(), extensionsList, reserveds, options);
	}

	MessageElement toElement() {
		return new MessageElement(location, name, documentation, Type.toElements(nestedTypes), options.toElements(), Reserved.toElements(reserveds),
				Field.toElements(declaredFields), OneOf.toElements(oneOfs), Extensions.toElements(extensionsList), Collections.emptyList() // groups
		);
	}
}
